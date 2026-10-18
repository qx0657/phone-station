"""No real adb: vibrate.sh must keep the same vibrator_manager command."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent.parent


class VibrateScriptTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "scripts").mkdir()
        (self.root / "lib").mkdir()
        shutil.copyfile(ROOT / "scripts/vibrate.sh", self.root / "scripts/vibrate.sh")
        self.events = self.root / "events"
        self.env = dict(os.environ, TEST_EVENTS=str(self.events), TEST_ADB=str(self.root / "adb"))
        (self.root / "lib/common.sh").write_text(
            'adb_bin() { print -r -- "$TEST_ADB"; }\nonline_serial() { print test-device; }\n'
        )
        for name, content in {
            "scripts/connect.sh": '#!/bin/zsh\nprint connect >> "$TEST_EVENTS"\n',
            "adb": '#!/bin/zsh\nprint -r -- "$*" >> "$TEST_EVENTS"\n',
        }.items():
            file = self.root / name
            file.write_text(content)
            file.chmod(0o700)

    def run_script(self, *args):
        return subprocess.run(
            ["zsh", str(self.root / "scripts/vibrate.sh"), *args],
            env=self.env, text=True, capture_output=True, timeout=5,
        )

    def test_default_and_stop_use_adb_shell(self):
        result = self.run_script()
        self.assertEqual(result.returncode, 0, result.stderr)
        events = self.events.read_text().splitlines()
        self.assertEqual(events[0], "connect")
        self.assertIn("-s test-device shell cmd vibrator_manager synced -f -B oneshot -a 400 200", events[1])
        self.events.unlink()
        stopped = self.run_script("stop")
        self.assertEqual(stopped.returncode, 0, stopped.stderr)
        self.assertIn("-s test-device shell cmd vibrator_manager cancel", self.events.read_text())

    def test_station_shell_replaces_adb_when_present(self):
        binary = self.root / "build/.phone-station/station"
        binary.parent.mkdir(parents=True)
        binary.write_text('#!/bin/zsh\nprint -r -- "$*" >> "$TEST_EVENTS"\n')
        binary.chmod(0o700)
        result = self.run_script("400", "200", "1000", "2")
        self.assertEqual(result.returncode, 0, result.stderr)
        line = self.events.read_text().splitlines()[1]
        self.assertTrue(line.startswith("shell test-device cmd vibrator_manager synced -f -B waveform -a"))
        self.assertIn("400 200 1000 0 400 200", line)
        self.assertNotIn("-s test-device shell", self.events.read_text())


if __name__ == "__main__":
    unittest.main()
