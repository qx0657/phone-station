import json
import platform
import subprocess
import tempfile
import unittest
from pathlib import Path

from build_keychain import build


class KeychainBuildTest(unittest.TestCase):
    def test_reuse_changes_and_corruption(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source, binary, compiler = root / "main.swift", root / "helper", root / "compiler"
            source.write_text("unchanged source")
            calls = root / "calls.json"
            compiler.write_text("#!/usr/bin/env python3\nimport sys,json\nfrom pathlib import Path\n"
                                "args=sys.argv[1:]\nPath(args[args.index('-o')+1]).write_bytes(b'compiled-helper')\n"
                                f"Path({str(calls)!r}).write_text(json.dumps(args))\n")
            compiler.chmod(0o755)
            self.assertTrue(build(source, binary, compiler, "arm64-apple-macosx13.0", root))
            arguments = json.loads(calls.read_text())
            self.assertEqual(arguments[arguments.index("-module-name") + 1], "PhoneRelayKeychain")
            self.assertEqual(Path(arguments[arguments.index("-o") + 1]).name, "phone-relay-keychain")
            calls.unlink()
            self.assertFalse(build(source, binary, compiler, "arm64-apple-macosx13.0", root))
            self.assertFalse(calls.exists())
            source.write_text("changed source")
            self.assertTrue(build(source, binary, compiler, "arm64-apple-macosx13.0", root))
            binary.write_bytes(b"tampered")
            self.assertTrue(build(source, binary, compiler, "arm64-apple-macosx13.0", root))
            self.assertTrue(build(source, binary, compiler, "x86_64-apple-macosx13.0", root))


    @unittest.skipUnless(platform.system() == "Darwin", "Mac linker signature")
    def test_compiler_identity_is_stable_across_build_directories(self):
        compiler = Path(subprocess.check_output(["xcrun", "--find", "swiftc"], text=True).strip())
        sdk = Path(subprocess.check_output(["xcrun", "--show-sdk-path"], text=True).strip())
        source = Path(__file__).parent / "remote-gateway/keychain/main.swift"
        target = platform.machine() + "-apple-macosx13.0"
        with tempfile.TemporaryDirectory() as directory:
            first, second = Path(directory) / "first/helper", Path(directory) / "second/helper"
            build(source, first, compiler, target, sdk)
            build(source, second, compiler, target, sdk)
            self.assertEqual(first.read_bytes(), second.read_bytes())


if __name__ == "__main__":
    unittest.main()
