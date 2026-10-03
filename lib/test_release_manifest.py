import tempfile
import unittest
from pathlib import Path

from release_manifest import artifact_receipt


class BundleReceiptTest(unittest.TestCase):
    def test_bundled_gateway_and_permissions_are_part_of_receipt(self):
        with tempfile.TemporaryDirectory() as directory:
            app = Path(directory) / "手机工位.app"
            main = app / "Contents/MacOS/PhoneStation"
            gateway = app / "Contents/Resources/lib/phone-relay-gateway"
            for file in (main, gateway):
                file.parent.mkdir(parents=True, exist_ok=True)
                file.write_bytes(b"first")
            before = artifact_receipt(app)
            self.assertEqual(len(before["files"]), 2)
            self.assertEqual(before["sha256"], artifact_receipt(app)["sha256"])
            gateway.write_bytes(b"second")
            changed = artifact_receipt(app)
            self.assertNotEqual(before["sha256"], changed["sha256"])
            gateway.chmod(0o700)
            self.assertNotEqual(changed["sha256"], artifact_receipt(app)["sha256"])


if __name__ == "__main__":
    unittest.main()
