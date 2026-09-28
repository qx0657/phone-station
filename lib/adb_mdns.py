#!/usr/bin/env python3
"""解析手机无线调试的 mDNS，避开被 Clash 隧道吃掉的地址。"""

from __future__ import annotations

import argparse
import fcntl
import os
import pty
import re
import select
import struct
import subprocess
import sys
import termios
import time

CONNECT = "_adb-tls-connect._tcp"
PAIRING = "_adb-tls-pairing._tcp"


class Dns:
    def __init__(self, args: list[str]):
        master, slave = pty.openpty()
        fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack("HHHH", 60, 240, 0, 0))
        self.proc = subprocess.Popen(
            ["dns-sd", *args],
            stdin=subprocess.DEVNULL,
            stdout=slave,
            stderr=slave,
            close_fds=True,
        )
        os.close(slave)
        self.master = master
        self.buf = ""

    def readline(self, timeout: float) -> str | None:
        deadline = time.time() + max(timeout, 0.05)
        while time.time() < deadline:
            if "\n" in self.buf:
                line, self.buf = self.buf.split("\n", 1)
                return line.rstrip("\r")
            remaining = deadline - time.time()
            if remaining <= 0:
                break
            ready, _, _ = select.select([self.master], [], [], min(remaining, 0.4))
            if not ready:
                continue
            try:
                chunk = os.read(self.master, 4096)
            except OSError:
                return None
            if not chunk:
                return None
            self.buf += chunk.decode("utf-8", "replace")
        if "\n" in self.buf:
            line, self.buf = self.buf.split("\n", 1)
            return line.rstrip("\r")
        return None

    def close(self) -> None:
        self.proc.terminate()
        try:
            self.proc.wait(timeout=1)
        except subprocess.TimeoutExpired:
            self.proc.kill()
        try:
            os.close(self.master)
        except OSError:
            pass


def browse_instances(service: str, timeout: float, name_contains: str | None = None) -> list[str]:
    dns = Dns(["-B", service, "local"])
    found: list[str] = []
    deadline = time.time() + timeout
    quiet_until = None
    try:
        while time.time() < deadline:
            line = dns.readline(min(0.8, max(0.1, deadline - time.time())))
            if line is None:
                if dns.proc.poll() is not None:
                    break
                if found and quiet_until and time.time() >= quiet_until:
                    break
                continue
            if not re.search(r"\sAdd\s", line):
                continue
            if service not in line:
                continue
            name = line.split()[-1]
            if name_contains and name_contains not in name:
                continue
            if name not in found:
                found.append(name)
                quiet_until = time.time() + 0.7
            if found and quiet_until and time.time() >= quiet_until:
                break
    finally:
        dns.close()
    return found


def lookup(instance: str, service: str, timeout: float = 6) -> tuple[str, int] | None:
    dns = Dns(["-L", instance, service, "local"])
    deadline = time.time() + timeout
    try:
        while time.time() < deadline:
            line = dns.readline(min(0.8, max(0.1, deadline - time.time())))
            if line is None:
                if dns.proc.poll() is not None:
                    break
                continue
            match = re.search(r"can be reached at\s+(\S+?):(\d+)", line)
            if match:
                return match.group(1), int(match.group(2))
    finally:
        dns.close()
    return None


def resolve_v4(host: str, timeout: float = 3) -> list[str]:
    if re.fullmatch(r"(?:\d{1,3}\.){3}\d{1,3}", host):
        return [host]
    dns = Dns(["-G", "v4", host])
    found: list[str] = []
    deadline = time.time() + timeout
    quiet_until = None
    try:
        while time.time() < deadline:
            line = dns.readline(min(0.6, max(0.1, deadline - time.time())))
            if line is None:
                if dns.proc.poll() is not None:
                    break
                if found and quiet_until and time.time() >= quiet_until:
                    break
                continue
            for ip in re.findall(r"\b(?:\d{1,3}\.){3}\d{1,3}\b", line):
                if ip not in found:
                    found.append(ip)
                    quiet_until = time.time() + 0.6
    finally:
        dns.close()
    return found


def route_interface(ip: str) -> str:
    try:
        result = subprocess.run(
            ["route", "-n", "get", ip],
            capture_output=True,
            text=True,
            timeout=2,
        )
    except (OSError, subprocess.TimeoutExpired):
        return ""
    for line in result.stdout.splitlines():
        stripped = line.strip()
        if stripped.startswith("interface:"):
            return stripped.split()[-1]
    return ""


def via_tun(ip: str) -> bool:
    return route_interface(ip).startswith("utun")


def rank(ip: str) -> int:
    a, b = (int(part) for part in ip.split(".")[:2])
    if a == 192 and b == 168:
        return 0
    if a == 10:
        return 1
    if a == 172 and 16 <= b <= 31:
        return 4
    return 3


def choose_ip(ips: list[str]) -> tuple[str | None, list[str]]:
    usable: list[str] = []
    skipped: list[str] = []
    seen: set[str] = set()
    for ip in ips:
        if ip in seen:
            continue
        seen.add(ip)
        if via_tun(ip):
            skipped.append(ip)
        else:
            usable.append(ip)
    if not usable:
        return None, skipped
    return sorted(usable, key=rank)[0], skipped


class Service:
    def __init__(self, instance: str, host: str, port: int, ips: list[str]):
        self.instance = instance
        self.host = host
        self.port = port
        self.ip, self.skipped = choose_ip(ips)


