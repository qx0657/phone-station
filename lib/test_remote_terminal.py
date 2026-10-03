import json
import os
import select
import subprocess
import sys
import tempfile
import termios
import time
import unittest
from pathlib import Path

from remote_ops import RemoteError
from remote_terminal import Receipt, acknowledge, initialize, read_output, send_input

SESSION_ID = "a" * 32


class Phone:
    def __init__(self):
        self.calls = []
        self.next_sequence = 0
        self.drop_input = False
        self.drop_before_input = False
        self.wrong_id = False
        self.model = "PGT-AN20"

    def rpc(self, method, args):
        self.calls.append((method, args))
        if method == "tools/list":
            return {"tools": [{"name": "station_terminal_" + op} for op in ("open", "read", "input", "resize", "close")]}
        return {}

    def tool(self, name, args=None):
        self.calls.append((name, args))
        if name == "station_controls_status":
            return {"model": self.model, "verified": True}
        if name == "station_shell_status":
            return {"available": True}
        if name == "station_terminal_input":
            if self.drop_before_input:
                raise RemoteError("offline")
            self.next_sequence += 1
            if self.drop_input:
                raise RemoteError("lost reply")
        return {"sessionId": "b" * 32 if self.wrong_id else SESSION_ID, "state": "running",
                "nextInputSequence": self.next_sequence, "offset": 0, "nextOffset": 3,
                "endOffset": 3, "droppedBytes": 0, "hex": "e4b8ad"}


class TerminalTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.receipt = Receipt(SESSION_ID, Path(self.temp.name))
        self.phone = Phone()

    def tearDown(self):
        self.receipt.unlock()
        self.temp.cleanup()

    def test_capabilities_are_read_only(self):
        initialize(self.phone)
        self.assertTrue(all(name in {"initialize", "tools/list", "station_controls_status", "station_shell_status"}
                            for name, _ in self.phone.calls))
        self.phone.model = "other"
        with self.assertRaises(RemoteError):
            initialize(self.phone)

    def test_lost_input_ack_queries_original_without_replay(self):
        self.phone.drop_input = True
        with self.assertRaises(RemoteError):
            send_input(self.phone, self.receipt, b"id\r")
        saved = json.loads(self.receipt.path.read_text())
        self.assertEqual(saved["pendingSequence"], 0)
        self.assertNotIn("hex", saved)
        self.assertNotIn("id\\r", self.receipt.path.read_text())
        with self.assertRaises(RemoteError):
            send_input(self.phone, self.receipt, b"id\r")
        result, data = read_output(self.phone, self.receipt)
        self.assertEqual(data, "中".encode())
        self.assertIsNone(self.receipt.value["pendingSequence"])
        self.assertEqual(self.receipt.value["nextInputSequence"], 1)
        self.assertEqual(len([name for name, _ in self.phone.calls if name == "station_terminal_input"]), 1)
        self.assertEqual(self.receipt.path.stat().st_mode & 0o777, 0o600)

    def test_missing_input_ack_remains_blocked_after_restart(self):
        self.phone.drop_before_input = True
        with self.assertRaises(RemoteError):
            send_input(self.phone, self.receipt, b"id\r")
        self.receipt.load()
        read_output(self.phone, self.receipt)
        self.assertEqual(self.receipt.value["pendingSequence"], 0)
        with self.assertRaises(RemoteError):
            send_input(self.phone, self.receipt, b"id\r")
        self.assertEqual(len([name for name, _ in self.phone.calls if name == "station_terminal_input"]), 1)

    def test_wrong_receipt_cannot_acknowledge_input(self):
        self.phone.wrong_id = True
        with self.assertRaises(RemoteError):
            send_input(self.phone, self.receipt, b"\x03")
        self.assertEqual(self.receipt.value["pendingSequence"], 0)
        with self.assertRaises(RemoteError):
            read_output(self.phone, self.receipt)

    def test_same_session_cannot_open_twice_locally(self):
        with self.assertRaises(RemoteError):
            Receipt(SESSION_ID, Path(self.temp.name))

    def test_tty_control_c_forwarding_close_and_restoration(self):
        master, slave = os.openpty()
        previous = termios.tcgetattr(slave)
        script = r'''
import sys
from pathlib import Path
from remote_terminal import Receipt, interact
class Phone:
    sequence = 0
    output = b"READY\r\n"
    def tool(self, name, args):
        if name == "station_terminal_open":
            return {"jobId": args["sessionId"], "kind": "terminal"}
        if name == "station_terminal_close":
            print("CLOSE_ONCE", flush=True)
            return {"sessionId": args["sessionId"], "state": "closed"}
        if name == "station_terminal_input":
            assert args["hex"] == "03", args
            self.sequence += 1
            self.output += b"INTERRUPT_FORWARDED\r\n"
        offset = args.get("offset", len(self.output))
        return {"sessionId": args["sessionId"], "state": "running", "hex": self.output[offset:].hex(),
                "offset": offset, "nextOffset": len(self.output), "endOffset": len(self.output),
                "droppedBytes": 0, "nextInputSequence": self.sequence}
r = Receipt("c" * 32, Path(sys.argv[1]))
try:
    sys.exit(interact(Phone(), r))
finally:
    r.unlock()
'''
        process = subprocess.Popen([sys.executable, "-c", script, self.temp.name],
                                   stdin=slave, stdout=slave, stderr=slave, cwd=Path(__file__).parent)
        captured = b""
        def wait_for(wanted):
            nonlocal captured
            deadline = time.monotonic() + 5
            while time.monotonic() < deadline:
                ready, _, _ = select.select([master], [], [], 0.1)
                if ready:
                    captured += os.read(master, 8192)
                if wanted in captured:
                    return
            self.fail(f"Missing {wanted!r} in {captured!r}")
        try:
            wait_for(b"READY")
            self.assertNotEqual(termios.tcgetattr(slave), previous)
            os.write(master, b"\x03")
            wait_for(b"INTERRUPT_FORWARDED")
            os.write(master, b"\x1d")
            wait_for(b"CLOSE_ONCE")
            self.assertEqual(process.wait(timeout=5), 0)
            restored = termios.tcgetattr(slave)
            # Darwin's PTY driver latches EXTPROC after tcsetattr, even in a
            # setraw/restore-only fixture. Compare every user-controlled flag.
            if sys.platform == "darwin":
                restored[3] &= ~0x20000000
                previous[3] &= ~0x20000000
            self.assertEqual(restored, previous)
            self.assertEqual(captured.count(b"CLOSE_ONCE"), 1)
        finally:
            if process.poll() is None:
                process.kill(); process.wait()
            os.close(master); os.close(slave)


if __name__ == "__main__":
    unittest.main()
