"""Local regressions; all generated products live in a temporary directory."""
import argparse
import os
import re
from pathlib import Path
import platform
import py_compile
import shutil
import subprocess
import sys
import tempfile

from doc_links import check as check_doc_links
from android.websocket_deps import jars as websocket_jars

ROOT = Path(__file__).resolve().parent.parent
MAC_TESTS = {
    "StationLocalization": ["StationLocalization"],
    "RemoteEventSession": ["RemoteEventSession"],
    "RemoteScreenSession": ["ClipboardProtocol", "ScreenVideoProtocol", "RemoteScreenSession"],
    "ScreenVideoProtocol": ["ScreenVideoProtocol"],
    "ReconnectPolicy": ["Reconnect"],
    "AdbCommandLine": ["AdbCommandLine"],
    "CommandSession": ["StationState", "StationRunner", "UsageCounters", "ClipboardProtocol", "AdbCommandLine", "CommandSession"],
    "RemoteRelayProfile": ["RemoteRelayProfile", "StationRunner", "UsageCounters"],
    "ClipboardSyncPolicy": ["ClipboardProtocol"],
    "ClipboardSession": ["ClipboardProtocol", "ClipboardSession", "UsageCounters"],
    "ClipboardTransport": ["ClipboardProtocol", "ClipboardSession", "UsageCounters"],
    "DeviceHealthSession": ["ClipboardProtocol", "DeviceHealthSession"],
    "NotificationSession": ["ClipboardProtocol", "NotificationProtocol", "NotificationDelivery", "NotificationSession", "NotificationBanner"],
    "NotificationBanner": ["NotificationProtocol", "NotificationBanner"],
    "InstallTask": ["InstallTask"],
    "StationRunner": ["StationRunner", "UsageCounters"],
    "AdbDeviceStream": ["AdbDeviceStream"],
    "PairingPrompt": ["PairingPrompt"],
    "UsageCounters": ["UsageCounters", "ClipboardProtocol"],
    "LocalEventSession": ["ClipboardProtocol", "LocalEventSession"],
    "DeviceKey": ["DeviceKey"],
}


def run(args, **kwargs):
    subprocess.run([str(arg) for arg in args], cwd=ROOT, check=True, timeout=300, **kwargs)


def agent_docs():
    start, end = "<!-- agent-docs-sync:start -->", "<!-- agent-docs-sync:end -->"
    bodies = []
    for name in ("AGENTS.md", "CLAUDE.md"):
        text = (ROOT / name).read_text()
        if text.count(start) != 1 or text.count(end) != 1 or text.index(start) > text.index(end):
            raise ValueError(f"{name} 的镜像标记不完整")
        bodies.append(text.split(start)[1].split(end)[0])
    if bodies[0] != bodies[1]:
        raise ValueError("AGENTS.md 与 CLAUDE.md 主体不同；先明确方向再同步")
    for skill in (ROOT / ".agents/skills").iterdir():
        if not skill.is_dir():
            continue
        if not (skill / "SKILL.md").is_file():
            raise ValueError(f"缺少 {skill}/SKILL.md")
        for client in (".claude", ".grok"):
            link = ROOT / client / "skills" / skill.name
            if not link.is_symlink() or link.resolve() != skill.resolve():
                raise ValueError(f"{link} 未指向项目 Skill 源码")


