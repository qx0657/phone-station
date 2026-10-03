"""Keep an unchanged ad-hoc keychain helper's approved binary identity stable."""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile


def stamp(source, binary, target):
    return {"schemaVersion": 1, "sourceSha256": hashlib.sha256(source.read_bytes()).hexdigest(),
            "target": target, "binarySha256": hashlib.sha256(binary.read_bytes()).hexdigest()}


def build(source, binary, compiler, target, sdk):
    metadata = Path(str(binary) + ".build.json")
    binary.parent.mkdir(parents=True, exist_ok=True)
    with Path(str(binary) + ".build.lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        if binary.is_file() and not binary.is_symlink() and os.access(binary, os.X_OK):
            try:
                if json.loads(metadata.read_text()) == stamp(source, binary, target):
                    return False
            except (OSError, ValueError):
                pass
        with tempfile.TemporaryDirectory(prefix=".keychain-build-", dir=binary.parent) as directory:
            # Output basename and module name must not contain a PID. Both enter
            # the linker signature, whose cdhash is the login-keychain identity.
            staged = Path(directory) / "phone-relay-keychain"
            subprocess.run([str(compiler), "-O", "-module-name", "PhoneRelayKeychain",
                            "-target", target, "-sdk", str(sdk), "-framework", "Security",
                            "-o", str(staged), str(source)], check=True)
            staged.chmod(0o755)
            value = stamp(source, staged, target)
            staged_metadata = Path(directory) / "metadata.json"
            staged_metadata.write_text(json.dumps(value, sort_keys=True) + "\n")
            # Atomic replacement also permits an already running repository CLI.
            os.replace(staged, binary)
            os.replace(staged_metadata, metadata)
        return True


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("source", "binary", "compiler", "sdk"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--target", required=True)
    args = parser.parse_args()
    compiled = build(args.source, args.binary, args.compiler, args.target, args.sdk)
    print("钥匙串辅助程序已构建。" if compiled else "复用源码与摘要均未变化的钥匙串辅助程序。")


if __name__ == "__main__":
    main()
