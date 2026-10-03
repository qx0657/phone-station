"""Stop confirmation checks without contacting a phone."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent.parent

class StopTest(unittest.TestCase):
    def run_stop(self, case):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / 'scripts').mkdir()
            (root / 'lib').mkdir()
            shutil.copyfile(ROOT / 'scripts/mcp.sh', root / 'scripts/mcp.sh')
            (root / 'lib/common.sh').write_text('adb_bin() { print -r -- "$TEST_ADB"; }\nonline_serial() { print device; }\n')
            adb = root / 'adb'
            adb.write_text('''#!/usr/bin/env python3
import os, sys
args = sys.argv[1:]
case = os.environ['CASE']
if 'broadcast' in args:
    if case == 'failed': sys.exit(1)
    print('Broadcast completed: result=' + ('0' if case == 'rejected' else '1'))
elif 'shell' in args and any('settings get' in a for a in args):
    print('1\\n1' if case == 'enabled' else '0\\n0')
elif 'dumpsys' in args:
    if case == 'disconnected': sys.exit(1)
    print('ACTIVITY MANAGER SERVICES')
    if case == 'active': print('ServiceRecord{123 .FileMcpService}')
elif 'forward' in args:
    open(os.environ['EVENTS'], 'a').write('forward\\n')
else: sys.exit(2)
''')
            gateway = root / 'lib/phone-relay-gateway'
            gateway.write_text('''#!/usr/bin/env python3
import os, sys
open(os.environ['EVENTS'], 'a').write(sys.argv[1] + '\\n')
if os.environ['CASE'] == 'cleanup-failed': sys.exit(1)
''')
            for file in (adb, gateway): file.chmod(0o700)
            events = root / 'events'
            env = dict(os.environ, TEST_ADB=str(adb), CASE=case, EVENTS=str(events))
            result = subprocess.run(['/bin/zsh', str(root / 'scripts/mcp.sh'), 'stop'], env=env, text=True, capture_output=True, timeout=8)
            recorded = events.read_text() if events.exists() else ''
            return result, recorded

    def test_no_false_success_and_keep_route_on_unconfirmed_stop(self):
        for case in ('failed', 'rejected', 'enabled', 'disconnected', 'active'):
            with self.subTest(case=case):
                result, events = self.run_stop(case)
                self.assertNotEqual(result.returncode, 0)
                self.assertNotIn('已停止手机 MCP 与远程通道。', result.stdout)
                self.assertEqual(events, '')

    def test_confirmed_stop_clears_route(self):
        result, events = self.run_stop('ok')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(events.splitlines(), ['forward', 'clear-local'])

    def test_cleanup_failure_is_explicit(self):
        result, _ = self.run_stop('cleanup-failed')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('手机服务已停止', result.stderr)

if __name__ == '__main__': unittest.main()
