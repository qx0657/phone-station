#!/usr/bin/env python3
"""Byte-oriented remote PTY client for Terminal.app and other local terminals."""
from __future__ import annotations

import argparse
import fcntl
import json
import os
from pathlib import Path
import re
import select
import shlex
import signal
import struct
import sys
import tempfile
import termios
import time
import tty
import uuid

from remote_ops import Client, RemoteError, DeviceMismatch, check_permissions

TOOLS = {"station_terminal_open", "station_terminal_read", "station_terminal_input",
         "station_terminal_resize", "station_terminal_close"}
ID = re.compile(r"[0-9a-f]{32}\Z")


class TerminalExit(Exception):
    pass


class Receipt:
    def __init__(self, session_id: str, root: Path | None = None):
        if not ID.fullmatch(session_id):
            raise RemoteError("会话编号需要 32 位小写十六进制。")
        self.id = session_id
        self.root = root or Path.home() / ".phonestation/terminals"
        self.root.mkdir(mode=0o700, parents=True, exist_ok=True)
        os.chmod(self.root, 0o700)
        self.path = self.root / (session_id + ".json")
        self.lock = os.open(self.root / (session_id + ".lock"), os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
        try:
            fcntl.flock(self.lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError as error:
            os.close(self.lock)
            raise RemoteError("这个会话已经在另一个终端中打开。") from error
        self.value = {"sessionId": session_id, "offset": 0, "nextInputSequence": 0,
                      "pendingSequence": None, "state": "creating", "createdAt": int(time.time())}

    def load(self):
        try:
            fd = os.open(self.path, os.O_RDONLY | os.O_NOFOLLOW)
            with os.fdopen(fd) as stream:
                value = json.load(stream)
            if value["sessionId"] != self.id:
                raise ValueError()
            for key in ("offset", "nextInputSequence"):
                if type(value[key]) is not int or value[key] < 0:
                    raise ValueError()
            pending = value["pendingSequence"]
            if pending is not None and (type(pending) is not int or pending != value["nextInputSequence"]):
                raise ValueError()
            self.value = value
        except (OSError, ValueError, KeyError, TypeError) as error:
            raise RemoteError("原会话记录不可用；不会重新创建或重发输入。") from error

    def save(self):
        fd, temporary = tempfile.mkstemp(prefix=".terminal-", dir=self.root)
        try:
            with os.fdopen(fd, "w") as stream:
                json.dump(self.value, stream, ensure_ascii=False)
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temporary, self.path)
            directory = os.open(self.root, os.O_RDONLY)
            try:
                os.fsync(directory)
            finally:
                os.close(directory)
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)

    def unlock(self):
        os.close(self.lock)


def initialize(client: Client):
    client.rpc_timeout = 8
    client.rpc("initialize", {"protocolVersion": "2025-03-26", "capabilities": {},
                              "clientInfo": {"name": "phone-remote-terminal", "version": "1"}})
    names = {item["name"] for item in client.rpc("tools/list", {}).get("tools", [])}
    model = client.tool("station_controls_status")
    if model.get("model", "").replace("_", "-") != "PGT-AN20" or model.get("verified") is not True:
        raise DeviceMismatch("当前手机型号尚未验证，停止终端操作。")
    check_permissions(model, ["shell"])
    if not TOOLS <= names:
        raise RemoteError("请先更新手机工位到 61 或更新版本，以支持远程交互终端。")
    state = client.tool("station_shell_status")
    if not state.get("available"):
        raise RemoteError(state.get("reason") or "Shizuku 尚不可用。")


def size(fd: int) -> tuple[int, int]:
    rows, columns, _, _ = struct.unpack("HHHH", fcntl.ioctl(fd, termios.TIOCGWINSZ, b"\0" * 8))
    return max(20, min(500, columns or 80)), max(5, min(200, rows or 24))


