#!/usr/bin/env python3
"""用本机假 MCP 服务验证通知路由，不操作手机。"""

import contextlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import io
import json
from pathlib import Path
import shlex
import shutil
import subprocess
import tempfile
import threading
import unittest
from unittest.mock import patch

import notify_mcp


class NotifyMcpTest(unittest.TestCase):
    def setUp(self):
        self.calls = []
        self.outcome = "played"
        self.display_mode = "replace"
        self.drop = False
        self.preflight = True
        test = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_args):
                pass

            def do_POST(self):
                request = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                test.calls.append(request)
                method = request["method"]
                if method == "tools/call" and test.drop:
                    self.close_connection = True
                    return
                if method == "initialize":
                    result = {}
                elif method == "tools/list":
                    result = {"tools": [{"name": "station_notify", "inputSchema": {
                        "properties": {"sound": {"type": "string"}}
                    }}] if test.preflight else []}
                else:
                    result = {"content": [{"type": "text", "text": json.dumps({
                        "posted": True, "sound": test.outcome, "mode": test.display_mode,
                    })}]}
                body = json.dumps({"jsonrpc": "2.0", "id": request["id"], "result": result}).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.endpoint = f"http://127.0.0.1:{self.server.server_port}/mcp"

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()

    def send(self, *args):
        with patch.object(notify_mcp, "gateway", return_value=(self.endpoint, "Bearer test")):
            with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                return notify_mcp.send(*args)

    def test_remote_notification_and_options(self):
        title = '标题 `不会执行` $(也不会执行) "引号"'
        self.assertEqual(0, self.send(title, "内容\n第二行", "stack", "Codex", "/system/media/Bell.ogg"))
        args = self.calls[-1]["params"]["arguments"]
        self.assertEqual({"title": title, "text": "内容\n第二行",
                          "agent": "Codex", "sound": "/system/media/Bell.ogg"}, args)
        self.assertEqual(1, sum(call["method"] == "tools/call" for call in self.calls))

    def test_status_uses_phone_display_mode(self):
        for phone_mode, legacy_mode, message in [
            ("stack", "replace", "通知已发出"),
            ("replace", "stack", "通知已更新"),
        ]:
            with self.subTest(phone_mode=phone_mode):
                self.display_mode = phone_mode
                output = io.StringIO()
                with patch.object(notify_mcp, "gateway", return_value=(self.endpoint, "Bearer test")):
                    with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(output):
                        self.assertEqual(0, notify_mcp.send("标题", "内容", legacy_mode, "", ""))
                self.assertIn(message, output.getvalue())
                self.assertNotIn("mode", self.calls[-1]["params"]["arguments"])

    def test_only_preflight_failure_allows_adb_fallback(self):
        self.preflight = False
        self.assertEqual(notify_mcp.UNAVAILABLE, self.send("标题", "内容", "replace", "", ""))
        self.assertFalse(any(call["method"] == "tools/call" for call in self.calls))

    def test_unknown_delivery_does_not_retry(self):
        self.drop = True
        self.assertEqual(1, self.send("标题", "内容", "replace", "", ""))
        self.assertEqual(1, sum(call["method"] == "tools/call" for call in self.calls))

    def test_posted_but_sound_failed_is_failure_without_fallback(self):
        self.outcome = "failed"
        self.assertEqual(1, self.send("标题", "内容", "replace", "", ""))
        self.assertEqual(1, sum(call["method"] == "tools/call" for call in self.calls))

    def test_silent_is_success(self):
        self.outcome = "silent"
        self.assertEqual(0, self.send("标题", "内容", "replace", "", ""))

    def shell_case(self, online):
        root = Path(__file__).resolve().parent.parent
        with tempfile.TemporaryDirectory(prefix="phone-notify-test-") as temp:
            base = Path(temp)
            (base / "scripts").mkdir()
            (base / "lib").mkdir()
            for source in ["scripts/notify.sh", "lib/common.sh", "lib/notify_mcp.py"]:
                shutil.copy2(root / source, base / source)
            output = (f"{self.endpoint}\nchannel=remote local=0ms remote=1ms relay=online\n"
                      "Authorization: Bearer test\n") if online else "off\n"
            mcp = base / "scripts/mcp.sh"
            mcp.write_text("#!/bin/zsh\nprint -r -- " + shlex.quote(output))
            mcp.chmod(0o755)
            marker = base / "adb-was-called"
            connect = base / "scripts/connect.sh"
            connect.write_text("#!/bin/zsh\ntouch " + shlex.quote(str(marker)) + "\nexit 47\n")
            connect.chmod(0o755)
            result = subprocess.run(["zsh", str(base / "scripts/notify.sh"), "--title", "远程测试",
                                     "--text", "远程正文", "--agent", "Codex"],
                                    capture_output=True, text=True, timeout=15)
            return result, marker.exists()

    def test_script_sends_without_connecting_adb(self):
        result, called_adb = self.shell_case(True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertFalse(called_adb)
        self.assertEqual("played", result.stdout.strip())

    def test_script_falls_back_before_submission(self):
        result, called_adb = self.shell_case(False)
        self.assertEqual(47, result.returncode)
        self.assertTrue(called_adb)
        self.assertEqual([], self.calls)

    def test_script_does_not_fallback_after_submission(self):
        self.drop = True
        result, called_adb = self.shell_case(True)
        self.assertEqual(1, result.returncode)
        self.assertFalse(called_adb)
        self.assertEqual(1, sum(call["method"] == "tools/call" for call in self.calls))


if __name__ == "__main__":
    unittest.main()
