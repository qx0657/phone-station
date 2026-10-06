"""校验并压缩旧 Mac App；确认可恢复后才移除原目录。"""
import argparse
from datetime import datetime
import os
from pathlib import Path
import plistlib
import shutil
import subprocess
import tempfile
import uuid

from release_manifest import artifact_receipt

BUNDLE_ID = "com.qx0657.phonestation"


def validate(app):
    if app.is_symlink() or not app.is_dir() or app.suffix != ".app":
        raise ValueError("需要真实的 .app 目录：" + str(app))
    with (app / "Contents/Info.plist").open("rb") as stream:
        info = plistlib.load(stream)
    if info.get("CFBundleIdentifier") != BUNDLE_ID or info.get("CFBundleExecutable") != "PhoneStation":
        raise ValueError("不是手机工位应用：" + str(app))


def require_stopped(app):
    # comm deliberately excludes command arguments, which can contain credentials.
    output = subprocess.check_output(["ps", "-axo", "comm="], text=True, errors="replace")
    prefix = str(app.resolve()) + "/"
    if any(line.strip().startswith(prefix) for line in output.splitlines()):
        raise ValueError("应用或随包进程仍在运行，请先退出：" + str(app))


def archive(app, destination):
    validate(app)
    require_stopped(app)
    before = artifact_receipt(app)
    destination.mkdir(parents=True, exist_ok=True, mode=0o700)
    if destination.resolve() == app.resolve() or app.resolve() in destination.resolve().parents:
        raise ValueError("归档目录不能位于原应用内")
    name = app.stem.lstrip(".") + "-" + datetime.now().strftime("%Y%m%d-%H%M%S") + "-" + uuid.uuid4().hex[:8] + ".zip"
    final = destination / name
    with tempfile.TemporaryDirectory(prefix=".archive-", dir=destination) as temporary:
        work = Path(temporary)
        packed = work / "app.zip"
        subprocess.run(["ditto", "-c", "-k", "--sequesterRsrc", "--keepParent", str(app), str(packed)], check=True)
        extracted = work / "verify"
        subprocess.run(["ditto", "-x", "-k", str(packed), str(extracted)], check=True)
        after = artifact_receipt(extracted / app.name)
        if before != after:
            raise ValueError("归档还原校验失败，原应用已保留")
        require_stopped(app)
        if before != artifact_receipt(app):
            raise ValueError("归档期间原应用发生变化，原应用已保留")
        os.chmod(packed, 0o600)
        packed.rename(final)
        # A durable verified archive must exist before deleting the duplicate.
        with final.open("rb") as stream:
            os.fsync(stream.fileno())
        shutil.rmtree(app)
    return final


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apps", type=Path, nargs="+", help="已退出的手机工位 .app 路径；不匹配或扫描其他应用")
    parser.add_argument("--destination", type=Path,
                        default=Path.home() / "Library/Application Support/Phone Station/Backups",
                        help="压缩包目录，默认 ~/Library/Application Support/Phone Station/Backups")
    args = parser.parse_args()
    for app in args.apps:
        try:
            print("已校验归档：" + str(archive(app.absolute(), args.destination.absolute())), flush=True)
        except (OSError, ValueError, subprocess.SubprocessError) as error:
            parser.exit(1, str(error) + "\n")


if __name__ == "__main__":
    main()
