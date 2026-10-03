"""No real adb: the script must delegate settings ownership to the phone app."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent.parent


class StayAwakeScriptTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "scripts").mkdir()
        (self.root / "lib").mkdir()
        shutil.copyfile(ROOT / "scripts/stay-awake.sh", self.root / "scripts/stay-awake.sh")
        self.events = self.root / "events"
        self.env = dict(os.environ, TEST_EVENTS=str(self.events), TEST_ADB=str(self.root / "adb"), TEST_RESULT="1")
        (self.root / "lib/common.sh").write_text('adb_bin() { print -r -- "$TEST_ADB"; }\nonline_serial() { print test-device; }\n')
        for name, content in {
            "scripts/connect.sh": '#!/bin/zsh\nprint connect >> "$TEST_EVENTS"\n',
            "adb": '''#!/bin/zsh
print -r -- "$*" >> "$TEST_EVENTS"
[[ ${TEST_LOST:-0} == 1 ]] && exit 1
print -r -- "Broadcast completed: result=$TEST_RESULT"
''',
        }.items():
            file = self.root / name
            file.write_text(content)
            file.chmod(0o700)

    def run_script(self, *args):
        return subprocess.run(["zsh", str(self.root / "scripts/stay-awake.sh"), *args],
                              env=self.env, text=True, capture_output=True, timeout=5)

    def test_uses_one_protected_broadcast_and_never_direct_settings(self):
        for mode, value in [("on", "true"), ("off", "false"), ("restore", "false")]:
            self.events.unlink(missing_ok=True)
            result = self.run_script(mode)
            self.assertEqual(result.returncode, 0, result.stderr)
            events = self.events.read_text().splitlines()
            self.assertEqual(len(events), 2)
            self.assertIn(".StayAwakeReceiver", events[1])
            self.assertIn("-f 0x00400000", events[1])
            self.assertIn("--ez on " + value, events[1])
            self.assertNotIn("settings put", events[1])

    def test_no_fallback_or_retry_when_unconfirmed(self):
        for code in ["0", "10", "-1"]:
            self.events.unlink(missing_ok=True)
            self.env["TEST_RESULT"] = code
            self.assertNotEqual(self.run_script("off").returncode, 0)
            self.assertEqual(len(self.events.read_text().splitlines()), 2)
        self.env["TEST_LOST"] = "1"
        self.assertNotEqual(self.run_script().returncode, 0)

    def test_invalid_arguments_do_not_connect(self):
        for args in [("wrong",), ("off", "extra"), ("-h",)]:
            self.run_script(*args)
        self.assertFalse(self.events.exists())


if __name__ == "__main__":
    unittest.main()
