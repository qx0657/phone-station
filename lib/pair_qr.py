#!/usr/bin/env python3
"""生成无线调试配对二维码，手机扫码后用局域网地址执行 adb pair。"""

from __future__ import annotations

import secrets
import shutil
import string
import subprocess
import sys
import time
from pathlib import Path

import adb_mdns

DIR = Path(__file__).resolve().parent
PNG = Path("/tmp/adb-pair-qr.png")
HTML = Path("/tmp/adb-pair.html")
TIMEOUT = 180


def log(message: str) -> None:
    print(message, flush=True)


def adb_path() -> str:
    found = shutil.which("adb")
    if found:
        return found
    sdk = Path.home() / "Library/Android/sdk/platform-tools/adb"
    if sdk.is_file():
        return str(sdk)
    raise SystemExit("找不到 adb")


def generate(payload: str) -> None:
    result = subprocess.run(
        ["node", str(DIR / "gen.js"), payload, str(PNG), str(HTML)],
        capture_output=True,
        text=True,
        timeout=30,
    )
    if result.returncode != 0:
        sys.stderr.write(result.stderr or result.stdout)
        raise SystemExit("生成二维码失败")


def main() -> int:
    alphabet = string.ascii_lowercase + string.digits
    name = "studio-" + "".join(secrets.choice(alphabet) for _ in range(10))
    password = "".join(secrets.choice(alphabet) for _ in range(12))
    payload = f"WIFI:T:ADB;S:{name};P:{password};;"
    generate(payload)

    browse = adb_mdns.Dns(["-B", adb_mdns.PAIRING, "local"])
    time.sleep(0.4)
    subprocess.run(["open", str(HTML)], check=False)
    log("二维码已打开。手机：无线调试 → 使用二维码配对设备。")

    hit = False
    deadline = time.time() + TIMEOUT
    try:
        while time.time() < deadline:
            line = browse.readline(min(1.0, max(0.1, deadline - time.time())))
            if line is None:
                if browse.proc.poll() is not None:
                    break
                continue
            if name in line and "Add" in line.split():
                hit = True
                break
    finally:
        browse.close()

    if not hit:
        log("没有扫到。二维码还开着的话，用手机的无线调试扫描；关掉页面后需要重新运行 pair-qr.sh。")
        return 1

    located = adb_mdns.lookup(name, adb_mdns.PAIRING)
    if not located:
        log("手机已广播配对服务，但没有解析到端口。")
        return 1
    host, port = located

    ips = adb_mdns.resolve_v4(host)
    ip, skipped = adb_mdns.choose_ip(ips)
    if ip is None:
        for service in adb_mdns.discover(adb_mdns.CONNECT, 3):
            ips.extend(adb_mdns.resolve_v4(service.host))
        ip, skipped = adb_mdns.choose_ip(ips)
    for skipped_ip in skipped:
        log(f"跳过 {skipped_ip}（路由走隧道）")
    if not ip:
        log("没有可用的局域网地址。")
        return 1

    adb = adb_path()
    log(f"配对 {ip}:{port}")
    paired = subprocess.run(
        [adb, "pair", f"{ip}:{port}", password],
        capture_output=True,
        text=True,
        timeout=40,
    )
    text = ((paired.stdout or "") + (paired.stderr or "")).strip()
    if text:
        log(text)
    if paired.returncode != 0 or "Successfully paired" not in text:
        log("配对失败。重新运行 pair-qr.sh，会生成新的二维码。")
        return 1

    connect = subprocess.run([str(DIR.parent / "connect.sh")], check=False)
    if connect.returncode == 0:
        log("配对完成。浏览器里的二维码页面可以关掉。")
    return connect.returncode


if __name__ == "__main__":
    sys.exit(main())