def discover(service: str, timeout: float, name_contains: str | None = None) -> list[Service]:
    services: list[Service] = []
    for instance in browse_instances(service, timeout, name_contains):
        located = lookup(instance, service)
        if not located:
            continue
        host, port = located
        services.append(Service(instance, host, port, resolve_v4(host)))
    return services


def emit(services: list[Service]) -> int:
    usable = 0
    for service in services:
        if service.ip:
            usable += 1
            print(f"use\t{service.instance}\t{service.ip}\t{service.port}", flush=True)
        for ip in service.skipped:
            iface = route_interface(ip) or "tun"
            print(f"skip\t{service.instance}\t{ip}\t{iface}", flush=True)
    if usable:
        return 0
    if services:
        print(
            "只发现了走 utun 的地址（常见是手机上显示的 172.19.0.1）。"
            "那个地址会被 Clash 隧道吃掉，adb 会报 protocol fault。",
            file=sys.stderr,
        )
        return 2
    print(
        "没有发现无线调试。手机打开开发者选项里的无线调试，和电脑连同一个 Wi-Fi。",
        file=sys.stderr,
    )
    return 1


def adb_output(adb: str, *args: str) -> str:
    result = subprocess.run([adb, *args], capture_output=True, text=True)
    return (result.stdout or "") + (result.stderr or "")


class Device:
    def __init__(self, serial: str, state: str, meta: dict[str, str]):
        self.serial = serial
        self.state = state
        self.meta = meta

    def key(self) -> tuple[str, str, str]:
        product = self.meta.get("product", "")
        model = self.meta.get("model", "")
        device = self.meta.get("device", "")
        if product or model or device:
            return product, model, device
        return self.serial, "", ""

    def wireless(self) -> bool:
        serial = self.serial
        return ":" in serial or "_adb-tls-" in serial or serial.endswith("._tcp")


def parse_devices(text: str) -> list[Device]:
    devices: list[Device] = []
    for raw in text.splitlines():
        parts = raw.split()
        if len(parts) < 2 or parts[0] == "List":
            continue
        if parts[1] not in {"device", "offline", "unauthorized", "authorizing"}:
            continue
        meta: dict[str, str] = {}
        for item in parts[2:]:
            if ":" in item:
                key, value = item.split(":", 1)
                meta[key] = value
        devices.append(Device(parts[0], parts[1], meta))
    return devices


def list_devices(adb: str) -> list[Device]:
    return parse_devices(adb_output(adb, "devices", "-l"))


def disconnect(adb: str, serial: str) -> None:
    result = subprocess.run([adb, "disconnect", serial], capture_output=True, text=True)
    message = ((result.stdout or "") + (result.stderr or "")).strip()
    if message:
        print(message)


def prefer(group: list[Device]) -> Device:
    for device in group:
        if "_adb-tls-connect._tcp" in device.serial:
            return device
    return group[0]


def collapse(adb: str) -> int:
    for device in list_devices(adb):
        if device.state == "offline" and device.wireless():
            print(f"断开离线连接 {device.serial}")
            disconnect(adb, device.serial)

    groups: dict[tuple[str, str, str], list[Device]] = {}
    for device in list_devices(adb):
        if device.state != "device":
            continue
        groups.setdefault(device.key(), []).append(device)

    for group in groups.values():
        if len(group) < 2:
            continue
        keep = prefer(group)
        for device in group:
            if device.serial == keep.serial:
                continue
            print(f"断开重复连接 {device.serial}，保留 {keep.serial}")
            disconnect(adb, device.serial)

    online = [device for device in list_devices(adb) if device.state == "device"]
    unauthorized = [device for device in list_devices(adb) if device.state == "unauthorized"]
    listing = adb_output(adb, "devices", "-l").rstrip()
    if listing:
        print(listing)
    if unauthorized and not online:
        print("手机上还没点允许这台电脑调试。", file=sys.stderr)
        return 1
    return 0 if online else 1


def has_online(adb: str) -> bool:
    return any(device.state == "device" for device in list_devices(adb))


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="解析无线调试的 mDNS，并整理重复的 adb 连接")
    parser.add_argument("--adb", default="adb")
    sub = parser.add_subparsers(dest="cmd", required=True)

    listed = sub.add_parser("list")
    listed.add_argument("kind", choices=("connect", "pairing"))
    listed.add_argument("timeout", nargs="?", type=float, default=5)

    wait = sub.add_parser("wait")
    wait.add_argument("kind", choices=("connect", "pairing"))
    wait.add_argument("name")
    wait.add_argument("timeout", nargs="?", type=float, default=30)

    sub.add_parser("has-device")
    sub.add_parser("collapse")
    sub.add_parser("self-check")
    return parser


def service_type(kind: str) -> str:
    return CONNECT if kind == "connect" else PAIRING


def main(argv: list[str]) -> int:
    args = build_parser().parse_args(argv)
    if args.cmd == "self-check":
        assert rank("192.168.0.103") < rank("10.0.0.8")
        assert rank("10.0.0.8") < rank("172.16.0.2")
        assert via_tun("172.19.0.1")
        assert not via_tun("192.168.0.103")
        chosen, skipped = choose_ip(["172.19.0.1", "192.168.0.103"])
        assert chosen == "192.168.0.103"
        assert skipped == ["172.19.0.1"]
        assert choose_ip(["172.19.0.1"])[0] is None
        print("self-check ok")
        return 0
    if args.cmd == "has-device":
        return 0 if has_online(args.adb) else 1
    if args.cmd == "collapse":
        return collapse(args.adb)
    if args.cmd == "list":
        return emit(discover(service_type(args.kind), args.timeout))
    if args.cmd == "wait":
        return emit(discover(service_type(args.kind), args.timeout, args.name))
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
