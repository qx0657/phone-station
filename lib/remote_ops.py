#!/usr/bin/env python3
"""Remote-only shell and APK deployment over the existing phone MCP tools."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import struct
import subprocess
import sys
import time
import uuid
import zipfile

ROOT = Path(__file__).resolve().parent.parent
PACKAGE = "dev.phonestation.adbkeep"
CHUNK_BYTES = 512 * 1024
JOB_ROOT = "/data/local/tmp/phone-station-install"
UPLOAD_ROOT = "/storage/emulated/0/Download/手机工位/inbox"
RECOVERY_EXTRA = "phone_station_install_job"


class RemoteError(Exception):
    pass


class DeviceMismatch(RemoteError):
    pass


def gateway_binary() -> Path:
    for candidate in (ROOT / "lib/phone-relay-gateway", ROOT / "build/.phone-station/phone-relay-gateway"):
        if candidate.is_file() and os.access(candidate, os.X_OK):
            return candidate
    raise RemoteError("找不到远程网关。先运行 ./scripts/build-mac-app.sh。")


class Client:
    def __init__(self):
        self.binary = gateway_binary()
        self.rid = 0
        self.rpc_timeout = 245

    def rpc(self, method: str, params: dict) -> dict:
        self.rid += 1
        body = json.dumps({"jsonrpc": "2.0", "id": self.rid, "method": method, "params": params}, ensure_ascii=False)
        try:
            reply = subprocess.run([str(self.binary), "remote-call"], input=body,
                                   text=True, capture_output=True, timeout=self.rpc_timeout)
        except (OSError, subprocess.TimeoutExpired) as error:
            raise RemoteError("远程请求未确认；请核实结果，不要自动重做。") from error
        if reply.returncode:
            raise RemoteError(reply.stderr.strip() or "远程请求失败。")
        try:
            value = json.loads(reply.stdout)
            if value.get("id") != self.rid or "error" in value:
                raise ValueError()
            return value["result"]
        except (ValueError, KeyError, TypeError) as error:
            raise RemoteError("远程响应无效；请核实结果。") from error

    def tool(self, name: str, args: dict | None = None) -> dict:
        result = self.rpc("tools/call", {"name": name, "arguments": args or {}})
        try:
            payload = json.loads(next(item["text"] for item in result["content"] if item.get("type") == "text"))
        except (ValueError, KeyError, TypeError, StopIteration) as error:
            raise RemoteError("手机工具返回无效结果。") from error
        if result.get("isError") or "error" in payload:
            raise RemoteError(payload.get("error", "手机工具调用失败。"))
        return payload

    def initialize(self, install: bool = False):
        self.rpc("initialize", {"protocolVersion": "2025-03-26", "capabilities": {},
                                "clientInfo": {"name": "phone-remote-ops", "version": "1"}})
        names = {item["name"] for item in self.rpc("tools/list", {}).get("tools", [])}
        required = {"station_shell_status", "station_shell_exec"}
        if install:
            required |= {"station_file_access_policy", "station_file_create_directory", "station_file_write_bytes", "station_file_append_bytes", "station_file_write_text"}
        if not required <= names:
            raise RemoteError("手机工位版本缺少远程工具，请先更新到支持 Shizuku shell 的版本。")
        state = self.tool("station_shell_status")
        if not state.get("available"):
            raise RemoteError(state.get("reason", "Shizuku 尚不可用。"))
        model = self.shell("getprop ro.product.model")["stdout"].strip()
        if model.replace("_", "-") != "PGT-AN20":
            raise DeviceMismatch(f"当前机型 {model} 尚未验证，停止设备操作。")

    def shell(self, command: str, timeout_ms: int = 10000, output_bytes: int = 32768) -> dict:
        return self.tool("station_shell_exec", {"command": command, "timeoutMs": timeout_ms,
                                                "maxOutputBytes": output_bytes})

    def checked_shell(self, command: str, timeout_ms: int = 10000) -> str:
        result = self.shell(command, timeout_ms)
        if result.get("timedOut") or result.get("exitCode") != 0 or result.get("outputTruncated") or result.get("outputIncomplete"):
            raise RemoteError((result.get("stderr") or result.get("stdout") or "shell 未完整完成；请核实结果。").strip())
        return result["stdout"]


def manifest_info(apk: Path) -> dict:
    """Read Android's binary manifest using only the Python standard library."""
    try:
        with zipfile.ZipFile(apk) as archive:
            if archive.getinfo("AndroidManifest.xml").file_size > 4 * 1024 * 1024:
                raise ValueError()
            data = archive.read("AndroidManifest.xml")
        kind, header, size = struct.unpack_from("<HHI", data)
        if kind != 3 or header != 8 or size != len(data):
            raise ValueError()
        strings = []
        pos = header
        while pos < size:
            kind, head, length = struct.unpack_from("<HHI", data, pos)
            if head < 8 or length < head or pos + length > size:
                raise ValueError()
            if kind == 1:
                count, _, flags, start = struct.unpack_from("<IIII", data, pos + 8)
                if head < 28 or count > length // 4:
                    raise ValueError()
                utf8 = bool(flags & 0x100)

                def read_length(offset):
                    if utf8:
                        first = data[offset]
                        return (((first & 0x7f) << 8) | data[offset + 1], offset + 2) if first & 0x80 else (first, offset + 1)
                    first = struct.unpack_from("<H", data, offset)[0]
                    return (((first & 0x7fff) << 16) | struct.unpack_from("<H", data, offset + 2)[0], offset + 4) if first & 0x8000 else (first, offset + 2)

                for index in range(count):
                    offset = pos + start + struct.unpack_from("<I", data, pos + head + index * 4)[0]
                    if utf8:
                        _, offset = read_length(offset)
                    chars, offset = read_length(offset)
                    end = offset + chars * (1 if utf8 else 2)
                    if offset < pos or end > pos + length:
                        raise ValueError()
                    strings.append(data[offset:end].decode("utf-8" if utf8 else "utf-16le"))
            elif kind == 0x102:
                name = struct.unpack_from("<I", data, pos + 20)[0]
                if strings[name] == "manifest":
                    attr_start, attr_size, count = struct.unpack_from("<HHH", data, pos + 24)
                    if attr_size < 20 or pos + 16 + attr_start + attr_size * count > pos + length:
                        raise ValueError()
                    attrs = {}
                    for index in range(count):
                        offset = pos + 16 + attr_start + index * attr_size
                        namespace, name, raw, _, _, typ, val = struct.unpack_from("<IIIHBBI", data, offset)
                        # Only package/split are unnamespaced. Version fields use the Android namespace.
                        key = strings[name]
                        expected_ns = "http://schemas.android.com/apk/res/android" if key in {"versionCode", "versionCodeMajor", "versionName"} else None
                        ns = None if namespace == 0xffffffff else strings[namespace]
                        if ns != expected_ns:
                            continue
                        attrs[key] = strings[raw] if raw != 0xffffffff else strings[val] if typ == 3 else val
                    package = attrs["package"]
                    version = int(attrs["versionCode"]) | (int(attrs.get("versionCodeMajor", 0)) << 32)
                    if not re.fullmatch(r"[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+", package) or version < 0:
                        raise ValueError()
                    return {"package": package, "versionCode": version, "versionName": str(attrs.get("versionName", "")),
                            "split": attrs.get("split", "")}
            pos += length
        raise ValueError()
    except (OSError, zipfile.BadZipFile, KeyError, ValueError, IndexError, struct.error, UnicodeError, TypeError) as error:
        raise RemoteError(f"无法读取 APK 的包名和版本：{apk.name}") from error


