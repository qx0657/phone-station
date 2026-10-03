"""Create a release receipt from local artifacts; never queries a device or secrets."""
import argparse
import hashlib
import json
from pathlib import Path
import plistlib
import stat
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent


def digest(file):
    result = hashlib.sha256()
    with file.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(chunk)
    return result.hexdigest()


def artifact_receipt(artifact):
    if artifact.is_file():
        return {"artifact": artifact.name, "kind": "file", "size": artifact.stat().st_size,
                "sha256": digest(artifact)}
    if not artifact.is_dir() or artifact.suffix != ".app":
        raise ValueError("artifact must be a regular file or a complete .app directory")
    files = []
    for file in sorted(artifact.rglob("*")):
        mode = file.lstat().st_mode
        item = {"path": file.relative_to(artifact).as_posix(), "mode": stat.S_IMODE(mode)}
        if file.is_symlink():
            item.update(kind="symlink", target=str(file.readlink()))
        elif file.is_file():
            item.update(kind="file", size=file.stat().st_size, sha256=digest(file))
        elif file.is_dir():
            continue
        else:
            raise ValueError("unsupported bundle entry: " + item["path"])
        files.append(item)
    if not (artifact / "Contents/MacOS/PhoneStation").is_file():
        raise ValueError("Mac bundle is missing its main program")
    canonical = json.dumps(files, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()
    return {"artifact": artifact.name, "kind": "bundle-tree", "files": files,
            "size": sum(item.get("size", 0) for item in files),
            "sha256": hashlib.sha256(canonical).hexdigest()}


def source_versions():
    manifest = ET.parse(ROOT / "lib/android/AndroidManifest.xml").getroot()
    android = manifest.attrib["{http://schemas.android.com/apk/res/android}versionName"]
    mac = plistlib.loads((ROOT / "app/mac/Info.plist").read_bytes())
    return {"android": android, "mac": mac["CFBundleShortVersionString"] + " (" + mac["CFBundleVersion"] + ")", "relay": "2"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("component", choices=["relay", "android", "mac"])
    parser.add_argument("artifact", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    try:
        receipt = artifact_receipt(args.artifact)
    except ValueError as error:
        parser.error(str(error))
    if args.component == "mac" and receipt["kind"] != "bundle-tree":
        parser.error("Mac releases require the complete .app directory")
    if args.component != "mac" and receipt["kind"] != "file":
        parser.error("Android and relay releases require a regular file")
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    dirty = bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, text=True).strip())
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps({"schemaVersion": 1, "component": args.component,
        "sourceCommit": commit, "dirtyWorkingTree": dirty, "sourceVersions": source_versions(),
        **receipt,
        "deployed": False}, ensure_ascii=False, indent=2) + "\n")


if __name__ == "__main__":
    main()
