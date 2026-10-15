#!/usr/bin/env python3
"""生成无线调试配对二维码，手机扫码后用局域网地址执行 adb pair。"""

from __future__ import annotations

import re
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


def connect_script() -> Path:
    return DIR.parent / "scripts" / "connect.sh"


def tokens_ok(name: str, password: str) -> bool:
    return re.fullmatch(r"studio-[a-z0-9]{10}", name) is not None and re.fullmatch(r"[a-z0-9]{12}", password) is not None


def wait_and_pair(name: str, password: str) -> int:
    browse = adb_mdns.Dns(["-B", adb_mdns.PAIRING, "local"])
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
        log("没有扫到。")
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
        log("配对失败。")
        return 1

    connect = subprocess.run([str(connect_script())], check=False)
    if connect.returncode == 0:
        log("配对完成。")
    return connect.returncode


def listen(name: str) -> int:
    password = sys.stdin.read().strip()
    if not tokens_ok(name, password):
        log("配对参数无效。")
        return 1
    return wait_and_pair(name, password)


def main() -> int:
    alphabet = string.ascii_lowercase + string.digits
    name = "studio-" + "".join(secrets.choice(alphabet) for _ in range(10))
    password = "".join(secrets.choice(alphabet) for _ in range(12))
    payload = f"WIFI:T:ADB;S:{name};P:{password};;"
    generate(payload)
    time.sleep(0.4)
    subprocess.run(["open", str(HTML)], check=False)
    log("二维码已打开。手机：无线调试 → 使用二维码配对设备。")
    code = wait_and_pair(name, password)
    if code == 0:
        log("浏览器里的二维码页面可以关掉。")
    else:
        log("重新运行 pair-qr.sh 会生成新的二维码。")
    return code


if __name__ == "__main__":
    if len(sys.argv) == 3 and sys.argv[1] == "listen":
        sys.exit(listen(sys.argv[2]))
    sys.exit(main())
