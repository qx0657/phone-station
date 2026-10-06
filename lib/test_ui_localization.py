"""Validate the shared display catalog without starting an app or contacting a device."""
import json
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parent.parent


class DisplayCatalogTests(unittest.TestCase):
    def test_complete_unambiguous_templates(self):
        def unique(pairs):
            result = {}
            for key, value in pairs:
                self.assertNotIn(key, result, f"Duplicate display key: {key}")
                result[key] = value
            return result

        catalog = json.loads((ROOT / "app/i18n/en.json").read_text(), object_pairs_hook=unique)
        self.assertGreater(len(catalog), 900)
        for source, english in catalog.items():
            self.assertTrue(source and english, source)
            slots = set(re.findall(r"\{(\d+)\}", source))
            self.assertEqual(slots, set(re.findall(r"\{(\d+)\}", english)), source)
            self.assertEqual(slots, {str(i) for i in range(len(slots))}, source)

    def test_data_paths_are_not_translated(self):
        catalog = json.loads((ROOT / "app/i18n/en.json").read_text())
        self.assertEqual(catalog["Pictures/手机工位"], "Pictures/手机工位")
        for source, english in catalog.items():
            if "Download/手机工位/" in source:
                self.assertIn("Download/手机工位/", english)


if __name__ == "__main__":
    unittest.main()
