#!/usr/bin/env python3
"""把 agent-notify-hook.sh 写进 Codex、Claude、Grok 的用户配置。"""

from __future__ import annotations

import json
import os
import select
import signal
import subprocess
import sys
import threading
import time
from pathlib import Path

MARKER = "/scripts/agent-notify-hook.sh"
TIMEOUT = 10
STATUS = "手机通知铃声"
ASK_MATCHER = "AskUserQuestion|ask_user_question|request_user_input"
NOTIFY_MATCHER = (
    "permission_prompt|agent_needs_input|"
    "elicitation_dialog|elicitation_url_dialog"
)

# Codex 用 Stop 表示一轮完成。SessionEnd 还会在主会话空闲退出时触发，
# 作为铃声提醒会和 Stop 重复，并且可能晚很久才响，所以 Codex 不安装它。
# Codex 当前也没有 Notification 和 Elicitation hook。
EVENTS = {
    "claude": [
        ("Stop", None),
        ("SessionEnd", None),
        ("PermissionRequest", None),
        ("Elicitation", None),
        ("Notification", NOTIFY_MATCHER),
        ("PreToolUse", ASK_MATCHER),
    ],
    "codex": [
        ("Stop", None),
        ("PermissionRequest", None),
        ("PreToolUse", ASK_MATCHER),
    ],
    "grok": [
        ("Stop", None),
        ("SessionEnd", None),
        ("Notification", NOTIFY_MATCHER),
        ("PreToolUse", ASK_MATCHER),
    ],
}


def repo_root() -> Path:
    return Path(__file__).resolve().parent.parent


CLIENT_FLAG = {
    "claude": "Claude",
    "codex": "Codex",
    "grok": "Grok",
}


def hook_command(tool: str, root: Path | None = None) -> str:
    base = repo_root() if root is None else root
    script = str(base / "scripts" / "agent-notify-hook.sh")
    return f"{script} --client {CLIENT_FLAG[tool]}"


def is_our_command(command: object) -> bool:
    text = str(command or "").replace("\\", "/").strip()
    if not text:
        return False
    head = text.split()[0].strip("\"'")
    return head.endswith(MARKER)


def handler(command: str) -> dict:
    return {
        "type": "command",
        "command": command,
        "timeout": TIMEOUT,
        "statusMessage": STATUS,
    }


def _groups(hooks: dict, event: str) -> list:
    groups = hooks.get(event)
    if not isinstance(groups, list):
        groups = []
        hooks[event] = groups
    return groups


def upsert(hooks: dict, event: str, matcher: str | None, command: str) -> None:
    groups = _groups(hooks, event)
    for group in groups:
        if not isinstance(group, dict):
            continue
        found = group.get("hooks")
        if not isinstance(found, list):
            continue
        ours = [item for item in found if isinstance(item, dict) and is_our_command(item.get("command"))]
        if not ours:
            continue
        for item in ours:
            item.clear()
            item.update(handler(command))
        if all(isinstance(item, dict) and is_our_command(item.get("command")) for item in found):
            if matcher:
                group["matcher"] = matcher
            else:
                group.pop("matcher", None)
        return
    group = {"hooks": [handler(command)]}
    if matcher:
        group["matcher"] = matcher
    groups.append(group)


def strip(hooks: dict) -> None:
    for event in list(hooks):
        groups = hooks.get(event)
        if not isinstance(groups, list):
            continue
        kept = []
        for group in groups:
            if not isinstance(group, dict):
                kept.append(group)
                continue
            found = group.get("hooks")
            if not isinstance(found, list):
                kept.append(group)
                continue
            remain = [
                item
                for item in found
                if not (isinstance(item, dict) and is_our_command(item.get("command")))
            ]
            if remain:
                group["hooks"] = remain
                kept.append(group)
        if kept:
            hooks[event] = kept
        else:
            del hooks[event]


def install_into(config: dict, tool: str, command: str) -> None:
    hooks = config.get("hooks")
    if not isinstance(hooks, dict):
        hooks = {}
        config["hooks"] = hooks
    # 先清理旧版安装的条目，确保重装能删掉已经移出 EVENTS 的事件。
    # strip 只识别本仓库的命令，不会动用户或其他插件的 hook。
    strip(hooks)
    for event, matcher in EVENTS[tool]:
        upsert(hooks, event, matcher, command)


def remove_from(config: dict) -> None:
    hooks = config.get("hooks")
    if isinstance(hooks, dict):
        strip(hooks)
        if not hooks:
            config.pop("hooks", None)


