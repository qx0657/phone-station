package dev.phonestation.adbkeep;

/**
 * 会话提醒那条通知怎么发。没有 Android 依赖，构建时在电脑上跑测试。
 *
 * <p>和常驻状态不是同一条。常驻用通道 {@code keep}，id 从 1 起，不用这里的 2 和 3。
 * 弹出用通道 {@code popup2}，只进下拉栏用 {@code remind-quiet}。
 * {@code alert}、{@code remind}、{@code popup} 和没有声音的 {@code banner} 都曾建过，系统不允许再改，所以换了 id。
 */
final class AlertNote {
    static final String ACTION = "dev.phonestation.adbkeep.ALERT";
    static final String CHANNEL_ID = "popup2";
    static final String CHANNEL_NAME = "提醒";
    static final String QUIET_CHANNEL_ID = "remind-quiet";
    static final String QUIET_CHANNEL_NAME = "提醒，不弹出";
    static final String REPLACE_TAG = "alert";
    static final int REPLACE_ID = 2;
    static final int STACK_ID = 3;

    final String title;
    final String text;
    final String agent;
    final String tag;
    final int id;
    final boolean stack;

    private AlertNote(String title, String text, String agent, String tag, int id, boolean stack) {
        this.title = title;
        this.text = text;
        this.agent = agent;
        this.tag = tag;
        this.id = id;
        this.stack = stack;
    }

    /**
     * 标题、内容是空的，或方式不是 replace / stack 时，返回 null。
     * Agent 只认 Grok、Claude、Codex，别的名字当成没有。
     */
    static AlertNote parse(
            String title, String text, String mode, String agent, long millis, long nanos) {
        if (title == null || title.isEmpty() || text == null || text.isEmpty()) {
            return null;
        }
        String known = knownAgent(agent);
        if ("replace".equals(mode)) {
            return new AlertNote(title, text, known, REPLACE_TAG, REPLACE_ID, false);
        }
        if ("stack".equals(mode)) {
            return new AlertNote(title, text, known, stackTag(millis, nanos), STACK_ID, true);
        }
        return null;
    }

    static String knownAgent(String agent) {
        if ("Grok".equals(agent) || "Claude".equals(agent) || "Codex".equals(agent)) {
            return agent;
        }
        return "";
    }

    static String stackTag(long millis, long nanos) {
        return "alert-" + millis + "-" + Long.toUnsignedString(nanos);
    }

    static boolean isStacked(String tag, int id) {
        return id == STACK_ID && tag != null && tag.startsWith("alert-");
    }
}
