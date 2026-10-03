"""Local regressions; all generated products live in a temporary directory."""
import argparse
import os
from pathlib import Path
import platform
import py_compile
import shutil
import subprocess
import sys
import tempfile

from doc_links import check as check_doc_links

ROOT = Path(__file__).resolve().parent.parent
MAC_TESTS = {
    "ReconnectPolicy": ["Reconnect"],
    "AdbCommandLine": ["AdbCommandLine"],
    "CommandSession": ["StationState", "StationRunner", "ClipboardProtocol", "AdbCommandLine", "CommandSession"],
    "RemoteRelayProfile": ["RemoteRelayProfile", "StationRunner"],
    "ClipboardSyncPolicy": ["ClipboardProtocol"],
    "ClipboardSession": ["ClipboardProtocol", "ClipboardSession"],
    "ClipboardTransport": ["ClipboardProtocol", "ClipboardSession"],
    "DeviceHealthSession": ["ClipboardProtocol", "DeviceHealthSession"],
    "NotificationSession": ["ClipboardProtocol", "NotificationProtocol", "NotificationDelivery", "NotificationSession", "NotificationBanner"],
    "NotificationBanner": ["NotificationProtocol", "NotificationBanner"],
    "InstallTask": ["InstallTask"],
    "StationRunner": ["StationRunner"],
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
        print(f"Markdown 文件与锚点 {check_doc_links(ROOT)} 份通过", flush=True)
        for name in ("scripts", "lib", ".agents", "server"):
            directory = ROOT / name
            for source in sorted(directory.rglob("*.sh")):
                run(["zsh", "-n", source])
            for source in sorted(directory.rglob("*.py")):
                py_compile.compile(str(source), cfile=str(work / "syntax.pyc"), doraise=True)
        print("文档镜像、Skill 入口与脚本语法通过", flush=True)
        run(["go", "-C", ROOT / "lib/remote-gateway", "test", "-race", "./..."])
        run(["go", "-C", ROOT / "server/relay", "test", "-race", "./..."])
        run(["go", "-C", ROOT / "lib/remote-gateway", "test", "-race", "-tags", "relaycontract", "-run", "^(TestRelayContractHarness|TestRepositoryRelayContract)$", "."])
        gateway = work / "phone-relay-gateway"
        run(["go", "-C", ROOT / "lib/remote-gateway", "build", "-o", gateway, "."])
        os.environ["PHONE_STATION_TEST_GATEWAY"] = str(gateway)
        tests = sorted((ROOT / "lib").rglob("test_*.py")) + sorted((ROOT / ".agents").rglob("test_*.py"))
        for test in tests:
            print(f"运行 {test.relative_to(ROOT)}", flush=True)
            run([sys.executable, test])
        java_tests = sorted((ROOT / "lib/android/test").glob("*Test.java"))
        run(["javac", "--release", "17", "-sourcepath", ROOT / "lib/android/src", "-d", work / "java", *java_tests])
        for test in java_tests:
            run(["java", "-cp", work / "java", "dev.phonestation.adbkeep." + test.stem])
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
            run([*command, "-framework", "UserNotifications", *[mac / (source + ".swift") for source in sources], mac / (name + "Test.swift"), "-o", binary])
            run([binary])
        sources = sorted(file for file in mac.glob("*.swift") if not file.name.endswith("Test.swift") and file.name != "MakeIcon.swift")
        for framework in ("SwiftUI", "AppKit", "ServiceManagement", "QuartzCore", "AVFoundation", "ImageIO", "UserNotifications"):
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
