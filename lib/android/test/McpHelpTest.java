package dev.phonestation.adbkeep;

final class McpHelpTest {
    public static void main(String[] args) {
        McpHelp.Section[] sections = McpHelp.sections();
        expect(4, sections.length);
        expect("怎么连上", sections[0].title);
        expect("能做的", sections[1].title);
        expect("做不到的", sections[2].title);
        expect("交接", sections[3].title);
        String all = text(sections);
        expect(true, all.contains("运行\n./scripts/mcp.sh\n"));
        expect(true, all.contains("只在手机本机"));
        expect(true, all.contains("不进回收站"));
        expect(true, all.contains("Download/手机工位/inbox"));
        expect(true, all.contains("Download/手机工位/outbox"));
        expect(true, all.contains("不会自动创建"));
        expect(false, all.contains("station_"));
        expect(false, all.contains("Bearer"));
        expect(false, all.contains("127.0.0.1"));
        System.out.println("McpHelpTest ok");
    }

    private static String text(McpHelp.Section[] sections) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < sections.length; i++) {
            out.append(sections[i].title).append('\n').append(sections[i].body).append('\n');
        }
        return out.toString();
    }

    private static void expect(int want, int got) {
        if (want != got) {
            throw new AssertionError("want " + want + " got " + got);
        }
    }

    private static void expect(String want, String got) {
        if (!want.equals(got)) {
            throw new AssertionError("want " + want + " got " + got);
        }
    }

    private static void expect(boolean want, boolean got) {
        if (want != got) {
            throw new AssertionError("want " + want + " got " + got);
        }
    }
}
