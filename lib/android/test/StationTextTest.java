package dev.phonestation.adbkeep;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class StationTextTest {
    public static void main(String[] args) throws Exception {
        Map<String, String> entries = new HashMap<>();
        Matcher pairs = Pattern.compile("(?m)^  (\"(?:\\\\.|[^\"\\\\])*\"): (\"(?:\\\\.|[^\"\\\\])*\"),?$")
                .matcher(new String(Files.readAllBytes(Path.of("app/i18n/en.json")), java.nio.charset.StandardCharsets.UTF_8));
        while (pairs.find()) { entries.put(Json.parse(pairs.group(1)).string(), Json.parse(pairs.group(2)).string()); }
        check(entries.size() > 900, "load the actual packaged catalog");
        StationText catalog = new StationText(entries);
        boolean[] english = {true};
        StationText.install(catalog, () -> english[0]);
        check("Settings".equals(StationText.translate("设置")), "English settings");
        check("Selected 2 apps".equals(StationText.translate("已选 2 个应用")), "dynamic app count");
        check("Remote Connecting".equals(StationText.translate("远程连接中")), "overlapping phrases require complete coverage and spaces");
        check("Remote disconnected".equals(StationText.translate("远程未连接")), "compound statuses are readable");
        check("Sync notifications from 设置".equals(StationText.translate("同步 设置 的通知")), "user app names stay verbatim");
        check("Running “设置 {0}”…".equals(StationText.translate("正在执行「设置 {0}」…")), "literal placeholders in user names stay verbatim");
        check("机密草稿".equals(StationText.translate("机密草稿")), "unknown content stays verbatim");
        for (int bits = 0; bits < 128; bits++) {
            Json state = McpStatus.present(on(bits, 0), on(bits, 1), on(bits, 2), on(bits, 3),
                    on(bits, 4), on(bits, 5), on(bits, 6), "连接中");
            noChinese(StationText.translate(state.get("label").string()));
            noChinese(StationText.translate(state.get("detail").string()));
            StationNote note = StationNote.present(on(bits, 0), on(bits, 1), "wireless", on(bits, 2),
                    on(bits, 3), state, false, "等 Wi-Fi");
            noChinese(StationText.translate(note.title));
            noChinese(StationText.translate(note.text));
            noChinese(StationText.translate(note.expandedDetail));
        }
        for (PermissionCopy.ShizukuState shizuku : PermissionCopy.ShizukuState.values()) {
            PermissionCopy.Board board = PermissionCopy.present(false, false, false, false, false, false, true, shizuku);
            noChinese(StationText.translate(board.summary));
            for (PermissionCopy.Row row : board.rows) {
                noChinese(StationText.translate(row.title)); noChinese(StationText.translate(row.value));
                noChinese(StationText.translate(row.hint)); noChinese(StationText.translate(PermissionCopy.impact(row)));
            }
        }
        // Translating a displayed label must never change protocol/state values.
        check("运行中".equals(McpStatus.present(true, true, true, false, false, false, false, "未配置").get("label").string()), "canonical status is unchanged");
        english[0] = false;
        check("设置".equals(StationText.translate("设置")), "switch back immediately");
        System.out.println("StationTextTest passed");
    }
    private static boolean on(int bits, int index) { return (bits & (1 << index)) != 0; }
    private static void noChinese(String text) {
        check(text.codePoints().noneMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN), "untranslated display: " + text);
    }
    private static void check(boolean condition, String message) { if (!condition) { throw new AssertionError(message); } }
}
