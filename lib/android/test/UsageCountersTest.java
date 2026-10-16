package dev.phonestation.adbkeep;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class UsageCountersTest {
    public static void main(String[] args) throws Exception {
        expect("file".equals(UsageCounters.category("station_file_stat")), "file tools");
        expect("clipboard".equals(UsageCounters.category("station_clipboard_get")), "clipboard tools");
        expect("notification".equals(UsageCounters.category("station_notification_poll")), "notification tools");
        expect("screen".equals(UsageCounters.category("station_screen_capture")), "screen tools");
        expect("shell".equals(UsageCounters.category("station_shell_exec")), "shell tools");
        expect("shell".equals(UsageCounters.category("station_terminal_open")), "terminal tools share the shell bucket");
        expect("control".equals(UsageCounters.category("station_controls_status")), "other station tools are controls");
        expect("control".equals(UsageCounters.category("station_device_status")), "device status is a control request");
        expect("other".equals(UsageCounters.category("ping")), "non-station names stay other");
        expect("other".equals(UsageCounters.category(null)), "missing tool");

        UsageCounters counters = new UsageCounters();
        counters.rpc("tools/call", "station_file_stat");
        counters.rpc("tools/call", "station_clipboard_exchange");
        counters.rpc("ping", null);
        counters.rpc("initialize", null);
        counters.rpc("tools/list", null);
        counters.rpc("nope", null);
        counters.settingsWrite();
        counters.settingsWrite();
        counters.helperStart("torch");
        counters.helperStart("shell");
        counters.helperStart("shell");
        counters.helperStop("shell");
        counters.reconnect();
        UsageCounters.Line[] lines = counters.lines();
        expect(lines.length == 5, "five diagnostic rows");
        expect("各类请求".equals(lines[0].label), "request label");
        expect("6（文件 1，剪贴板 1，通知 0，屏幕 0，命令 0，控制 0，协议 3，其他 1）".equals(lines[0].value),
                lines[0].value);
        expect("系统设置写入".equals(lines[1].label) && "2".equals(lines[1].value), "settings writes");
        expect("助手启动".equals(lines[2].label)
                && "3（手电筒 1，剪贴板 0，命令 2，终端 0，投屏 0）".equals(lines[2].value), lines[2].value);
        expect("1（手电筒 0，剪贴板 0，命令 1，终端 0，投屏 0）".equals(lines[3].value), lines[3].value);
        expect("重连次数".equals(lines[4].label) && "1".equals(lines[4].value), "reconnects");
        expect(UsageCounters.noteWrite(false) == false && UsageCounters.noteWrite(true), "only successful writes count");
        expect(UsageCounters.shared.lines()[1].value.equals("1"), "shared counter saw the successful write");

        Map<String, String> entries = new HashMap<>();
        Matcher pairs = Pattern.compile("(?m)^  (\"(?:\\\\.|[^\"\\\\])*\"): (\"(?:\\\\.|[^\"\\\\])*\"),?$")
                .matcher(new String(Files.readAllBytes(Path.of("app/i18n/en.json")), java.nio.charset.StandardCharsets.UTF_8));
        while (pairs.find()) {
            entries.put(Json.parse(pairs.group(1)).string(), Json.parse(pairs.group(2)).string());
        }
        StationText.install(new StationText(entries), () -> true);
        for (UsageCounters.Line line : lines) {
            String label = StationText.translate(line.label);
            String value = StationText.translate(line.value);
            noChinese(label);
            noChinese(value);
        }
        expect("Requests".equals(StationText.translate("各类请求")), "request label in English");
        expect("6 (files 1, clipboard 1, notifications 0, screen 0, commands 0, controls 0, protocol 3, other 1)"
                .equals(StationText.translate(lines[0].value)), StationText.translate(lines[0].value));
        expect("3 (flashlight 1, clipboard 0, shell 2, terminal 0, screen 0)"
                .equals(StationText.translate(lines[2].value)), StationText.translate(lines[2].value));
        System.out.println("UsageCountersTest passed");
    }

    private static void noChinese(String text) {
        expect(text.codePoints().noneMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN),
                "untranslated display: " + text);
    }

    private static void expect(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }
}
