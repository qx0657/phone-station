#!/usr/bin/env python3
"""Verify APK metadata, upload concurrency, install sessions, and lost-reply handling."""
import io
import json
from pathlib import Path
import struct
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import remote_ops as ops


def binary_manifest(package="dev.test.app", version=42, split="", utf8=True, version_name=""):
    strings = ["manifest", "package", "versionCode", "split", package, split,
               "http://schemas.android.com/apk/res/android", "versionCodeMajor", "versionName", version_name]
    encoded = []
    offsets = []
    for text in strings:
        offsets.append(sum(map(len, encoded)))
        raw = text.encode("utf-8" if utf8 else "utf-16le")
        encoded.append(bytes([len(text), len(raw)]) + raw + b"\0" if utf8 else struct.pack("<H", len(text)) + raw + b"\0\0")
    start = 28 + len(strings) * 4
    body = b"".join(encoded)
    pool = struct.pack("<HHIIIIII", 1, 28, start + len(body), len(strings), 0, 0x100 if utf8 else 0, start, 0)
    pool += struct.pack("<" + "I" * len(strings), *offsets) + body
    missing = 0xffffffff

    def attr(ns, name, typ, val):
        return struct.pack("<IIIHBBI", ns, name, missing, 8, 0, typ, val)

    attrs = attr(missing, 1, 3, 4) + attr(6, 2, 16, version & 0xffffffff)
    attrs += attr(6, 7, 16, version >> 32)
    if version_name:
        attrs += attr(6, 8, 3, 9)
    if split:
        attrs += attr(missing, 3, 3, 5)
    node = struct.pack("<HHIII", 0x102, 16, 36 + len(attrs), 1, missing)
    node += struct.pack("<IIHHHHHH", missing, 0, 20, 20, len(attrs) // 20, 0, 0, 0) + attrs
    return struct.pack("<HHI", 3, 8, 8 + len(pool) + len(node)) + pool + node


class RemoteOpsTest(unittest.TestCase):
    def apk(self, root, name, **info):
        file = root / name
        with zipfile.ZipFile(file, "w") as archive:
            archive.writestr("AndroidManifest.xml", binary_manifest(**info))
        return file

    def test_metadata_and_split_validation(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            base = self.apk(root, "base.apk", version=(2 << 32) | 42, utf8=False, version_name="42.1")
            split = self.apk(root, "split.apk", version=(2 << 32) | 42, split="config.arm64")
            result = ops.apk_set([str(split), str(base)])
            self.assertEqual(result[0]["versionCode"], (2 << 32) | 42)
            self.assertEqual(result[0]["versionName"], "42.1")
            self.assertEqual(result[1]["split"], "config.arm64")
            with self.assertRaises(ops.RemoteError):
                ops.apk_set([str(split)])
            different = self.apk(root, "other.apk", package="dev.other.app")
            with self.assertRaises(ops.RemoteError):
                ops.apk_set([str(base), str(different)])

    def test_upload_keeps_returned_file_version(self):
        calls = []

        class Client:
            def tool(self, name, args):
                calls.append((name, args))
                return {"targetVersion": str(len(calls))}

        with tempfile.TemporaryDirectory() as tmp:
            file = Path(tmp) / "data.apk"
            file.write_bytes(b"abcdefgh")
            with patch.object(ops, "CHUNK_BYTES", 3):
                ops.upload(Client(), {"file": file}, "/remote/data.apk")
        self.assertEqual([call[0] for call in calls], ["station_file_write_bytes", "station_file_append_bytes", "station_file_append_bytes"])
        self.assertNotIn("targetVersion", calls[0][1])
        self.assertEqual(calls[2][1]["targetVersion"], "2")
        self.assertEqual(b"".join(bytes.fromhex(call[1]["hex"]) for call in calls), b"abcdefgh")

    def test_shell_transport_never_resubmits_lost_mutation(self):
        with patch.object(ops, "gateway_binary", return_value=Path("/test/gateway")):
            client = ops.Client()
        with patch.object(ops.subprocess, "run", side_effect=subprocess.TimeoutExpired("gateway", 245)) as call:
            with self.assertRaises(ops.RemoteError):
                client.shell("pm install-commit 9")
            self.assertEqual(call.call_count, 1)

    def test_wait_reconnects_by_reading_same_job_only(self):
        with patch.object(ops, "install_status", side_effect=[ops.RemoteError("offline"), {"state": "running"}, {"state": "installed", "verified": True}]) as read:
            with patch.object(ops.time, "sleep"):
                self.assertTrue(ops.wait_install(object(), "abc", 10)["verified"])
        self.assertEqual([call.args[1] for call in read.call_args_list], ["abc"] * 3)

    def test_unknown_device_does_not_request_adb_fallback(self):
        with patch.object(ops, "Client") as client, patch.object(ops.sys, "stderr", io.StringIO()):
            client.return_value.initialize.side_effect = ops.DeviceMismatch("unknown device")
            self.assertEqual(ops.main(["install", "--check"]), 1)
            client.return_value.initialize.side_effect = ops.RemoteError("offline")
            self.assertEqual(ops.main(["install", "--check"]), 75)

    def test_status_verifies_installed_bytes_even_at_same_version(self):
        job_id = "a" * 32
        record = {"jobId": job_id, "package": "dev.test.app", "versionCode": 42, "sha256": ["a" * 64]}

        class Client:
            def checked_shell(self, cmd):
                if "request.json" in cmd:
                    return json.dumps(record) + "\n0\nSuccess\n"
                return "b" * 64 + " /data/app/base.apk\n"

        result = ops.install_status(Client(), job_id)
        self.assertEqual(result["state"], "unconfirmed")
        self.assertFalse(result["verified"])

    def test_pending_job_after_kill_or_reboot_is_not_running_forever(self):
        job_id = "a" * 32
        record = {"jobId": job_id, "package": "dev.test.app", "sha256": ["a" * 64]}
        for helper in ("expired", "exited", "unknown"):
            for installed in (False, True):
                with self.subTest(helper=helper, installed=installed):
                    class Client:
                        def checked_shell(self, cmd):
                            if "request.json" in cmd: return json.dumps(record) + "\n pending\nlog".replace(" pending", "pending")
                            if "lifecycle" in cmd: return helper
                            return ("a" if installed else "b") * 64 + " /data/app/base.apk\n"
                    result = ops.install_status(Client(), job_id)
                    self.assertEqual(result["state"], "installed" if installed else "result_unknown")
                    self.assertEqual(result["completed"], installed)

    def test_expired_recovery_keeps_installed_fact(self):
        job, client = self.status_client({"state": "pending"})
        original = client.checked_shell
        client.checked_shell = lambda cmd: "expired" if "lifecycle" in cmd else original(cmd)
        result = ops.install_status(client, job)
        self.assertEqual(result["state"], "installed_restart_unconfirmed")
        self.assertTrue(result["verified"])
        self.assertFalse(result["completed"])

    def test_install_session_failure_preserves_app_and_abandons_session(self):
        for failure in (False, True):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                job = root / "job"
                inbox = root / "inbox"
                job.mkdir()
                inbox.mkdir()
                (job / "0.apk").write_bytes(b"apk")
                log = root / "pm-calls"
                pm = root / "pm"
                pm.write_text(f'''#!/bin/sh
echo "$*" >> '{log}'
case "$1" in
 install-create) echo 'Success: created install session [71]';;
 install-write) [ "$4" = 71 ] || exit 2; echo 'Success: streamed 3 bytes';;
 install-commit) {'echo "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]"; exit 1' if failure else "echo Success"};;
esac
''')
                pm.chmod(0o755)
                script = ops.installer_script([{"size": 3, "package": "dev.test.app"}], str(job), str(inbox))
                result = subprocess.run(["/bin/sh"], input=script, text=True, capture_output=True,
                                        env={"PATH": f"{root}:/usr/bin:/bin"}, timeout=8)
                self.assertEqual(result.returncode, int(failure), result.stderr)
                self.assertEqual((job / "exit_code").read_text().strip(), str(int(failure)))
                self.assertFalse((job / "0.apk").exists())
                self.assertEqual("install-abandon" in log.read_text(), failure)
                self.assertNotIn("uninstall", log.read_text())

    def test_self_update_restarts_outside_app_and_records_failures(self):
        cases = [(True, False, "200", "ready"), (True, True, "200", "failed"),
                 (True, False, "100", "failed"), (False, False, "200", "ready")]
        for open_app, rejected, pid, expected in cases:
            with self.subTest(open_app=open_app, rejected=rejected, pid=pid), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                job, inbox = root / "job", root / "inbox"
                job.mkdir(); inbox.mkdir()
                (job / "0.apk").write_bytes(b"apk")
                tools = {
                    "pm": "case $1 in install-create) echo 'Success: created install session [71]';; *) echo Success;; esac",
                    "sleep": "exit 0",
                    "timeout": 'shift; exec "$@"',
                    "pidof": f"echo {pid}",
                    "dumpsys": ("case $2 in services) echo '* ServiceRecord{1 u0 dev.phonestation.adbkeep/.FileMcpService c:com.android.shell}'; "
                                "echo 'isForeground=true';; *) " + f"echo 'installRecoveryPid={pid}'; "
                                f"echo 'installRecoveryService={str(not rejected).lower()}'; "
                                f"echo 'installRecoveryActivity={str(open_app and not rejected).lower()}';; esac"),
                    "am": (f'echo "$*" >> "{root}/am-calls"\n' +
                           ("echo 'Error: denied'; exit 1" if rejected else
                            "case $1 in start) echo 'Status: ok'; echo 'Activity: dev.phonestation.adbkeep/.MainActivity';; *) echo 'Starting service';; esac")),
                }
                for name, body in tools.items():
                    file = root / name
                    file.write_text("#!/bin/sh\n" + body + "\n")
                    file.chmod(0o755)
                script = ops.installer_script([{"size": 3, "package": ops.PACKAGE}], str(job), str(inbox), open_app, "100")
                run = subprocess.run(["/bin/sh"], input=script, text=True, capture_output=True,
                                     env={"PATH": f"{root}:/usr/bin:/bin"}, timeout=8)
                self.assertEqual(run.returncode, 0, run.stderr)  # Installation succeeded even if launching failed.
                self.assertEqual((job / "exit_code").read_text().strip(), "0")
                recovery = json.loads((job / "recovery.json").read_text())
                self.assertEqual(recovery["state"], expected)
                self.assertEqual(recovery["processChanged"], pid != "100")
                self.assertEqual(recovery["serviceAccepted"], not rejected)
                self.assertEqual(recovery["activityStarted"], open_app and not rejected)
                calls = (root / "am-calls").read_text().splitlines()
                self.assertEqual(len(calls), 2 if open_app else 1)
                self.assertFalse((job / "0.apk").exists())

    def status_client(self, observed, version="40", remote=True, legacy=False):
        job_id = "b" * 32
        record = {"jobId": job_id, "package": ops.PACKAGE, "versionCode": 40, "sha256": ["c" * 64]}
        if not legacy:
            record["recovery"] = {"required": True, "openApp": True, "expectedVersion": "40"}

        class Client:
            rpc_calls = 0
            commands = []
            def checked_shell(self, cmd):
                self.commands.append(cmd)
                if "request.json" in cmd: return json.dumps(record) + "\n0\nSuccess\n"
                if "recovery.json" in cmd: return json.dumps(observed)
                if "lifecycle" in cmd: return "running"
                return "c" * 64 + " /data/app/base.apk\n"
            def rpc(self, method, params):
                self.rpc_calls += 1
                return {"serverInfo": {"version": version}}
            def tool(self, name): return {"remoteConnected": remote}
        return job_id, Client()

    def test_recovery_requires_real_service_and_current_remote_version(self):
        ready = {"state": "ready", "processChanged": True, "mcpForeground": True, "activityStarted": True,
                 "pid": 200, "proofPid": 200, "serviceSeen": True, "activitySeen": True}
        for version, remote, expected in [("40", True, "installed"), ("39", True, "installed_restart_failed"),
                                          ("40", False, "recovering")]:
            with self.subTest(version=version, remote=remote):
                job, client = self.status_client(ready, version, remote)
                result = ops.install_status(client, job)
                self.assertEqual(result["state"], expected)
                self.assertTrue(result["verified"])
                self.assertEqual(result["completed"], expected == "installed")
                self.assertFalse(any("am start" in cmd or "pm install-" in cmd for cmd in client.commands))

    def test_manual_launch_cannot_hide_failed_or_unverified_recovery(self):
        for legacy, observed in [(False, {"state": "failed"}), (True, {}),
                                 (False, {"state": "ready", "processChanged": True, "mcpForeground": True,
                                          "activityStarted": True, "pid": 200, "proofPid": 200,
                                          "serviceSeen": False, "activitySeen": False})]:
            with self.subTest(legacy=legacy):
                job, client = self.status_client(observed, legacy=legacy)
                result = ops.install_status(client, job)
                self.assertTrue(result["verified"])
                self.assertFalse(result["completed"])
                self.assertEqual(client.rpc_calls, 0)
                self.assertEqual(result["state"], "installed_restart_unconfirmed" if legacy else "installed_restart_failed")

    def test_wait_does_not_finish_at_verified_apk_while_recovering(self):
        with patch.object(ops, "install_status", side_effect=[{"state": "recovering", "verified": True},
                         {"state": "installed", "verified": True, "completed": True}]) as read:
            with patch.object(ops.time, "sleep"):
                self.assertTrue(ops.wait_install(object(), "same-job", 10)["completed"])
        self.assertEqual([call.args[1] for call in read.call_args_list], ["same-job"] * 2)

    def test_recovery_timeout_preserves_known_installation_and_job(self):
        clock = [0]
        result = {"state": "recovering", "verified": True, "recovery": {"state": "pending"}}
        with patch.object(ops, "install_status", return_value=result) as read, \
                patch.object(ops.time, "monotonic", side_effect=lambda: clock[0]), \
                patch.object(ops.time, "sleep", side_effect=lambda seconds: clock.__setitem__(0, clock[0]+seconds)):
            result = ops.wait_install(object(), "same-job", 5)
        self.assertEqual(result["state"], "installed_restart_unconfirmed")
        self.assertTrue(result["verified"])
        self.assertFalse(result["completed"])
        self.assertTrue(all(call.args[1] == "same-job" for call in read.call_args_list))


if __name__ == "__main__":
    unittest.main()