def acknowledge(receipt: Receipt, result: dict) -> bool:
    if result.get("sessionId") != receipt.id:
        raise RemoteError("终端应答编号不匹配，保留原编号，不重发。")
    if result.get("state") not in {"running", "exited", "closed", "expired", "input_unknown"}:
        return False
    next_sequence = result.get("nextInputSequence")
    if type(next_sequence) is not int:
        raise RemoteError("终端输入回执无效。")
    current = receipt.value["nextInputSequence"]
    pending = receipt.value["pendingSequence"]
    if pending is not None and next_sequence == pending + 1:
        receipt.value["nextInputSequence"] = next_sequence
        receipt.value["pendingSequence"] = None
        receipt.save()
    elif next_sequence != current:
        raise RemoteError("输入序号与本机记录不一致，停止输入，请关闭原会话。")
    return receipt.value["pendingSequence"] is None


def send_input(client: Client, receipt: Receipt, data: bytes):
    if receipt.value["pendingSequence"] is not None:
        raise RemoteError("上一段输入尚未确认，只能查询原会话。")
    sequence = receipt.value["nextInputSequence"]
    receipt.value["pendingSequence"] = sequence
    receipt.save()  # Before the only submission; raw input/commands never go to disk.
    result = client.tool("station_terminal_input", {"sessionId": receipt.id,
                         "sequence": sequence, "hex": data.hex()})
    acknowledge(receipt, result)


def read_output(client: Client, receipt: Receipt) -> tuple[dict, bytes]:
    result = client.tool("station_terminal_read", {"sessionId": receipt.id, "offset": receipt.value["offset"]})
    if result.get("sessionId") != receipt.id:
        raise RemoteError("终端应答编号不匹配。")
    if "hex" not in result:
        return result, b""
    try:
        data = bytes.fromhex(result["hex"])
        offset, next_offset = result["offset"], result["nextOffset"]
        dropped = result["droppedBytes"]
        if (len(data) > 32768 or any(type(v) is not int for v in (offset, next_offset, dropped))
                or dropped < 0 or offset != receipt.value["offset"] + dropped or next_offset != offset + len(data)):
            raise ValueError()
    except (ValueError, KeyError, TypeError) as error:
        raise RemoteError("终端输出游标无效，保留原会话。") from error
    acknowledge(receipt, result)
    return result, data


def message(text: str):
    sys.stderr.write("\r\n" + text + "\r\n")
    sys.stderr.flush()


