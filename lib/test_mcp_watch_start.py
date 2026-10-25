"""App-owned watch restores only the gateway, without phone operations."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent.parent


class WatchStartTest(unittest.TestCase):
    def run_watch(self, owned, fail=False):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "scripts").mkdir()
            (root / "lib").mkdir()
            shutil.copyfile(ROOT / "scripts/mcp.sh", root / "scripts/mcp.sh")
            (root / "lib/common.sh").write_text("adb_bin() { exit 91; }\n")
            gateway = root / "lib/phone-relay-gateway"
            gateway.write_text('''#!/usr/bin/env python3
import os, sys
with open(os.environ['EVENTS'], 'a') as stream:
    stream.write(sys.argv[1] + '\\n')
if sys.argv[1] == 'start':
    print('internal output must not enter NDJSON')
    if os.environ['FAIL'] == '1': sys.exit(12)
elif sys.argv[1] == 'watch-status':
    print('{"ready":true}')
else: sys.exit(92)
''')
            gateway.chmod(0o700)
            events = root / "events"
            env = dict(os.environ, EVENTS=str(events), FAIL=str(int(fail)))
            env.pop("PHONE_STATION_WATCH_STDIN", None)
            if owned:
                env["PHONE_STATION_WATCH_STDIN"] = "1"
            result = subprocess.run(["/bin/zsh", str(root / "scripts/mcp.sh"), "watch"],
                                    env=env, text=True, capture_output=True, timeout=5)
            return result, events.read_text().splitlines()

    def test_app_watch_starts_gateway_before_stream(self):
        result, events = self.run_watch(True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(events, ["start", "watch-status"])
        self.assertEqual(result.stdout, '{"ready":true}\n')

    def test_standalone_watch_remains_read_only(self):
        result, events = self.run_watch(False)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(events, ["watch-status"])

    def test_start_failure_exits_for_app_retry(self):
        result, events = self.run_watch(True, fail=True)
        self.assertEqual(result.returncode, 12)
        self.assertEqual(events, ["start"])
        self.assertEqual(result.stdout, "")


if __name__ == "__main__":
    unittest.main()
