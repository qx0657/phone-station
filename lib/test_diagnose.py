import tempfile
import unittest
from pathlib import Path

from diagnose import gateway_excerpt, write_bundle


class DiagnoseTest(unittest.TestCase):
    def test_bundle_redacts_and_rejects_paths(self):
        pin = "cd" * 32
        with tempfile.TemporaryDirectory() as raw:
            dest = Path(raw) / "bundle"
            write_bundle(dest, {
                "notes.txt": 'Authorization: Bearer phone-token\n{"pin":"%s"}\nserial usb-1\n' % pin,
            })
            text = (dest / "notes.txt").read_text()
            self.assertNotIn("phone-token", text)
            self.assertNotIn(pin, text)
            self.assertIn("usb-1", text)
            self.assertEqual((dest / "notes.txt").stat().st_mode & 0o777, 0o600)
            with self.assertRaises(ValueError):
                write_bundle(dest, {"../x.txt": "nope"})

    def test_excerpt_keeps_the_tail(self):
        with tempfile.TemporaryDirectory() as raw:
            path = Path(raw) / "remote-gateway.log"
            path.write_bytes(b"a" * 100 + b"TAIL")
            self.assertEqual(gateway_excerpt(path, limit=4), "TAIL")


if __name__ == "__main__":
    unittest.main()
