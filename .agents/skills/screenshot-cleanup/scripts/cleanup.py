#!/usr/bin/env python3
# 图库截图技能自带的脚本。从仓库根目录运行，不要抄进 scripts/。
# 类和阈值的原因在 docs/screenshot-cleanup.md。

import re
import shutil
import subprocess
import sys
import tarfile
from collections import defaultdict
from pathlib import Path

SHOT_DIR = "/storage/emulated/0/Pictures/Screenshots"
# 平均绝对差。0.04 时套餐次数和钱包余额已经变了，所以停在 0.01。
MEAN_MAX = 0.01
# 16×16 指纹只用来缩小候选。不能单独删除。
GRAY_MAE_MAX = 2.0

# 一条只进最先对上的类。行情是整段界面名相等，其余是子串。
CLASSES = (
    ("游戏", ("tmgp_cf_AFMain", "pubgmhd_Game", "netease_race_Game"), False),
    ("直播", ("LivePlay", "LiveSlide", "LiveDummy", "TaoLiveVideo", "FinderShareFeed", "FinderHomeAffinity", "FinderLive"), False),
    ("短视频", ("gifmaker_PhotoDetail", "aweme_DetailActivity", "aweme_UltraDetail"), False),
    ("打卡页", ("lark_MiniappTabActivity",), False),
    ("行情", ("com_hexin_plat_android_Hexin",), True),
)

NAME_RE = re.compile(r"^(?:Screenshot|IMG)_(\d{8})_(\d{6})(?:_(.+))?\.jpe?g$", re.I)


def screenshot_name(name):
    return (not any(c in name for c in "/\\\r\n\t\0")
            and NAME_RE.fullmatch(name) is not None)


def activity_of(name):
    match = NAME_RE.match(name)
    if not match:
        return ""
    return match.group(3) or ""


def class_of(name):
    activity = activity_of(name)
    for label, parts, exact in CLASSES:
        if exact:
            if activity in parts:
                return label
        elif any(part in activity for part in parts):
            return label
    return ""


def read_listing(lines):
    rows = []
    for line in lines:
        line = line.rstrip("\n")
        if not line or line.startswith("name\t"):
            continue
        parts = line.split("\t", 2)
        if len(parts) != 3:
            continue
        size_text, mtime_text, full = parts
        name = full.rsplit("/", 1)[-1]
        rows.append({
            "name": name,
            "full": full if full.startswith("/") else SHOT_DIR + "/" + name,
            "bytes": int(size_text),
            "mtime": int(mtime_text),
            "class": class_of(name),
            "video": name.lower().endswith(".mp4"),
        })
    return rows


def fmt_size(nbytes):
    if nbytes >= 1024 ** 3:
        return f"{nbytes / 1024 ** 3:.1f}GB"
    return f"{nbytes / 1024 ** 2:.0f}MB"


def report(rows):
    jpg = [row for row in rows if not row["video"] and row["name"].lower().endswith((".jpg", ".jpeg"))]
    videos = [row for row in rows if row["video"]]
    print(f"截图 {len(jpg)} 张，{fmt_size(sum(row['bytes'] for row in jpg))}")
    print(f"录屏 {len(videos)} 段，{fmt_size(sum(row['bytes'] for row in videos))}。不在删除名单里。")
    print()
    print("可以删")
    total_n = 0
    total_b = 0
    for label, _, _ in CLASSES:
        picked = [row for row in jpg if row["class"] == label]
        total_n += len(picked)
        total_b += sum(row["bytes"] for row in picked)
        print(f"{label}\t{len(picked)} 张\t{fmt_size(sum(row['bytes'] for row in picked))}")
    print(f"合计\t{total_n} 张\t{fmt_size(total_b)}")
    print()
    print("商品详情、订单、账单、车票和聊天不在这五类里。")
    print("名单: python3 .agents/skills/screenshot-cleanup/scripts/cleanup.py list 游戏")


def list_class(rows, label):
    names = [item[0] for item in CLASSES]
    if label not in names:
        print(f"没有这个类: {label}", file=sys.stderr)
        print("类: " + " ".join(names), file=sys.stderr)
        return 2
    for row in rows:
        if row["class"] == label and screenshot_name(row["name"]):
            print(row["full"])
    return 0


def gray_bytes(text):
    if len(text) != 512:
        return None
    try:
        return bytes.fromhex(text)
    except ValueError:
        return None