def page_subscriptions():
    """每页视图里读到的 station.<会话> 必须出现在该页订阅表或常驻订阅里。"""
    mac = ROOT / "app/mac"
    station = (mac / "Station.swift").read_text()
    sessions = set()
    for line in station.splitlines():
        match = re.match(r"\s+(?:let|var) (\w+)\s*(?::\s*([A-Za-z0-9_]+))?(?:\s*=\s*([A-Za-z0-9_]+))?", line)
        if not match:
            continue
        blob = (match.group(2) or "") + (match.group(3) or "")
        if "Session" in blob or blob in {"StationFeedback", "StationAppearance"}:
            sessions.add(match.group(1))
    if not sessions:
        raise ValueError("没有解析到 Station 会话")
    body = station.split("private func bindPage()", 1)[1].split("private func observe", 1)[0]
    head, tail = body.split("switch page", 1)
    always = set(re.findall(r"observe\((\w+)\)", head))
    subscribed = {}
    for part in re.split(r"\n\s+case ", tail)[1:]:
        header, _, rest = part.partition(":")
        names = [piece.strip()[1:] for piece in header.split(",") if piece.strip().startswith(".")]
        observed = set(re.findall(r"observe\((\w+)\)", rest))
        for name in names:
            subscribed[name] = observed
    structs = {}
    for file in mac.glob("*.swift"):
        text = file.read_text()
        marks = list(re.finditer(r"(?m)^(struct|enum|final class|class|@main|private extension|extension) ([A-Za-z0-9_]+)", text))
        for index, mark in enumerate(marks):
            end = marks[index + 1].start() if index + 1 < len(marks) else len(text)
            structs[mark.group(2)] = text[mark.start():end]
    views = (mac / "StationView.swift").read_text().split("switch station.page", 1)[1]
    missing = []
    for part in re.split(r"\n\s+case ", views)[1:]:
        header, _, rest = part.partition(":")
        pages = [piece.strip()[1:] for piece in header.split(",") if piece.strip().startswith(".")]
        block = rest.split("\n    }", 1)[0]
        roots = re.findall(r"\b([A-Z][A-Za-z0-9]*)\s*\(", block)
        seen = []
        reads = set(re.findall(r"station\.([A-Za-z_][A-Za-z0-9_]*)", block)) & sessions
        pending = [name for name in roots if name in structs]
        while pending:
            name = pending.pop()
            if name in seen:
                continue
            seen.append(name)
            chunk = structs[name]
            reads |= set(re.findall(r"station\.([A-Za-z_][A-Za-z0-9_]*)", chunk)) & sessions
            pending.extend(ref for ref in re.findall(r"\b([A-Z][A-Za-z0-9]*)\s*\(", chunk) if ref in structs and ref not in seen)
        for page in pages:
            if page not in subscribed:
                missing.append(f"{page} 没有订阅表")
                continue
            gap = reads - always - subscribed[page]
            if gap:
                missing.append(f"{page} 读了 {', '.join(sorted(gap))}，订阅表没有")
    if missing:
        raise ValueError("页面订阅表与视图不一致：" + "；".join(missing))
    return len(subscribed)