def apk_set(paths: list[str]) -> list[dict]:
    apks = []
    for name in paths:
        file = Path(name).expanduser().resolve()
        if file.suffix.lower() != ".apk" or not file.is_file() or file.stat().st_size <= 0:
            raise RemoteError(f"需要有效的 .apk 文件：{name}")
        info = manifest_info(file)
        with file.open("rb") as stream:
            digest = hashlib.file_digest(stream, "sha256").hexdigest() if hasattr(hashlib, "file_digest") else file_hash(stream)
        apks.append({**info, "file": file, "size": file.stat().st_size, "sha256": digest})
    if not apks or len(apks) > 100:
        raise RemoteError("一次需要 1 到 100 个 APK。")
    if len({(item["package"], item["versionCode"]) for item in apks}) != 1:
        raise RemoteError("一组 APK 必须属于同一个包名和版本。")
    splits = [item["split"] for item in apks]
    if splits.count("") != 1 or len(set(splits)) != len(splits):
        raise RemoteError("必须有且仅有一个 base APK，各 split 不能重复。")
    return sorted(apks, key=lambda item: (bool(item["split"]), item["split"]))


def file_hash(stream) -> str:
    digest = hashlib.sha256()
    for chunk in iter(lambda: stream.read(CHUNK_BYTES), b""):
        digest.update(chunk)
    return digest.hexdigest()