def read_json(file: Path) -> dict:
    if not file.exists():
        return {}
    data = json.loads(file.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise SystemExit(f"{file} 不是 JSON 对象")
    return data


def write_json(file: Path, data: dict) -> None:
    file.parent.mkdir(parents=True, exist_ok=True)
    text = json.dumps(data, indent=2, ensure_ascii=False) + "\n"
    tmp = file.with_suffix(file.suffix + ".tmp")
    tmp.write_text(text, encoding="utf-8")
    os.replace(tmp, file)


def paths_for(home: Path) -> dict[str, Path]:
    return {
        "claude": home / ".claude" / "settings.json",
        "codex": home / ".codex" / "hooks.json",
        "grok": home / ".grok" / "hooks" / "phone-notify.json",
    }


def install(home: Path) -> list[Path]:
    written = []
    for tool, file in paths_for(home).items():
        config = read_json(file)
        install_into(config, tool, hook_command(tool))
        write_json(file, config)
        written.append(file)
    return written


def remove(home: Path) -> list[Path]:
    changed = []
    for file in paths_for(home).values():
        if not file.exists():
            continue
        config = read_json(file)
        remove_from(config)
        if file.name == "phone-notify.json" and not config.get("hooks"):
            file.unlink()
        else:
            write_json(file, config)
        changed.append(file)
    return changed


def _read_rpc(proc: subprocess.Popen, wanted: int, timeout: float) -> dict | None:
    assert proc.stdout is not None
    deadline = time.time() + timeout
    while time.time() < deadline:
        wait = max(0.1, deadline - time.time())
        ready, _, _ = select.select([proc.stdout], [], [], wait)
        if proc.poll() is not None and not ready:
            return None
        if not ready:
            continue
        line = proc.stdout.readline()
        if not line:
            return None
        try:
            msg = json.loads(line.decode())
        except json.JSONDecodeError:
            continue
        if msg.get("id") == wanted:
            return msg
    return None


def _iter_hooks(result: dict):
    data = result.get("data")
    if isinstance(data, dict):
        data = data.get("data") or data.get("hooks")
    if not isinstance(data, list):
        return
    for entry in data:
        if not isinstance(entry, dict):
            continue
        hooks = entry.get("hooks")
        if isinstance(hooks, list):
            for hook in hooks:
                if isinstance(hook, dict):
                    yield hook
        elif "command" in entry or "key" in entry:
            yield entry


def trust_codex(home: Path) -> str:
    codex = os.environ.get("CODEX_BIN") or "codex"
    hooks_file = home / ".codex" / "hooks.json"
    try:
        proc = subprocess.Popen(
            [codex, "app-server", "--listen", "stdio://"],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            start_new_session=True,
        )
    except OSError as exc:
        return f"Codex 未能自动信任：{exc}"
    assert proc.stdin is not None
    err: list[bytes] = []

    def drain() -> None:
        assert proc.stderr is not None
        err.append(proc.stderr.read())

    threading.Thread(target=drain, daemon=True).start()

    def send(obj: dict) -> None:
        proc.stdin.write(json.dumps(obj).encode() + b"\n")
        proc.stdin.flush()

    def stop() -> None:
        if proc.poll() is None:
            os.killpg(proc.pid, signal.SIGTERM)
            try:
                proc.wait(timeout=2)
            except subprocess.TimeoutExpired:
                os.killpg(proc.pid, signal.SIGKILL)
                proc.wait(timeout=2)

    try:
        send(
            {
                "method": "initialize",
                "id": 1,
                "params": {
                    "clientInfo": {
                        "name": "phone-agent-notify",
                        "title": "phone",
                        "version": "1",
                    },
                    "capabilities": {"experimentalApi": True},
                },
            }
        )
        init = _read_rpc(proc, 1, 30)
        if not init or "error" in init:
            detail = ""
            if init and isinstance(init.get("error"), dict):
                detail = str(init["error"].get("message") or "")
            return f"Codex 未能自动信任：{detail or 'initialize 没有返回'}"
        send({"method": "initialized", "params": {}})
        send({"method": "hooks/list", "id": 2, "params": {"cwds": [str(home)]}})
        listed = _read_rpc(proc, 2, 30)
        if not listed or "error" in listed:
            detail = ""
            if listed and isinstance(listed.get("error"), dict):
                detail = str(listed["error"].get("message") or "")
            return f"Codex 未能自动信任：{detail or 'hooks/list 没有返回'}"
        result = listed.get("result")
        if not isinstance(result, dict):
            return "Codex 未能自动信任：hooks/list 的结果不是对象"
        updates = {}
        seen = 0
        for hook in _iter_hooks(result):
            command = str(hook.get("command") or "")
            source = str(hook.get("sourcePath") or "")
            if not is_our_command(command):
                continue
            if source and Path(source).resolve() != hooks_file.resolve():
                continue
            seen += 1
            key = hook.get("key")
            current = hook.get("currentHash")
            if isinstance(key, str) and isinstance(current, str) and current:
                updates[key] = {"trusted_hash": current}
        if seen == 0:
            return "Codex 未能自动信任：hooks/list 里没有 agent-notify-hook.sh。请在 Codex 里打开 /hooks 信任它。"
        if not updates:
            return "Codex 未能自动信任：钩子没有 currentHash。"
        send(
            {
                "method": "config/batchWrite",
                "id": 3,
                "params": {
                    "edits": [
                        {
                            "keyPath": "hooks.state",
                            "mergeStrategy": "upsert",
                            "value": updates,
                        }
                    ],
                    "reloadUserConfig": True,
                },
            }
        )
        wrote = _read_rpc(proc, 3, 30)
        if not wrote or "error" in wrote:
            detail = ""
            if wrote and isinstance(wrote.get("error"), dict):
                detail = str(wrote["error"].get("message") or "")
            return f"Codex 未能自动信任：{detail or 'config/batchWrite 没有返回'}"
        return f"Codex 已信任 {len(updates)} 条 hook。"
    finally:
        try:
            proc.stdin.close()
        except OSError:
            pass
        stop()


def main(argv: list[str] | None = None) -> int:
    args = list(sys.argv[1:] if argv is None else argv)
    removing = "--remove" in args
    if any(arg not in {"--remove"} for arg in args):
        print("不认识的参数。", file=sys.stderr)
        return 1
    home = Path.home()
    script = hook_command("grok").split()[0]
    if not removing and not Path(script).is_file():
        print(f"找不到 {script}", file=sys.stderr)
        return 1
    if removing:
        changed = remove(home)
        print("已卸下")
        for file in changed:
            print(f"  {file}")
        print("已经打开的会话要重开一次。")
        return 0
    written = install(home)
    print("已安装")
    for file in written:
        print(f"  {file}")
    print("命令")
    for tool in ("claude", "codex", "grok"):
        print(f"  {hook_command(tool)}")
    print(trust_codex(home))
    print("已经打开的 Codex、Claude、Grok 会话要重开一次。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
