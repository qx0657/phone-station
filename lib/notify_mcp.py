#!/usr/bin/env python3
"""通知优先走统一 MCP 网关；只有提交通知前不可用才允许回退 adb。"""

from __future__ import annotations

import json
from pathlib import Path
import subprocess
import sys
import urllib.request
from urllib.parse import urlsplit

UNAVAILABLE = 75


def gateway() -> tuple[str, str] | None:
    script = Path(__file__).resolve().parent.parent / "scripts/mcp.sh"
    try:
        result = subprocess.run(
            [str(script), "status"], capture_output=True, text=True, timeout=25
        )
    except (OSError, subprocess.TimeoutExpired):
        return None
    if result.returncode:
        return None
    lines = result.stdout.splitlines()
    endpoint = next((line for line in lines if line.startswith("http://")), "")
    auth = next(
        (line.removeprefix("Authorization: ") for line in lines if line.startswith("Authorization: ")),
        "",
    )
    channel = next((line for line in lines if line.startswith("channel=")), "")
    if not endpoint or not auth or not channel.startswith(("channel=local ", "channel=remote ")):
        return None
    parsed = urlsplit(endpoint)
    if parsed.hostname != "127.0.0.1" or parsed.path != "/mcp":
        return None
    return endpoint, auth


def rpc(endpoint: str, auth: str, rid: int, method: str, params: dict) -> dict:
    body = json.dumps(
        {"jsonrpc": "2.0", "id": rid, "method": method, "params": params}, ensure_ascii=False
    ).encode("utf-8")
    request = urllib.request.Request(
        endpoint,
        data=body,
        headers={
            "Authorization": auth,
            "Content-Type": "application/json",
            "Accept": "application/json, text/event-stream",
        },
    )
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open(request, timeout=45) as response:
        reply = json.load(response)
    if not isinstance(reply, dict) or "error" in reply:
        raise ValueError("MCP 请求失败")
    return reply["result"]


def send(title: str, text: str, _legacy_mode: str, agent: str, sound: str) -> int:
    route = gateway()
    if route is None:
        return UNAVAILABLE
    endpoint, auth = route
    # 这里都没有发送通知，失败仍可用 adb；业务请求提交后绝不自动重发。
    try:
        rpc(endpoint, auth, 1, "initialize", {
            "protocolVersion": "2025-03-26", "capabilities": {},
            "clientInfo": {"name": "phone-notify", "version": "1"},
        })
        tools = rpc(endpoint, auth, 2, "tools/list", {}).get("tools", [])
        tool = next((item for item in tools if item.get("name") == "station_notify"), None)
        if tool is None:
            return UNAVAILABLE
        if sound and "sound" not in tool.get("inputSchema", {}).get("properties", {}):
            return UNAVAILABLE
    except (OSError, ValueError, KeyError, TypeError):
        return UNAVAILABLE

    # 显示方式由手机设置决定；保留旧入口的参数位置，但不再发送 mode。
    args = {"title": title, "text": text, "agent": agent}
    if sound:
        args["sound"] = sound
    try:
        result = rpc(endpoint, auth, 3, "tools/call", {"name": "station_notify", "arguments": args})
        content = result.get("content", [])
        payload = next(item["text"] for item in content if item.get("type") == "text")
        detail = json.loads(payload)
        if result.get("isError") or detail.get("posted") is not True:
            print(detail.get("error", "通知没有发出。"), file=sys.stderr)
            return 1
        print("通知已更新" if detail.get("mode") == "replace" else "通知已发出", file=sys.stderr)
        outcome = detail.get("sound")
        if outcome == "played":
            print("played")
            return 0
        if outcome == "silent":
            print("铃声已静音。", file=sys.stderr)
            return 0
        print("铃声没有播放。", file=sys.stderr)
        return 1
    except (OSError, ValueError, KeyError, TypeError, StopIteration):
        print("通知结果未确认，请查看手机；这次不会自动重发。", file=sys.stderr)
        return 1


def main(argv: list[str]) -> int:
    if len(argv) != 5:
        print("notify_mcp.py 由 notify.sh 调用。", file=sys.stderr)
        return 2
    return send(*argv)


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
