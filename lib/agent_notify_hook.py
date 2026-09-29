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
BODY_LIMIT = 80
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


def tool_leaf(name: str) -> str:
    return name.replace(":", ".").split(".")[-1].split("__")[-1]


def one_line(value: object, limit: int = BODY_LIMIT) -> str:
    if not isinstance(value, str):
        return ""
    line = ""
    for part in value.splitlines():
        part = " ".join(part.split()).strip()
        if not part or part.startswith("```"):
            continue
        line = part
        break
    if not line or has_title_mark(line):
        return ""
    if len(line) <= limit:
        return line
    return line[: limit - 1] + "…"


CLIENTS = ("Grok", "Claude", "Codex")


def client_from_args(argv: list[str]) -> str:
    for index, arg in enumerate(argv):
        if arg != "--client":
            continue
        if index + 1 >= len(argv):
            return ""
        name = argv[index + 1]
        if name in CLIENTS:
            return name
        return ""
    return ""


def client_from_path(value: object) -> str:
    if not isinstance(value, str) or not value:
        return ""
    norm = value.replace("\\", "/")
    if "/.grok/sessions/" in norm:
        return "Grok"
    if "/.codex/" in norm:
        return "Codex"
    if "/.claude/" in norm:
        return "Claude"
    return ""


def client_name(data: dict, argv: list[str]) -> str:
    """谁触发的。路径优先，避免 Grok 执行 Claude 配置时被标成 Claude。"""
    found = client_from_path(first(data, "transcriptPath", "transcript_path"))
    if found:
        return found
    found = client_from_path(
        first(data, "agent_transcript_path", "agentTranscriptPath")
    )
    if found:
        return found
    if session_dir(data):
        return "Grok"
    if os.environ.get("GROK_HOOK_EVENT") or os.environ.get("GROK_SESSION_ID"):
        return "Grok"
    found = client_from_args(argv)
    if found:
        return found
    if os.environ.get("CLAUDE_PROJECT_DIR"):
        return "Claude"
    return ""


def project_name(data: dict) -> str:
    cwd = first(data, "cwd", "workspaceRoot", "workspace_root")
    if not isinstance(cwd, str):
        return ""
    name = os.path.basename(cwd.rstrip("/"))
    if not name or name == "/":
        return ""
    return name


def tool_fields(data: dict) -> dict:
    raw = first(data, "tool_input", "toolInput")
    if isinstance(raw, str):
        try:
            raw = json.loads(raw)
        except json.JSONDecodeError:
            return {}
    if isinstance(raw, dict):
        return raw
    return {}


def assistant_line(data: dict) -> str:
    return one_line(first(data, "last_assistant_message", "lastAssistantMessage"))


def session_label(data: dict) -> str:
    directory = session_dir(data)
    if directory is None:
        return ""
    summary = read_json(os.path.join(directory, "summary.json"))
    if summary is None:
        return ""
    for key in ("last_turn_summary", "generated_title", "session_summary"):
        line = one_line(summary.get(key))
        if line:
            return line
    return ""


def permission_detail(data: dict) -> str:
    tool = tool_leaf(str(first(data, "tool_name", "toolName") or ""))
    incoming = tool_fields(data)
    extra = ""
    for key in ("command", "cmd", "file_path", "filePath", "url", "description"):
        extra = one_line(incoming.get(key), 60)
        if extra:
            break
    if not extra:
        extra = one_line(first(data, "message"), 60)
    if tool and extra:
        return f"{tool}：{extra}"
    return tool or extra


def question_detail(data: dict) -> str:
    incoming = tool_fields(data)
    for key in ("question", "prompt", "message"):
        line = one_line(incoming.get(key))
        if line:
            return line
    questions = incoming.get("questions")
    if isinstance(questions, list):
        for item in questions:
            if isinstance(item, str):
                line = one_line(item)
            elif isinstance(item, dict):
                line = one_line(item.get("question") or item.get("prompt") or item.get("header"))
            else:
                line = ""
            if line:
                return line
    return one_line(first(data, "message"))


def url_detail(data: dict) -> str:
    incoming = tool_fields(data)
    for value in (first(data, "url"), incoming.get("url"), first(data, "message")):
        line = one_line(value)
        if line:
            return line
    return ""


def shade_kind(reason: str) -> str:
    leaf = tool_leaf(reason)
    if reason == "stop" or leaf == "stop":
        return "stop"
    if reason == "sessionend" or leaf == "sessionend":
        return "sessionend"
    if reason in {"permissionrequest", "permission_prompt"} or leaf in {
        "permissionrequest",
        "permission_prompt",
    }:
        return "permission"
    if reason == "elicitation_url_dialog" or leaf == "elicitation_url_dialog":
        return "url"
    if reason in ASK_TOOLS or leaf in ASK_TOOLS:
        return "ask"
    if reason in {"elicitation", "elicitation_dialog", "agent_needs_input"} or leaf in {
        "elicitation",
        "elicitation_dialog",
        "agent_needs_input",
    }:
        return "answer"
    return "plain"


def with_context(data: dict, detail: str, client: str) -> str:
    parts = []
    if client:
        parts.append(client)
    project = project_name(data)
    if project:
        parts.append(project)
    if detail:
        parts.append(detail)
    return " · ".join(parts)


def shade_text(reason: str, data: dict | None = None, client: str = "") -> tuple[str, str]:
    """下拉通知的标题和内容。同一条通知会被后一次调用盖掉。"""
    payload = data or {}
    kind = shade_kind(reason)
    titles = {
        "stop": "这一轮结束了",
        "sessionend": "会话结束了",
        "permission": "需要确认权限",
        "url": "需要打开一个链接",
        "ask": "有一个问题要回答",
        "answer": "需要你的回答",
    }
    title = titles.get(kind, "手机工位")
    if kind == "stop":
        detail = assistant_line(payload)
    elif kind == "sessionend":
        detail = session_label(payload)
    elif kind == "permission":
        detail = permission_detail(payload)
    elif kind == "url":
        detail = url_detail(payload)
    elif kind in {"ask", "answer"}:
        detail = question_detail(payload)
    else:
        detail = ""
    body = one_line(with_context(payload, detail, client)) or with_context(
        payload, detail, client
    )
    if not body:
        body = "有一条提醒" if title == "手机工位" else title
    return title, body


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
        if ok:
            title, text = shade_text(reason, data, client_name(data, argv))
            print(title)
            print(text)
        return 0
    if not ok or not claim():
        return 0
    directory = state_dir()
    os.makedirs(directory, exist_ok=True)
    title, text = shade_text(reason, data, client_name(data, argv))
    spawn(
        [notify_bin(), "--title", title, "--text", text],
        os.path.join(directory, "last.log"),
        reason,
    )
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
