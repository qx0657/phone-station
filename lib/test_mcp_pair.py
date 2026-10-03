"""Exercise the pairing entry point without adb, keychain access, or a relay."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parent.parent
GATEWAY = Path(os.environ.get("PHONE_STATION_TEST_GATEWAY", ROOT / "build/.phone-station/phone-relay-gateway"))
PIN = "a" * 64
PHONE_TOKEN = "b" * 64
DESKTOP_TOKEN = "c" * 64


class PairScriptTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "scripts").mkdir()
        (self.root / "lib").mkdir()
        shutil.copyfile(ROOT / "scripts/mcp.sh", self.root / "scripts/mcp.sh")
        self.events = self.root / "events.jsonl"
        self.env = dict(os.environ, TEST_EVENTS=str(self.events), TEST_GATEWAY=str(GATEWAY),
                        TEST_ADB=str(self.root / "adb"), TEST_PAIR_RESULT="1", TEST_CAPABILITY_RESULT="2")
        (self.root / "lib/common.sh").write_text(
            'adb_bin() { print -r -- "$TEST_ADB"; }\n'
            'online_serial() { print -r -- test-device; }\n')
        self.executable(self.root / "scripts/connect.sh", '#!/bin/zsh\nexit 0\n')
        self.executable(self.root / "adb", '''#!/usr/bin/env python3
import json, os, sys
args = sys.argv[1:]
with open(os.environ['TEST_EVENTS'], 'a') as f:
    f.write(json.dumps({'adb': args}) + '\\n')
if 'settings' in args:
    print('1')  # Old profile remains enabled even when new pairing is rejected.
elif 'capabilities' in args:
    print('Broadcast completed: result=' + os.environ['TEST_CAPABILITY_RESULT'])
else:
    token = sys.stdin.read().strip()
    assert token == 'b' * 64
    print('Broadcast completed: result=' + os.environ['TEST_PAIR_RESULT'])
''')
        self.executable(self.root / "lib/phone-relay-gateway", '''#!/usr/bin/env python3
import json, os, subprocess, sys
command = sys.argv[1]
if command == 'validate-endpoint':
    sys.exit(subprocess.call([os.environ['TEST_GATEWAY'], *sys.argv[1:]]))
with open(os.environ['TEST_EVENTS'], 'a') as f:
    f.write(json.dumps({'gateway': sys.argv[1:]}) + '\\n')
if command == 'configure':
    value = json.load(sys.stdin)
    assert value['token'] == 'c' * 64
    if os.environ.get('TEST_CONFIGURE_FAIL') == '1':
        print('cannot store remote credential', file=sys.stderr)
        sys.exit(1)
    with open(os.environ['TEST_EVENTS'], 'a') as f:
        f.write(json.dumps({'configuredEndpoint': value['endpoint'], 'pin': value['pin']}) + '\\n')
elif command == 'status':
    print('relay=online')
elif command == 'profile':
    print(json.dumps({'endpoint': '', 'pin': '', 'configured': False}))
elif command not in ('start', 'unpair'):
    sys.exit(2)
''')

    @staticmethod
    def executable(file, content):
        file.write_text(content)
        file.chmod(0o700)

    def run_script(self, *args, input_text=""):
        result = subprocess.run(["/bin/zsh", str(self.root / "scripts/mcp.sh"), *args],
                                input=input_text, text=True, capture_output=True,
                                env=self.env, timeout=5)
        self.assertNotIn(PHONE_TOKEN, result.stdout + result.stderr)
        self.assertNotIn(DESKTOP_TOKEN, result.stdout + result.stderr)
        return result

    def recorded(self):
        return [json.loads(line) for line in self.events.read_text().splitlines()] if self.events.exists() else []

    def test_custom_address_and_stdin_pair_after_phone_ack(self):
        endpoint = "https://[2001:db8::1]:24443/station/"
        result = self.run_script("pair", endpoint, PIN, "--stdin",
                                 input_text=f"{PHONE_TOKEN}\n{DESKTOP_TOKEN}\n")
        self.assertEqual(result.returncode, 0, result.stderr)
        events = self.recorded()
        self.assertIn("capabilities", events[0]["adb"])
        self.assertIn("--es endpoint 'https://[2001:db8::1]:24443/station'", events[1]["adb"][-1])
        self.assertEqual(events[3], {"gateway": ["configure"]})
        self.assertEqual(events[4]["configuredEndpoint"], endpoint.rstrip("/"))
        self.assertNotIn(PHONE_TOKEN, json.dumps(events))
        self.assertNotIn(DESKTOP_TOKEN, json.dumps(events))

    def test_old_enabled_flag_cannot_confirm_rejected_new_profile(self):
        for code in ("0", "10"):
            with self.subTest(result=code):
                self.env["TEST_PAIR_RESULT"] = code
                self.events.unlink(missing_ok=True)
                result = self.run_script("pair", "https://relay.example.com", PIN, "--stdin",
                                         input_text=f"{PHONE_TOKEN}\n{DESKTOP_TOKEN}\n")
                self.assertEqual(result.returncode, 1)
                self.assertTrue(all("gateway" not in event for event in self.recorded()))

    def test_old_phone_is_checked_before_sending_any_credentials(self):
        self.env["TEST_CAPABILITY_RESULT"] = "0"
        result = self.run_script("pair", "https://relay.example.com", PIN, "--stdin",
                                 input_text=f"{PHONE_TOKEN}\n{DESKTOP_TOKEN}\n")
        self.assertEqual(result.returncode, 1)
        events = self.recorded()
        self.assertEqual(len(events), 1)
        self.assertIn("capabilities", events[0]["adb"])
        self.assertNotIn("pair", events[0]["adb"])

    def test_desktop_failure_explains_partial_pairing_and_retry(self):
        self.env["TEST_CONFIGURE_FAIL"] = "1"
        result = self.run_script("pair", "https://relay.example.com", PIN, "--stdin",
                                 input_text=f"{PHONE_TOKEN}\n{DESKTOP_TOKEN}\n")
        self.assertEqual(result.returncode, 1)
        self.assertIn("手机已保存新中继", result.stderr)
        self.assertIn("电脑仍保留原配置", result.stderr)
        self.assertIn("重新配对", result.stderr)
        events = self.recorded()
        self.assertEqual(events[-1], {"gateway": ["configure"]})
        self.assertTrue(all(event.get("gateway") != ["start"] for event in events))

    def test_invalid_credentials_do_not_reach_adb(self):
        result = self.run_script("pair", "https://relay.example.com", PIN, "--stdin",
                                 input_text=f"{PHONE_TOKEN}\n{PHONE_TOKEN}\n")
        self.assertEqual(result.returncode, 2)
        self.assertEqual(self.recorded(), [])

    def test_invalid_address_does_not_reach_adb(self):
        result = self.run_script("pair", "https://relay.example.com?token=x", PIN, "--stdin")
        self.assertEqual(result.returncode, 2)
        self.assertEqual(self.recorded(), [])

    def test_missing_stdin_fails_without_prompting(self):
        result = self.run_script("pair", "https://relay.example.com", PIN, "--stdin",
                                 input_text=f"{PHONE_TOKEN}\n")
        self.assertEqual(result.returncode, 2)
        self.assertEqual(self.recorded(), [])

    def test_profile_is_read_only(self):
        result = self.run_script("profile")
        self.assertEqual(result.returncode, 0)
        self.assertEqual(json.loads(result.stdout), {"endpoint": "", "pin": "", "configured": False})
        self.assertEqual(self.recorded(), [{"gateway": ["profile"]}])

    def test_desktop_configuration_and_removal_never_use_adb(self):
        result = self.run_script("desktop", "https://relay.example.com/station/", PIN, "--stdin",
                                 input_text=DESKTOP_TOKEN + "\n")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("此 Mac", result.stdout)
        self.assertTrue(all("adb" not in event for event in self.recorded()))
        self.assertEqual(self.recorded()[-1], {"gateway": ["start"]})
        self.events.unlink()
        result = self.run_script("forget-desktop")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.recorded(), [{"gateway": ["unpair"]}])

    def test_desktop_invalid_or_failed_save_does_not_start_or_use_adb(self):
        for credential in ("invalid\n", ""):
            result = self.run_script("desktop", "https://relay.example.com", PIN, "--stdin",
                                     input_text=credential)
            self.assertEqual(result.returncode, 2)
            self.assertEqual(self.recorded(), [])
        self.env["TEST_CONFIGURE_FAIL"] = "1"
        result = self.run_script("desktop", "https://relay.example.com", PIN, "--stdin",
                                 input_text=DESKTOP_TOKEN + "\n")
        self.assertEqual(result.returncode, 1)
        self.assertEqual(self.recorded(), [{"gateway": ["configure"]}])


class GatewayConfigTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.gateway = self.root / "phone-relay-gateway"
        shutil.copyfile(GATEWAY, self.gateway)
        self.gateway.chmod(0o700)
        self.store = self.root / "fake-keychain.json"
        self.store.write_text("{}")
        self.env = dict(os.environ, HOME=str(self.root), XDG_CONFIG_HOME=str(self.root / "config"),
                        TEST_KEYCHAIN=str(self.store))
        PairScriptTest.executable(self.root / "phone-relay-keychain", '''#!/usr/bin/env python3
import json, os, pathlib, sys
store = pathlib.Path(os.environ['TEST_KEYCHAIN'])
values = json.loads(store.read_text())
action = sys.argv[1]
profile = sys.argv[2] if len(sys.argv) == 3 else 'legacy'
if action == 'set':
    if os.environ.get('TEST_REJECT_TOKEN') == '1':
        sys.exit(1)
    values[profile] = sys.stdin.read().strip()
elif action == 'delete':
    values.pop(profile, None)
else:
    print(values[profile])
store.write_text(json.dumps(values))
''')

    def configure(self, endpoint, token):
        return subprocess.run([str(self.gateway), "configure"], env=self.env, text=True,
                              input=json.dumps({"endpoint": endpoint, "pin": PIN, "token": token}),
                              capture_output=True, timeout=5)

    def profile(self):
        result = subprocess.run([str(self.gateway), "profile"], env=self.env, text=True,
                                capture_output=True, check=True, timeout=5)
        self.assertNotIn(DESKTOP_TOKEN, result.stdout)
        return json.loads(result.stdout)

    def test_new_profile_is_opt_in_and_credential_rotation_uses_new_key(self):
        self.assertEqual(self.profile(), {"endpoint": "", "pin": "", "configured": False})
        self.assertEqual(self.configure("https://relay.example.com/station/", DESKTOP_TOKEN).returncode, 0)
        first = json.loads(self.store.read_text())
        self.assertEqual(len(first), 1)
        self.assertEqual(next(iter(first.values())), DESKTOP_TOKEN)
        self.assertEqual(self.profile()["endpoint"], "https://relay.example.com/station")
        new_token = "d" * 64
        self.assertEqual(self.configure("https://other.example.com", new_token).returncode, 0)
        second = json.loads(self.store.read_text())
        self.assertEqual(len(second), 1)
        self.assertEqual(next(iter(second.values())), new_token)
        self.assertTrue(set(first).isdisjoint(second))

    def test_failed_credential_write_preserves_previous_profile(self):
        self.assertEqual(self.configure("https://relay.example.com", DESKTOP_TOKEN).returncode, 0)
        saved_profile, saved_tokens = self.profile(), self.store.read_text()
        self.env["TEST_REJECT_TOKEN"] = "1"
        self.assertNotEqual(self.configure("https://other.example.com", "d" * 64).returncode, 0)
        self.assertEqual(self.profile(), saved_profile)
        self.assertEqual(json.loads(self.store.read_text()), json.loads(saved_tokens))


if __name__ == "__main__":
    unittest.main()
