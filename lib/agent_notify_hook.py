#!/usr/bin/env python3
"""Codex、Claude、Grok 的 hook 入口。判断要不要响，然后马上返回。

Stop 会把标准输出当成控制指令，退出码 2 会让这一轮继续。
会话结束的预算也很短。铃声放到脱离的进程里播，这里自己不打印。
"""

from __future__ import annotations

import json
import os
import resource
import sys
import time
from urllib.parse import quote

DEBOUNCE_S = 8.0
ASK_TOOLS = {
    "AskUserQuestion",
    "ask_user_question",
    "request_user_input",
}
NOTIFY_TYPES = {
    "permission_prompt",
    "agent_needs_input",
    "elicitation_dialog",
    "elicitation_url_dialog",
}
# Grok 起标题的提示。中文这句是当前无头会话里的原文，英文两句在本机 grok 里。
TITLE_MARKS = (
    "为下面这条用户消息起一个简短会话标题",
    "Just generate the session_title",
    "Generate a session title for the conversation",
)
TRANSCRIPT_SCAN_BYTES = 1_500_000


def state_dir() -> str:
    override = os.environ.get("PHONE_AGENT_NOTIFY_STATE")
    if override:
        return override
    return os.path.join(os.path.expanduser("~/.cache"), "phone-agent-notify")


def notify_bin() -> str:
    override = os.environ.get("PHONE_AGENT_NOTIFY_BIN")
    if override:
        return override
    here = os.path.dirname(os.path.realpath(__file__))
    return os.path.join(os.path.dirname(here), "scripts", "notify.sh")


def debounce_seconds() -> float:
    raw = os.environ.get("PHONE_AGENT_NOTIFY_DEBOUNCE")
    if not raw:
        return DEBOUNCE_S
    try:
        return float(raw)
    except ValueError:
        return DEBOUNCE_S


def first(data: dict, *keys):
    for key in keys:
        if key not in data:
            continue
        value = data[key]
        if value is None or value == "" or value == [] or value == {}:
            continue
        return value
    return None


def event_name(data: dict) -> str:
    raw = first(data, "hook_event_name", "hookEventName") or ""
    return str(raw).replace("_", "").replace("-", "").lower()


def is_ask_tool(name: str) -> bool:
    leaf = name.replace(":", ".").split(".")[-1].split("__")[-1]
    return leaf in ASK_TOOLS


def grok_home() -> str:
    return os.environ.get("GROK_HOME") or os.path.join(os.path.expanduser("~"), ".grok")


def read_json(path: str) -> dict | None:
    try:
        with open(path, encoding="utf-8") as handle:
            data = json.load(handle)
    except (OSError, UnicodeError, json.JSONDecodeError):
        return None
    if not isinstance(data, dict):
        return None
    return data


def session_dir(data: dict) -> str | None:
    transcript = first(data, "transcriptPath", "transcript_path")
    if isinstance(transcript, str) and transcript:
        parent = os.path.dirname(transcript)
        if os.path.isfile(os.path.join(parent, "summary.json")):
            return parent
    session_id = first(data, "sessionId", "session_id") or os.environ.get("GROK_SESSION_ID")
    cwd = first(data, "cwd")
    if not session_id or not cwd:
        return None
    directory = os.path.join(
        grok_home(),
        "sessions",
        quote(str(cwd), safe=""),
        str(session_id),
    )
    if os.path.isfile(os.path.join(directory, "summary.json")):
        return directory
    return None


def has_title_mark(text: str) -> bool:
    return any(mark in text for mark in TITLE_MARKS)


def transcript_has_title(directory: str) -> bool:
    for name in ("chat_history.jsonl", "updates.jsonl"):
        path = os.path.join(directory, name)
        try:
            with open(path, encoding="utf-8", errors="replace") as handle:
                read = 0
                for line in handle:
                    read += len(line)
                    if has_title_mark(line):
                        return True
                    if read >= TRANSCRIPT_SCAN_BYTES:
                        break
        except OSError:
            continue
    return False


