import os
from pathlib import Path
import subprocess
import tempfile
import unittest

import signing_key as signing


class SigningTest(unittest.TestCase):
    def test_legacy_migration_preserves_certificate_and_is_repeatable(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            legacy = root / 'legacy.keystore'
            subprocess.run(['keytool', '-genkeypair', '-keystore', str(legacy), '-storepass', 'android',
                            '-keypass', 'android', '-alias', 'adbkeep', '-keyalg', 'RSA', '-dname', 'CN=Test'],
                           check=True, capture_output=True)
            legacy_pass = root / 'legacy.password'
            legacy_pass.write_text('android\n')
            before = signing.certificate('keytool', legacy, legacy_pass)
            directory = root / 'keys'
            key, password = signing.prepare(directory, legacy)
            self.assertEqual(signing.certificate('keytool', key, password), before)
            self.assertNotEqual(password.read_text().strip(), 'android')
            self.assertEqual(directory.stat().st_mode & 0o777, 0o700)
            self.assertEqual(key.stat().st_mode & 0o777, 0o600)
            self.assertEqual(password.stat().st_mode & 0o777, 0o600)
            saved = key.read_bytes(), password.read_bytes()
            signing.prepare(directory, legacy)
            self.assertEqual((key.read_bytes(), password.read_bytes()), saved)
            # Simulate the only transaction gap: new password + old legacy keystore.
            key.write_bytes(legacy.read_bytes())
            signing.prepare(directory, legacy)
            self.assertEqual(signing.certificate('keytool', key, password), before)

    def test_new_key_uses_private_random_password(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            key, password = signing.prepare(root / 'keys', root / 'missing')
            self.assertEqual(len(password.read_text().strip()), 64)
            self.assertTrue(signing.certificate('keytool', key, password))

    def test_invalid_existing_key_is_not_replaced(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            key = root / 'adb-keep.keystore'
            key.write_bytes(b'existing invalid key')
            with self.assertRaises(signing.SigningError): signing.prepare(root, root / 'missing')
            self.assertEqual(key.read_bytes(), b'existing invalid key')

if __name__ == '__main__': unittest.main()
