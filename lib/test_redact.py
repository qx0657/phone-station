import unittest

from redact import redact


class RedactTest(unittest.TestCase):
    def test_hides_bearer_fields_and_pins(self):
        pin = "ab" * 32
        raw = (
            'Authorization: Bearer phone-token\n'
            '{"token":"phone-token","pin":"%s","name":"studio"}\n'
            "serial usb-1 stays\n"
        ) % pin
        cleaned = redact(raw)
        self.assertNotIn("phone-token", cleaned)
        self.assertNotIn(pin, cleaned)
        self.assertIn("usb-1", cleaned)
        self.assertIn('"name":"studio"', cleaned)
        self.assertIn("[redacted]", cleaned)

    def test_leaves_short_hex(self):
        self.assertEqual(redact("cursor abcdef"), "cursor abcdef")


if __name__ == "__main__":
    unittest.main()
