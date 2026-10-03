import contextlib
import io
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest.mock import patch

import cleanup


def shot(index):
    return f"Screenshot_20261003_12000{index}_test_Activity.jpg"


def row(index):
    return {"name": shot(index), "bytes": 100, "mtime": index,
            "w": 1080, "h": 2400, "gray16": "00" * 256}


class CleanupTest(unittest.TestCase):
    def test_similarity_chain_cannot_delete_against_different_keeper(self):
        values = {shot(1): 0, shot(2): 0.009, shot(3): 0.018}
        with patch.object(cleanup, "content_image", side_effect=lambda p: values[p.name]), \
                patch.object(cleanup, "mean_diff", side_effect=lambda a, b: abs(a - b)):
            deleted = cleanup.confirm_dupes([row(1), row(2), row(3)], Path("unused"))
        self.assertEqual(deleted, [(shot(2), shot(3), 100)])

    def test_decode_failure_is_neither_deleted_nor_a_keeper(self):
        def decode(file):
            if file.name == shot(3):
                raise OSError("corrupt JPEG")
            return 1
        with patch.object(cleanup, "content_image", side_effect=decode), \
                patch.object(cleanup, "mean_diff", return_value=0):
            self.assertEqual(cleanup.confirm_dupes([row(1), row(2), row(3)], Path("unused")),
                             [(shot(1), shot(2), 100)])

    def test_unknown_names_and_non_jpegs_are_excluded(self):
        rows = [{**row(i), "name": name} for i, name in enumerate([
            "Screenshot_20261003_120001.jpg", "photo.jpg", "../outside.jpg",
            "Screenshot_20261003_120001_x/evil.jpg", "Screenshot_20261003_120001_x.mp4"])]
        self.assertEqual(cleanup.candidate_names(rows), [])
        with contextlib.redirect_stdout(io.StringIO()) as output:
            cleanup.list_class([{"class": "游戏", "name": "clip.mp4", "full": "/clip.mp4"}], "游戏")
        self.assertEqual(output.getvalue(), "")

    def test_archive_rejects_links_traversal_duplicates_and_wrong_sizes(self):
        for case in ("link", "traversal", "duplicate", "size", "missing"):
            with self.subTest(case=case), tempfile.TemporaryDirectory() as temp:
                root = Path(temp)
                archive = root / "images.tar"
                with tarfile.open(archive, "w") as bundle:
                    info = tarfile.TarInfo("../outside.jpg" if case == "traversal" else shot(1))
                    info.size = 0 if case == "size" else 1
                    if case == "link":
                        info.type = tarfile.SYMTYPE
                        info.linkname = "../outside.jpg"
                    if case != "missing":
                        bundle.addfile(info, io.BytesIO(b"x"))
                    if case == "duplicate":
                        bundle.addfile(info, io.BytesIO(b"x"))
                out = root / "out"
                out.mkdir()
                with self.assertRaises(ValueError):
                    cleanup.extract_candidates(archive, out, {shot(1): 1})
                self.assertFalse((root / "outside.jpg").exists())


if __name__ == "__main__":
    unittest.main()