def gray_close(left, right):
    limit = int(GRAY_MAE_MAX * 256)
    total = 0
    for a, b in zip(left, right):
        total += abs(a - b)
        if total > limit:
            return False
    return True


def candidate_names(hash_rows):
    groups = defaultdict(list)
    usable = []
    for row in hash_rows:
        if not screenshot_name(row["name"]) or not activity_of(row["name"]):
            continue
        gray = gray_bytes(row["gray16"])
        if gray is None:
            continue
        row["gray"] = gray
        row["activity"] = activity_of(row["name"])
        usable.append(row)
        groups[(row["w"], row["h"], row["activity"])].append(row)
    names = set()
    for rows in groups.values():
        if len(rows) < 2:
            continue
        for i, left in enumerate(rows):
            for right in rows[i + 1:]:
                if gray_close(left["gray"], right["gray"]):
                    names.add(left["name"])
                    names.add(right["name"])
    return sorted(names)


def read_hash_tsv(lines):
    rows = []
    for line in lines:
        parts = line.rstrip("\n").split("\t")
        if len(parts) < 6 or parts[0] == "name":
            continue
        if parts[5].startswith("ERR"):
            continue
        rows.append({
            "name": parts[0],
            "bytes": int(parts[1]),
            "mtime": int(parts[2]),
            "w": int(parts[3]),
            "h": int(parts[4]),
            "gray16": parts[5],
        })
    return rows


def content_image(file_path):
    from PIL import Image
    with Image.open(file_path) as source:
        image = source.convert("L")
    width, height = image.size
    if height > width:
        image = image.crop((0, int(height * 0.05), width, int(height * 0.97)))
    else:
        image = image.crop((int(width * 0.04), 0, int(width * 0.96), height))
    scaled_h = max(1, int(image.height * 800 / image.width))
    return image.resize((800, scaled_h), Image.Resampling.BOX)


def mean_diff(left, right):
    from PIL import Image, ImageChops, ImageStat
    if left.size != right.size:
        right = right.resize(left.size, Image.Resampling.BOX)
    return ImageStat.Stat(ImageChops.difference(left, right)).mean[0]


def confirm_dupes(hash_rows, directory):
    groups = defaultdict(list)
    for row in hash_rows:
        if not screenshot_name(row["name"]) or not activity_of(row["name"]):
            continue
        gray = gray_bytes(row["gray16"])
        if gray is None:
            continue
        row["gray"] = gray
        row["activity"] = activity_of(row["name"])
        groups[(row["w"], row["h"], row["activity"])].append(row)
    cache = {}

    def load(name):
        if name not in cache:
            try:
                cache[name] = content_image(Path(directory) / name)
            except (OSError, ValueError):
                cache[name] = None
        return cache[name]

    deleted = []
    for rows in groups.values():
        # Similarity is not transitive: compare each deletion directly with a
        # retained, newer image. An already deleted image can never be a keeper.
        keepers = []
        for row in sorted(rows, key=lambda item: (item["mtime"], item["name"]), reverse=True):
            left = load(row["name"])
            if left is None:
                continue
            for keep in keepers:
                if (gray_close(row["gray"], keep["gray"])
                        and mean_diff(left, load(keep["name"])) < MEAN_MAX):
                    deleted.append((row["name"], keep["name"], row["bytes"]))
                    break
            else:
                keepers.append(row)
        cache.clear()
    return deleted


def extract_candidates(archive, directory, expected):
    """Only materialize requested regular JPEGs; never follow archive links."""
    seen = set()
    with tarfile.open(archive) as bundle:
        for item in bundle:
            if (item.name not in expected or item.name in seen or not screenshot_name(item.name)
                    or not item.isfile() or item.size != expected[item.name]):
                raise ValueError("候选截图归档与名单不一致，未生成删除名单")
            with bundle.extractfile(item) as source, (directory / item.name).open("xb") as target:
                shutil.copyfileobj(source, target)
            seen.add(item.name)
    if seen != set(expected):
        raise ValueError("候选截图不完整，未生成删除名单")


def print_dupes(deleted):
    total = sum(size for _, _, size in deleted)
    groups = len({keep for _, keep, _ in deleted})
    print(f"重复 {groups} 组，可删 {len(deleted)} 张，{fmt_size(total)}")
    for name, keep, _ in deleted:
        print(f"可删\t{SHOT_DIR}/{name}")
        print(f"留下\t{SHOT_DIR}/{keep}")