def upload(client: Client, apk: dict, remote: str):
    version = None
    with apk["file"].open("rb") as stream:
        for chunk in iter(lambda: stream.read(CHUNK_BYTES), b""):
            args = {"path": remote, "hex": chunk.hex()}
            if version:
                args["targetVersion"] = version
            result = client.tool("station_file_append_bytes" if version else "station_file_write_bytes", args)
            version = result["targetVersion"]


def service_probe_command() -> str:
    # Only return lifecycle facts. Never invoke the service's token-bearing dump().
    return (f"dumpsys activity services {PACKAGE} | awk "
            "' /\\* ServiceRecord\\{/ {mcp = index($0, \"/.FileMcpService \") > 0} "
            "mcp && /isForeground=true/ {found=1} END {print found+0}'")


def recovery_probe_command(job_id: str) -> str:
    # 40+ provides a dump mode with no credentials. Filter strictly even for an older/custom APK.
    return (f"timeout 3 dumpsys activity service {PACKAGE}/.FileMcpService --install-recovery {shlex.quote(job_id)} | awk "
            "'/^[[:space:]]*installRecoveryPid=/ {split($0,a,\"=\"); pid=a[2]+0} "
            "/^[[:space:]]*installRecoveryService=true$/ {service=1} "
            "/^[[:space:]]*installRecoveryActivity=true$/ {activity=1} "
            "END {printf \"%d %s %s\\n\",pid+0,service?\"true\":\"false\",activity?\"true\":\"false\"}'")


