#!/usr/bin/env python3
# 通过 MT 界面启动或停止 MCP。
# 服务类没有 exported，Android 15 上 shell 直接 start-foreground-service 会被拒绝。
# 侧栏项「MCP 服务」和对话框按钮「启动」「停止」是稳定入口。

import argparse
import io
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

from PIL import Image

PACKAGE = "bin.mt.plus"
LAUNCHER = "bin.mt.plus/.MainLightIcon"
DUMP_PATH = "/data/local/tmp/mt-mcp-ui.xml"


def run(adb, serial, args, check=True):
    cmd = [adb, "-s", serial, *args]
    return subprocess.run(cmd, check=check, capture_output=True)


def shell(adb, serial, *args):
    run(adb, serial, ["shell", *args])


def _dump_once(adb, serial):
    run(adb, serial, ["shell", "rm", "-f", DUMP_PATH], check=False)
    proc = run(adb, serial, ["shell", "uiautomator", "dump", DUMP_PATH], check=False)
    message = (proc.stdout + proc.stderr).decode("utf-8", "replace")
    if "ERROR" in message or "null root" in message:
        return None, message.strip()
    out = run(adb, serial, ["exec-out", "cat", DUMP_PATH], check=False)
    text = out.stdout.decode("utf-8", "replace")
    if "<?xml" in text:
        return text, ""
    return None, message.strip() or "empty dump"


def dump_ui(adb, serial):
    # 失败时系统不会覆盖旧文件。对话框关掉后有时会停在一个读不到的窗口，退回主界面再试。
    last_error = ""
    for attempt in range(6):
        text, last_error = _dump_once(adb, serial)
        if text:
            return text
        if attempt == 2:
            shell(adb, serial, "input", "keyevent", "4")
            time.sleep(0.4)
            shell(adb, serial, "am", "start", "-n", LAUNCHER)
            time.sleep(0.6)
        else:
            time.sleep(0.4)
    raise SystemExit("读不到 MT 的界面。屏幕如果锁着，先解开。" + (f" {last_error}" if last_error else ""))


def screenshot(adb, serial):
    proc = run(adb, serial, ["exec-out", "screencap", "-p"])
    return Image.open(io.BytesIO(proc.stdout))


def _dark(pixel):
    return max(pixel[:3]) < 90


def _white(pixel):
    return pixel[0] > 248 and pixel[1] > 248 and pixel[2] > 248


def dialog_pixels(adb, serial):
    # 对话框中间是纯白，左右和上方是压暗的文件列表。
    # 已安装应用那种白卡片不能算。
    image = screenshot(adb, serial)
    width, height = image.size

    def at(fx, fy):
        return image.getpixel((int(width * fx), int(height * fy)))

    # 0.49 会落在说明文字上，是灰色。取标题下沿和地址区两块白底。
    visible = _white(at(0.50, 0.42)) and _white(at(0.50, 0.53)) and _dark(at(0.03, 0.53))
    return visible, width, height


def nodes(xml_text):
    root = ET.fromstring(xml_text)
    return [el.attrib for el in root.iter("node")]


def bounds(node):
    nums = [int(n) for n in re.findall(r"\d+", node.get("bounds", ""))]
    if len(nums) != 4:
        return None
    x1, y1, x2, y2 = nums
    return x1, y1, x2, y2


def center(node):
    box = bounds(node)
    if not box:
        return None
    x1, y1, x2, y2 = box
    return (x1 + x2) // 2, (y1 + y2) // 2


def tap(adb, serial, node):
    point = center(node)
    if not point:
        raise SystemExit("界面节点没有坐标。")
    shell(adb, serial, "input", "tap", str(point[0]), str(point[1]))


def find_text(xml_text, text):
    # 点文字本身经常点空。侧栏项的文字不可点，可点的是外面那一行。
    start = xml_text.find("<?xml")
    root = ET.fromstring(xml_text[start:] if start >= 0 else xml_text)
    parent = {}
    for node in root.iter():
        for child in list(node):
            parent[child] = node
    for el in root.iter("node"):
        if el.attrib.get("text") != text:
            continue
        chosen = el
        while chosen is not None and chosen.attrib.get("clickable") != "true":
            chosen = parent.get(chosen)
        if chosen is None:
            chosen = el
        return chosen.attrib
    return None


