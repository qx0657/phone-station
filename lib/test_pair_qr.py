"""二维码配对的参数与连接脚本路径。不打开浏览器，也不连接手机。"""
from pathlib import Path
import unittest

import pair_qr

ROOT = Path(__file__).resolve().parent.parent


class PairQrTest(unittest.TestCase):
    def test_connect_script_is_beside_lib(self):
        self.assertEqual(pair_qr.connect_script(), ROOT / "scripts" / "connect.sh")
        self.assertTrue(pair_qr.connect_script().is_file())

    def test_tokens(self):
        self.assertTrue(pair_qr.tokens_ok("studio-abcdefghij", "0123456789ab"))
        self.assertFalse(pair_qr.tokens_ok("studio-short", "0123456789ab"))
        self.assertFalse(pair_qr.tokens_ok("studio-abcdefghij", "short"))
        self.assertFalse(pair_qr.tokens_ok("../studio-abcdefghij", "0123456789ab"))


if __name__ == "__main__":
    unittest.main()