def recovery_script(open_app: bool, previous_pid: str, job_id: str) -> list[str]:
    pending = json.dumps({"state": "pending", "openApp": open_app}, separators=(",", ":"))
    lines = [f"printf '%s\\n' {shlex.quote(pending)} > recovery.json.tmp; mv recovery.json.tmp recovery.json",
             f"timeout 15 am start-foreground-service --user current -n {PACKAGE}/.FileMcpService "
             f"--es {RECOVERY_EXTRA} {shlex.quote(job_id)} > service_result 2>&1",
             "service_rc=$?", "cat service_result", "activity_rc=-1", "activity_started=false"]
    if open_app:
        lines += [f"timeout 20 am start -W --user current -a android.intent.action.MAIN "
                  f"-c android.intent.category.LAUNCHER -n {PACKAGE}/.MainActivity "
                  f"--es {RECOVERY_EXTRA} {shlex.quote(job_id)} > activity_result 2>&1",
                  "activity_rc=$?", "cat activity_result",
                  "if [ \"$activity_rc\" = 0 ] && grep -Eq '^Status: *ok$' activity_result "
                  f"&& grep -Eq '^Activity: *{PACKAGE.replace('.', '[.]')}/([.]|{PACKAGE.replace('.', '[.]')}[.])MainActivity$' activity_result; "
                  "then activity_started=true; fi"]
    lines += ["stable=0", "last_pid=", "mcp_foreground=0", "process_changed=false", "recovery_state=failed",
              "proof_pid=0", "service_seen=false", "activity_seen=false",
              "for n in $(seq 1 30); do",
              f"  pid=$(pidof {PACKAGE} | awk '{{print $1}}')",
              f"  mcp_foreground=$({service_probe_command()})",
              f"  if [ -n \"$pid\" ] && [ \"$pid\" != {shlex.quote(previous_pid)} ]; then process_changed=true; else process_changed=false; fi",
              '  if [ "$process_changed" = true ] && [ "$mcp_foreground" = 1 ]; then',
              f"    proof=$({recovery_probe_command(job_id)})",
              '    set -- $proof; proof_pid="$1"; service_seen="$2"; activity_seen="$3"',
              "  fi",
              '  if [ "$process_changed" = true ] && [ "$mcp_foreground" = 1 ] && [ "$proof_pid" = "$pid" ] '
              '&& { [ "$service_seen" = true ] || [ "$activity_seen" = true ]; }; then',
              '    if [ "$pid" = "$last_pid" ]; then stable=$((stable + 1)); else stable=1; fi',
              "  else stable=0; fi", '  last_pid="$pid"',
              '  if [ "$stable" -ge 2 ]; then recovery_state=ready; break; fi', "  sleep 1", "done"]
    if open_app:
        lines += ['[ "$activity_started" = true ] && [ "$activity_seen" = true ] || recovery_state=failed']
    # Keep request errors separate from the actual process/service observation.
    lines += ['service_accepted=false',
              "if [ \"$service_rc\" = 0 ] && ! grep -Eq '^Error:|Exception' service_result; then service_accepted=true; fi",
              '[ "$service_accepted" = true ] || [ "$activity_started" = true ] || recovery_state=failed',
              'if [ -z "$pid" ]; then pid=null; fi',
              'mcp_ready=false; [ "$mcp_foreground" = 1 ] && mcp_ready=true']
    fmt = ('{"state":"%s","openApp":%s,"serviceRequestExit":%s,"serviceAccepted":%s,'
           '"activityRequestExit":%s,"activityStarted":%s,"pid":%s,"processChanged":%s,"mcpForeground":%s,'
           '"proofPid":%s,"serviceSeen":%s,"activitySeen":%s}\\n')
    lines += [f"printf {shlex.quote(fmt)} \"$recovery_state\" {str(open_app).lower()} "
              '"$service_rc" "$service_accepted" "$activity_rc" "$activity_started" "$pid" '
              '"$process_changed" "$mcp_ready" "$proof_pid" "$service_seen" "$activity_seen" '
              '> recovery.json.tmp; mv recovery.json.tmp recovery.json']
    return lines


def installer_script(apks: list[dict], job: str, inbox: str, open_app: bool = True,
                     previous_pid: str = "") -> str:
    total = sum(item["size"] for item in apks)
    lines = ["#!/system/bin/sh", "umask 077", f"cd {shlex.quote(job)} || exit 1",
             "session=", "rc=1", "committed=0",
             "finish() {",
             '  if [ -n "$session" ] && [ "$committed" != 1 ]; then pm install-abandon "$session" >/dev/null 2>&1; fi',
             '  printf "%s\\n" "$rc" > exit_code.tmp; mv exit_code.tmp exit_code',
             f"  rm -f ./*.apk; rm -rf {shlex.quote(inbox)}", "}", "trap finish EXIT",
             "touch started", "sleep 2",  # Let the initiating MCP reply leave before replacing its own APK.
             f"pm install-create -r --user current -S {total} > create_result 2>&1", "rc=$?", "cat create_result",
             '[ "$rc" = 0 ] || exit "$rc"',
             r"session=$(sed -n 's/^Success: created install session \[\([0-9]*\)\].*/\1/p' create_result)",
             '[ -n "$session" ] || { rc=1; exit 1; }']
    for index, apk in enumerate(apks):
        lines += [f'pm install-write -S {apk["size"]} "$session" part{index}.apk {index}.apk',
                  'rc=$?; [ "$rc" = 0 ] || exit "$rc"']
    lines += ['pm install-commit "$session" > commit_result 2>&1', "rc=$?", "cat commit_result",
              '[ "$rc" = 0 ] || exit "$rc"',
              "grep -qx 'Success' commit_result || { rc=1; exit 1; }", "committed=1", "rc=0",
              'printf "0\\n" > exit_code.tmp; mv exit_code.tmp exit_code']
    if apks[0]["package"] == PACKAGE:
        lines += recovery_script(open_app, previous_pid, Path(job).name)
    lines += ["exit 0"]
    return "\n".join(lines) + "\n"