def interact(client: Client, receipt: Receipt, resume: bool = False) -> int:
    fd = sys.stdin.fileno()
    if not os.isatty(fd) or not os.isatty(sys.stdout.fileno()):
        raise RemoteError("交互终端需要 TTY；请在系统「终端」中运行 terminal.sh。")
    columns, rows = size(fd)
    if resume:
        receipt.load()
    else:
        if receipt.path.exists():
            raise RemoteError("会话编号已使用，不会重新创建。")
        receipt.save()
        try:
            result = client.tool("station_terminal_open", {"sessionId": receipt.id, "columns": columns, "rows": rows})
            if result.get("jobId") != receipt.id or result.get("kind") != "terminal":
                raise RemoteError("终端创建回执不匹配。")
        except RemoteError:
            message("创建应答未确认，正在查询原会话。")
    message(f"远程手机 shell · 会话 {receipt.id} · Ctrl-] 关闭")
    message(f"恢复原会话：{shlex.quote(str(Path(__file__).resolve().parent.parent / 'scripts/terminal.sh'))} --resume {receipt.id}")
    previous = termios.tcgetattr(fd)
    old_signals = {}
    resized = [True]
    def stop(_sig, _frame):
        raise TerminalExit()
    def resize(_sig, _frame):
        resized[0] = True
    for sig in (signal.SIGHUP, signal.SIGTERM):
        old_signals[sig] = signal.signal(sig, stop)
    old_signals[signal.SIGWINCH] = signal.signal(signal.SIGWINCH, resize)
    ending = False
    warned = False
    input_warning = False
    try:
        tty.setraw(fd)
        while True:
            try:
                result, data = read_output(client, receipt)
                state = result.get("state")
                if state == "starting":
                    time.sleep(0.2)
                    continue
                if state not in {"running", "exited", "closed", "expired", "input_unknown"}:
                    ending = True
                    receipt.value["state"] = state or "lost"; receipt.save()
                    message(result.get("reason") or "原会话已结束，不会重新创建。")
                    return 1
                warned = False
                if result.get("droppedBytes", 0):
                    message(f"输出超过缓冲区，遗漏 {result['droppedBytes']} 字节。")
                if data:
                    sys.stdout.buffer.write(data); sys.stdout.buffer.flush()
                    receipt.value["offset"] = result["nextOffset"]; receipt.save()
                pending = receipt.value["pendingSequence"] is not None
                if pending and not input_warning:
                    message("上一段输入未确认，已暂停输入，只查询原序号；Ctrl-] 关闭。")
                input_warning = pending
                if state != "running" and receipt.value["offset"] >= result.get("endOffset", 0):
                    ending = True
                    receipt.value["state"] = state; receipt.save()
                    message(result.get("reason") or f"会话已结束，退出码 {result.get('exitCode')}。")
                    return int(result.get("exitCode") or (0 if state in {"exited", "closed"} else 1))
                if resized[0] and state == "running":
                    resized[0] = False
                    columns, rows = size(fd)
                    try:
                        client.tool("station_terminal_resize", {"sessionId": receipt.id, "columns": columns, "rows": rows})
                    except RemoteError:
                        message("窗口尺寸未确认。")
                readable, _, _ = select.select([fd], [], [], 0 if data else 0.15)
                if readable:
                    entered = os.read(fd, 4096)
                    if not entered or b"\x1d" in entered:
                        raise TerminalExit()
                    if not pending and state == "running":
                        try:
                            send_input(client, receipt, entered)
                        except RemoteError:
                            message("输入应答未确认，正在查询原序号，不重发。")
            except RemoteError:
                if not warned:
                    message("连接中断，正在查询原会话；输入暂停。Ctrl-] 关闭，断线 60 秒后手机自动清理。")
                    warned = True
                readable, _, _ = select.select([fd], [], [], 0.5)
                if readable and b"\x1d" in os.read(fd, 4096):
                    raise TerminalExit()
    except (TerminalExit, KeyboardInterrupt):
        return 0
    finally:
        try:
            termios.tcsetattr(fd, termios.TCSADRAIN, previous)
        except termios.error:
            pass
        for sig, handler in old_signals.items():
            signal.signal(sig, handler)
        if not ending:
            close_session(client, receipt)


def close_session(client: Client, receipt: Receipt):
    try:
        result = client.tool("station_terminal_close", {"sessionId": receipt.id})
        if result.get("sessionId") != receipt.id or result.get("state") not in {"closed", "exited", "expired", "lost", "input_unknown"}:
            raise RemoteError("关闭回执未确认。")
        receipt.value["state"] = result["state"]; receipt.save()
        message("会话已关闭。")
        return True
    except RemoteError:
        message(f"关闭未确认，保留会话 {receipt.id}；停止查询 60 秒后手机自动清理。")
        return False


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="经已有中继打开手机交互式 shell；不使用本地 adb。Ctrl-] 关闭。")
    group = parser.add_mutually_exclusive_group()
    group.add_argument("--resume", metavar="SESSION_ID", help="只恢复原会话，不重新创建或重发输入")
    group.add_argument("--close", metavar="SESSION_ID", help="关闭原会话并清理进程")
    args = parser.parse_args(argv)
    receipt = None
    try:
        client = Client()
        initialize(client)
        receipt = Receipt(args.resume or args.close or uuid.uuid4().hex)
        if args.close:
            receipt.load(); return 0 if close_session(client, receipt) else 1
        return interact(client, receipt, bool(args.resume))
    except (RemoteError, OSError) as error:
        message(str(error))
        return 1
    finally:
        if receipt is not None:
            receipt.unlock()


if __name__ == "__main__":
    raise SystemExit(main())