def find_hamburger(items):
    # 左上角没有文字的按钮是侧栏。右上角 content-desc 为「菜单」的是另一套菜单。
    for node in items:
        if not node.get("class", "").endswith("ImageButton"):
            continue
        if node.get("clickable") != "true":
            continue
        box = bounds(node)
        if not box:
            continue
        x1, y1, x2, _ = box
        if x1 <= 20 and y1 < 500 and x2 < 400:
            return node
    return None


def dialog_open(xml_text):
    return "将支持 MCP" in xml_text or "服务未启动" in xml_text


def drawer_open(xml_text):
    return "根目录" in xml_text or "夜间模式" in xml_text


def tap_xy(adb, serial, x, y):
    shell(adb, serial, "input", "tap", str(int(x)), str(int(y)))


def open_drawer(adb, serial):
    for _ in range(4):
        visible, _, _ = dialog_pixels(adb, serial)
        if visible:
            return
        xml_text = dump_ui(adb, serial)
        if drawer_open(xml_text) or find_text(xml_text, "MCP 服务") is not None:
            return
        if "显示播放器控件" in xml_text:
            shell(adb, serial, "input", "keyevent", "4")
            time.sleep(0.4)
            continue
        button = find_hamburger(nodes(xml_text))
        if button is not None and "bin.mt.plus" in xml_text:
            tap(adb, serial, button)
            time.sleep(1.0)
            continue
        shell(adb, serial, "input", "keyevent", "4")
        time.sleep(0.4)
        shell(adb, serial, "input", "keyevent", "KEYCODE_WAKEUP")
        shell(adb, serial, "am", "start", "-n", LAUNCHER)
        time.sleep(0.8)
    raise SystemExit("没有打开 MT 的侧栏。")


def open_dialog(adb, serial):
    visible, _, _ = dialog_pixels(adb, serial)
    if visible:
        return
    open_drawer(adb, serial)
    visible, _, _ = dialog_pixels(adb, serial)
    if visible:
        return
    for _ in range(4):
        xml_text = dump_ui(adb, serial)
        mcp = find_text(xml_text, "MCP 服务")
        if mcp is None:
            if not drawer_open(xml_text):
                open_drawer(adb, serial)
                continue
            shell(adb, serial, "input", "swipe", "500", "2000", "500", "1100", "400")
            time.sleep(0.6)
            continue
        box = bounds(mcp)
        # 点这一行靠上的位置。点正中或贴底会被系统手势当成关掉侧栏。
        tap_xy(adb, serial, box[0] + 300, box[1] + 36)
        time.sleep(0.9)
        visible, _, _ = dialog_pixels(adb, serial)
        if visible or dialog_open(dump_ui(adb, serial)):
            return
    raise SystemExit("没有打开 MCP 服务对话框。确认 MT 管理器能正常进侧栏。")


def press_primary(adb, serial):
    # 左下角同一个位置：服务开着是「停止」，关着是「启动」。
    xml_text = dump_ui(adb, serial)
    for label in ("启动", "停止"):
        node = find_text(xml_text, label)
        if node is not None and node.get("clickable") == "true":
            tap(adb, serial, node)
            time.sleep(0.6)
            return
    visible, width, height = dialog_pixels(adb, serial)
    if not visible:
        raise SystemExit("MCP 对话框没有出现，不能点启动或停止。")
    tap_xy(adb, serial, width * 0.185, height * 0.661)
    time.sleep(0.6)


def close_dialog(adb, serial):
    visible, width, height = dialog_pixels(adb, serial)
    if not visible:
        return
    xml_text = dump_ui(adb, serial)
    node = find_text(xml_text, "关闭")
    if node is not None:
        tap(adb, serial, node)
    else:
        tap_xy(adb, serial, width * 0.815, height * 0.661)
    time.sleep(0.4)


def ui_start(adb, serial):
    open_dialog(adb, serial)
    press_primary(adb, serial)
    close_dialog(adb, serial)


def ui_stop(adb, serial):
    open_dialog(adb, serial)
    press_primary(adb, serial)
    time.sleep(0.4)
    close_dialog(adb, serial)


def main():
    parser = argparse.ArgumentParser(description="打开或关掉 MT 的 MCP 对话框按钮")
    parser.add_argument("action", choices=("ui-start", "ui-stop"))
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", required=True)
    args = parser.parse_args()
    if args.action == "ui-start":
        ui_start(args.adb, args.serial)
    else:
        ui_stop(args.adb, args.serial)


if __name__ == "__main__":
    main()