def prepare(client: Client, apks: list[dict], job_id: str, open_app: bool = True):
    inbox = f"{UPLOAD_ROOT}/install-{job_id}"
    job = f"{JOB_ROOT}/{job_id}"
    client.tool("station_file_access_policy")
    client.tool("station_file_create_directory", {"path": inbox})
    for index, apk in enumerate(apks):
        print(f"上传 {apk['file'].name}（{apk['size']} 字节）", file=sys.stderr, flush=True)
        upload(client, apk, f"{inbox}/{index}.apk")
    record = {"jobId": job_id, "package": apks[0]["package"], "versionCode": apks[0]["versionCode"],
              "sha256": [item["sha256"] for item in apks]}
    previous_pid = ""
    if apks[0]["package"] == PACKAGE:
        previous_pid = client.checked_shell(f"pidof {PACKAGE} | awk '{{print $1}}'").strip()
        if previous_pid and not previous_pid.isdecimal():
            raise RemoteError("升级前应用进程状态无效；尚未提交安装。")
        record["recovery"] = {"required": True, "openApp": open_app, "previousPid": previous_pid,
                              "expectedVersion": apks[0].get("versionName", "")}
    script = installer_script(apks, job, inbox, open_app, previous_pid)
    record_text = json.dumps(record) + "\n"
    client.tool("station_file_write_text", {"path": f"{inbox}/request.json", "text": record_text})
    client.tool("station_file_write_text", {"path": f"{inbox}/install.sh", "text": script})
    commands = ["set -e", "umask 077", f"mkdir -p {JOB_ROOT}", f"mkdir {job}"]
    for index, apk in enumerate(apks):
        commands += [f"cp {shlex.quote(inbox + '/' + str(index) + '.apk')} {job}/{index}.apk",
                     f"test \"$(sha256sum {job}/{index}.apk | cut -d ' ' -f 1)\" = {apk['sha256']}"]
        if len(commands) >= 42:
            client.checked_shell("\n".join(commands), 60000)
            commands = ["set -e", "umask 077"]
    commands += [f"cp {shlex.quote(inbox + '/request.json')} {job}/request.json",
                 f"cp {shlex.quote(inbox + '/install.sh')} {job}/install.sh",
                 f"test \"$(sha256sum {job}/request.json | cut -d ' ' -f 1)\" = {hashlib.sha256(record_text.encode()).hexdigest()}",
                 f"test \"$(sha256sum {job}/install.sh | cut -d ' ' -f 1)\" = {hashlib.sha256(script.encode()).hexdigest()}"]
    client.checked_shell("\n".join(commands), 60000)
    return job


def start_install(client: Client, job: str):
    # Separate session survives both ShellRunner's group cleanup and Shizuku's APK-change cleanup.
    # The handshake ensures detachment has completed before returning from station_shell_exec.
    client.checked_shell(f"/system/bin/setsid /system/bin/timeout -s KILL 180 /system/bin/sh {job}/install.sh "
                         f"</dev/null >{job}/output 2>&1 &\n"
                         f"for n in $(seq 1 100); do test -f {job}/started && exit 0; sleep 0.05; done; exit 1")


def install_status(client: Client, job_id: str) -> dict:
    if not re.fullmatch(r"[0-9a-f]{32}", job_id):
        raise RemoteError("安装任务编号需要 32 位小写十六进制。")
    job = f"{JOB_ROOT}/{job_id}"
    raw = client.checked_shell(f"test -f {job}/request.json || exit 1; cat {job}/request.json; "
                               f"if test -f {job}/exit_code; then cat {job}/exit_code; else echo pending; fi; "
                               f"tail -c 20000 {job}/output 2>/dev/null || true")
    try:
        first, code, output = raw.split("\n", 2)
        result = json.loads(first)
        if result["jobId"] != job_id:
            raise ValueError()
        package = result["package"]
        if not re.fullmatch(r"[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+", package):
            raise ValueError()
        result.update({"state": "running" if code == "pending" else "installed" if code == "0" else "failed",
                       "exitCode": None if code == "pending" else int(code), "output": output,
                       "verified": False, "completed": False})
    except (ValueError, KeyError, TypeError) as error:
        raise RemoteError("安装状态记录不完整，请保留任务编号继续核实。") from error
    if code == "0":
        installed = client.checked_shell(f"pm path --user current {shlex.quote(package)} | "
                                         "sed 's/^package://' | while IFS= read -r apk; do sha256sum \"$apk\" || exit; done")
        hashes = [line.split()[0] for line in installed.splitlines() if line.strip()]
        result["verified"] = sorted(hashes) == sorted(result["sha256"])
        if not result["verified"]:
            result["state"] = "unconfirmed"
        elif package == PACKAGE:
            check_recovery(client, job, result)
        else:
            result["completed"] = True
    return result


