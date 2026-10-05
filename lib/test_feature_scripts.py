"""Exercise the real adb entry-point guard without contacting a device."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import sys

ROOT = Path(__file__).resolve().parent.parent


class FeatureScriptsTest(unittest.TestCase):
    def test_phone_choices_gate_adb_and_old_phones_remain_compatible(self):
        with tempfile.TemporaryDirectory() as directory:
            adb = Path(directory) / "adb"
            adb.write_text('#!/bin/zsh\n[[ "$TEST_FAIL" == 1 ]] && exit 1\nprint -r -- "$TEST_FLAGS"\n')
            adb.chmod(0o700)
            command = 'source "$1"; require_station_feature "$2" phone screen; print executed'
            for flags, failure, allowed in [("1\n1", "0", True), ("0\n1", "0", False),
                                             ("1\n0", "0", False), ("null\nnull", "0", True),
                                             ("1\n1", "1", False)]:
                result = subprocess.run(["zsh", "-e", "-c", command, "test", str(ROOT / "lib/common.sh"), str(adb)],
                                        env=dict(os.environ, TEST_FLAGS=flags, TEST_FAIL=failure),
                                        text=True, capture_output=True, timeout=5)
                self.assertEqual(result.returncode == 0, allowed, result.stderr)
                self.assertEqual("executed" in result.stdout, allowed)

    def test_switch_revocation_gives_recorder_time_to_finalize(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            flags = base / "flags"
            flags.write_text("1\n1\n")
            adb = base / "adb"
            adb.write_text('#!/bin/zsh\ncat "$TEST_FLAGS_FILE"\n')
            adb.chmod(0o700)
            recorder = base / "recorder.py"
            recorder.write_text('import os, signal, time\nfrom pathlib import Path\n'
                                'if signal.getsignal(signal.SIGINT) == signal.SIG_IGN: raise SystemExit(12)\n'
                                'def finish(signum, frame):\n'
                                '    time.sleep(0.1)\n'
                                '    Path(os.environ["TEST_FINALIZED"]).write_text("finalized")\n'
                                '    raise SystemExit(0)\n'
                                'signal.signal(signal.SIGINT, finish)\n'
                                'Path(os.environ["TEST_FLAGS_FILE"]).write_text("0\\n1\\n")\n'
                                'while True: time.sleep(0.1)\n')
            command = 'source "$1"; ADB="$2"; SERIAL=phone; run_station_screen "$3" "$4"'
            result = subprocess.run(["zsh", "-e", "-c", command, "test", str(ROOT / "lib/common.sh"),
                                     str(adb), sys.executable, str(recorder)],
                                    env=dict(os.environ, TEST_FLAGS_FILE=str(flags), TEST_FINALIZED=str(base / "finalized")),
                                    text=True, capture_output=True, timeout=8)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual((base / "finalized").read_text(), "finalized")

    def test_hung_recorder_is_ended_and_does_not_claim_saved_video(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            flags = base / "flags"
            flags.write_text("1\n1\n")
            adb = base / "adb"
            adb.write_text('#!/bin/zsh\ncat "$TEST_FLAGS_FILE"\n')
            adb.chmod(0o700)
            recorder = base / "hung.py"
            recorder.write_text('import os, signal, time\nfrom pathlib import Path\n'
                                'signal.signal(signal.SIGINT, signal.SIG_IGN)\n'
                                'signal.signal(signal.SIGTERM, signal.SIG_IGN)\n'
                                'Path(os.environ["TEST_PID"]).write_text(str(os.getpid()))\n'
                                'Path(os.environ["TEST_FLAGS_FILE"]).write_text("0\\n1\\n")\n'
                                'while True: time.sleep(0.1)\n')
            command = 'source "$1"; ADB="$2"; SERIAL=phone; run_station_screen "$3" "$4"'
            result = subprocess.run(["zsh", "-e", "-c", command, "test", str(ROOT / "lib/common.sh"),
                                     str(adb), sys.executable, str(recorder)],
                                    env=dict(os.environ, TEST_FLAGS_FILE=str(flags), TEST_PID=str(base / "pid")),
                                    text=True, capture_output=True, timeout=15)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("请检查录像文件是否完整", result.stderr)
            with self.assertRaises(ProcessLookupError): os.kill(int((base / "pid").read_text()), 0)


if __name__ == "__main__":
    unittest.main()