def main():
    parser = argparse.ArgumentParser(description="本机回归检查：文档、语法、Python、Java、Go race、Swift；不操作手机。")
    parser.add_argument("--core", action="store_true", help="仅运行跨平台检查，明确跳过 Swift 编译和测试")
    args = parser.parse_args()
    if not args.core and platform.system() != "Darwin":
        parser.error("完整检查需要 macOS；其他平台用 --core")
    java_home = os.environ.get("JAVA_HOME")
    if not java_home and Path("/opt/homebrew/opt/openjdk/bin/javac").is_file():
        java_home = "/opt/homebrew/opt/openjdk"
    if java_home:
        os.environ["PATH"] = str(Path(java_home) / "bin") + os.pathsep + os.environ["PATH"]
    for tool in ("zsh", "go", "javac", "java", "keytool") + (() if args.core else ("xcrun",)):
        if not shutil.which(tool):
            parser.error(f"缺少 {tool}，请按 README 的本机检查说明安装")
    if not args.core:
        sdk_version = subprocess.check_output(["xcrun", "--show-sdk-version"], text=True).strip()
        if int(sdk_version.split(".")[0]) < 26:
            parser.error("完整 Mac 检查需要 macOS 26 或更新 SDK；用 DEVELOPER_DIR 选择兼容的 Xcode/Command Line Tools，或用 --core 仅验证跨平台部分")
    os.environ["PYTHONDONTWRITEBYTECODE"] = "1"
    with tempfile.TemporaryDirectory(prefix="phone-station-check-") as temp:
        work = Path(temp)
        agent_docs()
        print(f"页面订阅表 {page_subscriptions()} 页通过", flush=True)
        print(f"Markdown 文件与锚点 {check_doc_links(ROOT)} 份通过", flush=True)
        for name in ("scripts", "lib", ".agents", "server"):
            directory = ROOT / name
            for source in sorted(directory.rglob("*.sh")):
                run(["zsh", "-n", source])
            for source in sorted(directory.rglob("*.py")):
                py_compile.compile(str(source), cfile=str(work / "syntax.pyc"), doraise=True)
        print("文档镜像、Skill 入口与脚本语法通过", flush=True)
        run(["go", "-C", ROOT / "lib/remote-gateway", "test", "-race", "./..."])
        run(["go", "-C", ROOT / "lib/link", "test", "-race", "./..."])
        run(["go", "-C", ROOT / "server/relay", "test", "-race", "./..."])
        run(["go", "-C", ROOT / "lib/android/terminal", "test", "-race", "./..."])
        run(["go", "-C", ROOT / "lib/android/screen", "test", "-race", "./..."])
        terminal_env = {**os.environ, "GOOS": "android", "GOARCH": "arm64", "CGO_ENABLED": "0"}
        run(["go", "-C", ROOT / "lib/android/terminal", "build", "-trimpath", "-o", work / "terminal-arm64", "."], env=terminal_env)
        run(["go", "-C", ROOT / "lib/android/screen", "build", "-trimpath", "-o", work / "screen-arm64", "."], env=terminal_env)
        run(["go", "-C", ROOT / "lib/remote-gateway", "test", "-race", "-tags", "relaycontract", "-run", "^(TestRelayContractHarness|TestRepositoryRelayContract)$", "."])
        gateway = work / "phone-relay-gateway"
        run(["go", "-C", ROOT / "lib/remote-gateway", "build", "-o", gateway, "."])
        os.environ["PHONE_STATION_TEST_GATEWAY"] = str(gateway)
        tests = sorted((ROOT / "lib").rglob("test_*.py")) + sorted((ROOT / ".agents").rglob("test_*.py"))
        for test in tests:
            print(f"运行 {test.relative_to(ROOT)}", flush=True)
            run([sys.executable, test])
        java_cp = os.pathsep.join(str(p) for p in websocket_jars())
        java_tests = sorted((ROOT / "lib/android/test").glob("*Test.java"))
        run(["javac", "--release", "17", "-classpath", java_cp, "-sourcepath", ROOT / "lib/android/src", "-d", work / "java", *java_tests])
        for test in java_tests:
            run(["java", "-cp", str(work / "java") + os.pathsep + java_cp, "dev.phonestation.adbkeep." + test.stem])
        print(f"Java 测试 {len(java_tests)} 个通过", flush=True)
        if args.core:
            print("跨平台检查通过；--core 未验证 Swift。")
            return
        mac = ROOT / "app/mac"
        expected = {name + "Test.swift" for name in MAC_TESTS} | {"ConnectionStatusTest.swift"}
        actual = {file.name for file in mac.glob("*Test.swift")}
        if expected != actual:
            raise ValueError(f"Swift 测试清单未同步：{sorted(expected ^ actual)}")
        swift = subprocess.check_output(["xcrun", "--find", "swiftc"], text=True).strip()
        sdk = subprocess.check_output(["xcrun", "--show-sdk-path"], text=True).strip()
        command = [swift, "-parse-as-library", "-swift-version", "5", "-target", platform.machine() + "-apple-macosx13.0", "-sdk", sdk]
        for name, sources in MAC_TESTS.items():
            binary = work / (name + "Test")
            run([*command, "-framework", "UserNotifications", "-framework", "Security", "-framework", "CryptoKit", *[mac / (source + ".swift") for source in dict.fromkeys(["StationLocalization", *sources])], mac / (name + "Test.swift"), "-o", binary])
            run([binary])
        sources = sorted(file for file in mac.glob("*.swift") if not file.name.endswith("Test.swift") and file.name != "MakeIcon.swift")
        for framework in ("SwiftUI", "AppKit", "ServiceManagement", "QuartzCore", "AVFoundation", "ImageIO", "UserNotifications", "Security", "CryptoKit"):
            command += ["-framework", framework]
        run([*command, *[file for file in sources if file.name != "PhoneStationApp.swift"], mac / "ConnectionStatusTest.swift", "-o", work / "ConnectionStatusTest"])
        run([work / "ConnectionStatusTest"])
        run([*command, *sources, "-o", work / "PhoneStation"])
        print(f"全部检查通过；Swift 测试 {len(expected)} 个及 Mac 全量源码编译通过。", flush=True)


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, py_compile.PyCompileError, subprocess.SubprocessError) as error:
        sys.exit(f"检查失败：{error}")