def check_recovery(client: Client, job: str, result: dict):
    requested = result.get("recovery")
    if not isinstance(requested, dict) or not requested.get("required"):
        # Old tasks only verified APK bytes. A later manual launch cannot prove automatic recovery.
        result.update(state="installed_restart_unconfirmed", recovery={"state": "legacy_unverified"})
        return
    raw = client.checked_shell(f"if test -f {job}/recovery.json; then cat {job}/recovery.json; "
                               "else printf '%s\\n' '{\"state\":\"pending\"}'; fi")
    try:
        observed = json.loads(raw)
        if not isinstance(observed, dict) or observed.get("state") not in {"pending", "ready", "failed"}:
            raise ValueError()
    except (ValueError, TypeError) as error:
        raise RemoteError("安装已校验，启动状态记录无效；请查询原任务，不要重装。") from error
    result["recovery"] = recovery = {**requested, **observed}
    if observed["state"] == "pending":
        result["state"] = "recovering"
        return
    if (observed["state"] == "failed" or not observed.get("processChanged")
            or not observed.get("mcpForeground")
            or not (observed.get("serviceSeen") or observed.get("activitySeen"))
            or observed.get("proofPid") != observed.get("pid")
            or (requested.get("openApp") and not (observed.get("activityStarted") and observed.get("activitySeen")))):
        result["state"] = "installed_restart_failed"
        recovery["state"] = "failed"
        return
    # These calls use the configured relay directly; reaching the APK on disk is not enough.
    server = client.rpc("initialize", {"protocolVersion": "2025-03-26", "capabilities": {},
                                      "clientInfo": {"name": "phone-install-recovery", "version": "1"}})
    version = str(server.get("serverInfo", {}).get("version", ""))
    live = client.tool("station_device_status")
    recovery["remote"] = {"connected": live.get("remoteConnected") is True, "serverVersion": version}
    if requested.get("expectedVersion") and version != requested["expectedVersion"]:
        recovery.update(state="failed", reason="远程运行版本与安装包不一致。")
        result["state"] = "installed_restart_failed"
    elif not recovery["remote"]["connected"]:
        recovery["state"] = "pending"
        result["state"] = "recovering"
    else:
        result["completed"] = True


