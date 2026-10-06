import plistlib
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import archive_mac_app as archive
from release_manifest import artifact_receipt


class ArchiveMacAppTest(unittest.TestCase):
    def fixture(self, root):
        app = root / ".手机工位-old.app"
        mac = app / "Contents/MacOS"
        mac.mkdir(parents=True)
        (mac / "PhoneStation").write_bytes(b"test executable")
        (mac / "PhoneStation").chmod(0o755)
        (mac / "alias").symlink_to("PhoneStation")
        (app / "Contents/Info.plist").write_bytes(plistlib.dumps({
            "CFBundleIdentifier": archive.BUNDLE_ID, "CFBundleExecutable": "PhoneStation"}))
        return app

    @unittest.skipUnless(Path("/usr/bin/ditto").exists(), "Mac bundle archiver")
    def test_archive_round_trip_preserves_bundle(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            app = self.fixture(root)
            expected = artifact_receipt(app)
            packed = archive.archive(app, root / "backups")
            self.assertFalse(app.exists())
            self.assertEqual(packed.stat().st_mode & 0o777, 0o600)
            subprocess.run(["ditto", "-x", "-k", str(packed), str(root / "restore")], check=True)
            self.assertEqual(expected, artifact_receipt(root / "restore" / app.name))

    def test_refuses_running_and_unrelated_apps(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            app = self.fixture(root)
            with patch.object(archive.subprocess, "check_output", return_value=str(app.resolve() / "Contents/MacOS/PhoneStation") + "\n"):
                with self.assertRaisesRegex(ValueError, "仍在运行"):
                    archive.archive(app, root / "backups")
            self.assertTrue(app.exists())
            (app / "Contents/Info.plist").write_bytes(plistlib.dumps({"CFBundleIdentifier": "other"}))
            with self.assertRaisesRegex(ValueError, "不是手机工位"):
                archive.archive(app, root / "backups")
            self.assertTrue(app.exists())

    @unittest.skipUnless(Path("/usr/bin/ditto").exists(), "Mac bundle archiver")
    def test_verification_failure_keeps_original(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            app = self.fixture(root)
            before = artifact_receipt(app)
            with patch.object(archive, "artifact_receipt", side_effect=[before, {"sha256": "bad"}]):
                with self.assertRaisesRegex(ValueError, "还原校验失败"):
                    archive.archive(app, root / "backups")
            self.assertEqual(before, artifact_receipt(app))
            self.assertEqual(list((root / "backups").iterdir()), [])


if __name__ == "__main__":
    unittest.main()