def side_session(data: dict) -> str | None:
    """主会话之外、不该响的 Stop / SessionEnd。找不到记录就当主会话。"""
    try:
        directory = session_dir(data)
        if directory is None:
            return None
        summary = read_json(os.path.join(directory, "summary.json"))
        if summary is None:
            return None
        kind = str(summary.get("session_kind") or "").lower()
        if kind.startswith("subagent"):
            return "subagent"
        if kind != "headless":
            return None
        info = summary.get("info")
        info_cwd = ""
        if isinstance(info, dict):
            info_cwd = str(info.get("cwd") or "")
        payload_cwd = str(first(data, "cwd") or "")
        if info_cwd == "/" or payload_cwd == "/":
            return "title"
        for key in ("session_summary", "generated_title"):
            value = summary.get(key)
            if isinstance(value, str) and has_title_mark(value):
                return "title"
        if transcript_has_title(directory):
            return "title"
        return None
    except Exception:
        return None


def decide(data: dict) -> tuple[bool, str]:
    event = event_name(data)
    subagent = first(data, "subagentType", "subagent_type")
    if event in {"subagentstop", "subagentend"}:
        return False, "subagent"
    if event in {"stop", "sessionend"}:
        if subagent:
            return False, "subagent"
        side = side_session(data)
        if side:
            return False, side
        return True, event
    if event in {"permissionrequest", "elicitation"}:
        return True, event
    if event == "notification":
        kind = str(first(data, "notification_type", "notificationType") or "")
        if kind in NOTIFY_TYPES:
            return True, kind
        return False, "notification"
    if event == "pretooluse":
        tool = str(first(data, "tool_name", "toolName") or "")
        if is_ask_tool(tool):
            return True, tool
        return False, "tool"
    if event == "":
        return True, "unspecified"
    return False, "event"


def load_payload(raw: str) -> dict | None:
    text = raw.strip()
    if not text:
        return {}
    try:
        data = json.loads(text)
    except json.JSONDecodeError:
        return None
    if not isinstance(data, dict):
        return None
    return data


def claim() -> bool:
    directory = state_dir()
    os.makedirs(directory, exist_ok=True)
    lock_path = os.path.join(directory, "lock")
    stamp_path = os.path.join(directory, "last")
    import fcntl

    with open(lock_path, "a", encoding="utf-8") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        moment = time.time()
        try:
            with open(stamp_path, encoding="utf-8") as stamp:
                last = float(stamp.read().strip())
        except (OSError, ValueError):
            last = 0.0
        if moment - last < debounce_seconds():
            return False
        with open(stamp_path, "w", encoding="utf-8") as stamp:
            stamp.write(str(moment))
        return True


def _close_extra_fds() -> None:
    soft, _hard = resource.getrlimit(resource.RLIMIT_NOFILE)
    limit = soft if 0 < soft < 1024 else 256
    for fd in range(3, limit):
        try:
            os.close(fd)
        except OSError:
            pass


def spawn(argv: list[str], log_path: str, reason: str) -> None:
    pid = os.fork()
    if pid > 0:
        os.waitpid(pid, 0)
        return
    try:
        os.setsid()
        os.chdir("/")
        log_fd = os.open(log_path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o644)
        null_fd = os.open(os.devnull, os.O_RDONLY)
        os.dup2(null_fd, 0)
        os.dup2(log_fd, 1)
        os.dup2(log_fd, 2)
        if log_fd > 2:
            os.close(log_fd)
        if null_fd > 2:
            os.close(null_fd)
        _close_extra_fds()
        if os.fork() > 0:
            os._exit(0)
        os.write(1, f"{reason}\n".encode())
        os.execv(argv[0], argv)
    except OSError:
        os._exit(0)
    os._exit(0)


def run(argv: list[str]) -> int:
    dry = "--dry-run" in argv
    raw = "" if sys.stdin.isatty() else sys.stdin.read()
    data = load_payload(raw)
    if data is None:
        if dry:
            print("skip invalid")
        return 0
    ok, reason = decide(data)
    if dry:
        print(("ring" if ok else "skip") + " " + reason)
        return 0
    if not ok or not claim():
        return 0
    directory = state_dir()
    os.makedirs(directory, exist_ok=True)
    spawn([notify_bin()], os.path.join(directory, "last.log"), reason)
    return 0


def main(argv: list[str] | None = None) -> int:
    args = list(sys.argv[1:] if argv is None else argv)
    try:
        return run(args)
    except Exception as exc:
        if "--dry-run" in args:
            print(f"error {exc}", file=sys.stderr)
            return 1
        return 0


if __name__ == "__main__":
    sys.exit(main())