def wait_install(client: Client, job_id: str, seconds: int) -> dict:
    deadline = time.monotonic() + seconds
    last = None
    original_timeout = getattr(client, "rpc_timeout", None)
    bounded = isinstance(original_timeout, (int, float))
    try:
        while time.monotonic() < deadline:
            if bounded:
                client.rpc_timeout = max(1, min(20, deadline - time.monotonic()))
            try:
                result = install_status(client, job_id)
                last = result
                if result["state"] not in {"running", "recovering"}:
                    return result
            except RemoteError:
                # Only repeat these known read-only checks; never install or launch again.
                pass
            time.sleep(min(2, max(0, deadline - time.monotonic())))
    finally:
        if bounded:
            client.rpc_timeout = original_timeout
    if last and last.get("verified"):
        last.update(state="installed_restart_unconfirmed", completed=False)
        last["recovery"].update(state="unconfirmed", reason="等待远程恢复超时；查询原任务，不要重装。")
        return last
    raise RemoteError(f"安装结果尚未确认。稍后运行 ./scripts/install-apk.sh --status {job_id}；不会自动重装。")


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="远程中继 + Shizuku，不需要 adb 连接。")
    modes = parser.add_subparsers(dest="mode", required=True)
    shell = modes.add_parser("shell", description="执行手机 /system/bin/sh -c 命令，不能使用电脑路径或 adb 前缀。")
    shell.add_argument("--status", action="store_true", help="只读检查 Shizuku")
    shell.add_argument("--json", action="store_true", help="返回完整执行结果")
    shell.add_argument("--timeout-ms", type=int, default=10000, choices=range(100, 60001), metavar="100..60000")
    shell.add_argument("--max-output-bytes", type=int, default=32768, choices=range(1, 65537), metavar="1..65536")
    shell.add_argument("command", nargs="?", help="例如 'pm list packages' 或 'id'")
    install = modes.add_parser("install", description="安装或保留数据升级 APK；多个参数用于 base + split APK。")
    install.add_argument("--check", action="store_true", help="只检查远程安装通道；不可用返回 75")
    install.add_argument("--status", metavar="JOB_ID", help="只读查询先前的安装结果")
    install.add_argument("--wait-seconds", type=int, default=210, help="等待结果的秒数，默认 210")
    install.add_argument("--no-open", action="store_true", help="更新手机工位时只恢复后台服务，不打开主页")
    install.add_argument("apks", nargs="*")
    args = parser.parse_args(argv)
    if args.mode == "shell" and not args.status and not args.command:
        shell.error("需要一条用引号包住的 command 或 --status")
    if args.mode == "shell" and args.command and (args.command.startswith("adb ") or len(args.command) > 16384 or "\0" in args.command):
        shell.error("只传手机 shell 命令，去掉 adb shell 前缀；最多 16384 字")
    if args.mode == "install" and (not 1 <= args.wait_seconds <= 600 or sum((args.check, bool(args.status), bool(args.apks))) != 1):
        install.error("选择 APK 文件、--check 或 --status 之一；等待时间为 1 到 600 秒")
    if args.mode == "install" and args.no_open and not args.apks:
        install.error("--no-open 仅与 APK 文件一起使用")
    job_id = None
    try:
        client = Client()
        if args.mode == "shell" and args.status:
            client.rpc("initialize", {"protocolVersion": "2025-03-26", "capabilities": {}, "clientInfo": {"name": "phone-shell", "version": "1"}})
            print(json.dumps(client.tool("station_shell_status"), ensure_ascii=False))
            return 0
        if args.mode == "install" and args.status:
            client.rpc_timeout = 20
            client.rpc("initialize", {"protocolVersion": "2025-03-26", "capabilities": {}, "clientInfo": {"name": "phone-install-status", "version": "1"}})
            result = install_status(client, args.status)
            print(json.dumps(result, ensure_ascii=False))
            return 0 if result.get("completed") else 75 if result["state"] in {"running", "recovering"} else 1
        apks = apk_set(args.apks) if args.mode == "install" and args.apks else None
        client.initialize(install=args.mode == "install")
        if args.mode == "shell":
            result = client.shell(args.command, args.timeout_ms, args.max_output_bytes)
            if args.json:
                print(json.dumps(result, ensure_ascii=False))
            else:
                print(result.get("stdout", ""), end="")
                print(result.get("stderr", ""), end="", file=sys.stderr)
                if result.get("outputTruncated") or result.get("outputIncomplete"):
                    print("输出未完整保留。", file=sys.stderr)
            return 124 if result.get("timedOut") else result.get("exitCode") if isinstance(result.get("exitCode"), int) else 1
        if args.check:
            print("远程安装通道已就绪（Shizuku shell）。")
            return 0
        job_id = uuid.uuid4().hex
        print(f"安装任务 {job_id}", file=sys.stderr, flush=True)
        job = prepare(client, apks, job_id, not args.no_open)
        print("APK 校验通过，提交安装。更新手机工位时自动拉起并确认远程恢复。", file=sys.stderr, flush=True)
        try:
            start_install(client, job)
        except RemoteError:
            print("提交响应未确认，继续查询同一安装任务。", file=sys.stderr, flush=True)
        result = wait_install(client, job_id, args.wait_seconds)
        print(json.dumps(result, ensure_ascii=False))
        return 0 if result.get("completed") else 1
    except RemoteError as error:
        print(str(error), file=sys.stderr)
        if job_id:
            print(f"保留任务编号：{job_id}。结果未确认时先查询，不要重复安装。", file=sys.stderr)
        return 75 if args.mode == "install" and args.check and not isinstance(error, DeviceMismatch) else 1


if __name__ == "__main__":
    raise SystemExit(main())