ROOT = Path(__file__).resolve().parents[4]
DEX = Path(__file__).resolve().parents[1] / "hasher" / "screenshot-hash.dex"


def adb_bin():
    found = shutil.which("adb")
    if found:
        return found
    sdk = Path.home() / "Library/Android/sdk/platform-tools/adb"
    if sdk.is_file():
        return str(sdk)
    sys.exit("找不到 adb。")


def run(cmd, **kwargs):
    return subprocess.run(cmd, check=True, **kwargs)


def online_serials(adb):
    out = subprocess.run([adb, "devices"], check=True, capture_output=True, text=True).stdout
    serials = []
    for line in out.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            serials.append(parts[0])
    return serials


def connect():
    adb = adb_bin()
    serials = online_serials(adb)
    if not serials:
        run([str(ROOT / "scripts" / "connect.sh")])
        serials = online_serials(adb)
    if len(serials) != 1:
        sys.exit("需要恰好一台在线设备。先运行 scripts/connect.sh。")
    return adb, serials[0]


def device_listing(adb, serial):
    proc = subprocess.run(
        [adb, "-s", serial, "shell",
         "find " + SHOT_DIR + " -maxdepth 1 -type f -exec stat -c '%s\t%Y\t%n' {} +"],
        check=True, capture_output=True, text=True,
    )
    return proc.stdout.splitlines()


def fetch_rows(from_stdin):
    if from_stdin:
        return read_listing(sys.stdin)
    adb, serial = connect()
    return read_listing(device_listing(adb, serial))


def run_dupes():
    if not DEX.is_file():
        sys.exit(f"缺少 {DEX}\n先运行 {DEX.parent / 'build.sh'}")
    import tempfile
    adb, serial = connect()
    print("在手机上算指纹，大约两分钟。", file=sys.stderr)
    remote_dex = "/data/local/tmp/screenshot-hash.dex"
    remote_tsv = "/data/local/tmp/shot-hash.tsv"
    run([adb, "-s", serial, "push", str(DEX), remote_dex], capture_output=True)
    try:
        run([adb, "-s", serial, "shell",
             f"rm -f {remote_tsv}; CLASSPATH={remote_dex} app_process /data/local/tmp HashShots {SHOT_DIR} {remote_tsv}"])
        with tempfile.TemporaryDirectory() as work:
            work = Path(work)
            tsv = work / "hash.tsv"
            run([adb, "-s", serial, "pull", remote_tsv, str(tsv)], capture_output=True)
            rows = read_hash_tsv(tsv.read_text(encoding="utf-8").splitlines())
            names = candidate_names(rows)
            if not names:
                print("没有正文几乎相同的重复张。")
                return 0
            print(f"核对 {len(names)} 张原图。", file=sys.stderr)
            orig = work / "orig"
            orig.mkdir()
            tar_bytes = subprocess.run(
                [adb, "-s", serial, "exec-out", "tar", "-c", "-C", SHOT_DIR, "-T", "-"],
                input=("\n".join(names) + "\n").encode(),
                check=True, capture_output=True,
            ).stdout
            archive = work / "orig.tar"
            archive.write_bytes(tar_bytes)
            expected = {row["name"]: row["bytes"] for row in rows if row["name"] in names}
            extract_candidates(archive, orig, expected)
            print_dupes(confirm_dupes(rows, orig))
    finally:
        subprocess.run([adb, "-s", serial, "shell", "rm", "-f", remote_tsv, remote_dex], check=False)
    return 0


def main(argv):
    args = [arg for arg in argv[1:] if arg != "--from-stdin"]
    from_stdin = "--from-stdin" in argv[1:]
    cmd = args[0] if args else "report"
    if cmd in ("-h", "--help"):
        print("用法: cleanup.py")
        print("      cleanup.py list <类>")
        print("      cleanup.py dupes")
        print("类: " + " ".join(item[0] for item in CLASSES))
        return 0
    if cmd == "report":
        report(fetch_rows(from_stdin))
        return 0
    if cmd == "list":
        if len(args) != 2:
            print("用法: cleanup.py list <类>", file=sys.stderr)
            return 2
        return list_class(fetch_rows(from_stdin), args[1])
    if cmd == "dupes":
        return run_dupes()
    print(f"未知命令: {cmd}", file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
