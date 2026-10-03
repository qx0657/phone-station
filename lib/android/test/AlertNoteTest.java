package dev.phonestation.adbkeep;

final class AlertNoteTest {
    public static void main(String[] args) {
        if (AlertNote.REPLACE_ID == 1 || "keep".equals(AlertNote.CHANNEL_ID)) {
            throw new AssertionError("alert collides with the keeper notification");
        }
        AlertNote replace = AlertNote.parse("标题", "内容", "replace", "Grok", 10L, 20L);
        if (replace == null || replace.stack
                || !"alert".equals(replace.tag)
                || replace.id != AlertNote.REPLACE_ID
                || !"标题".equals(replace.title)
                || !"内容".equals(replace.text)
                || !"Grok".equals(replace.agent)) {
            throw new AssertionError("replace");
        }
        AlertNote stack = AlertNote.parse("标题", "内容", "stack", "Codex", 10L, 20L);
        String tag = AlertNote.stackTag(10L, 20L);
        if (stack == null || !stack.stack || !tag.equals(stack.tag) || stack.id != AlertNote.STACK_ID
                || !"Codex".equals(stack.agent)) {
            throw new AssertionError("stack");
        }
        if (tag.equals(AlertNote.stackTag(10L, 21L)) || tag.equals(AlertNote.REPLACE_TAG)) {
            throw new AssertionError("stack tag");
        }
        if (!AlertNote.isStacked(stack.tag, stack.id)
                || AlertNote.isStacked(replace.tag, replace.id)
                || AlertNote.isStacked(stack.tag, AlertNote.REPLACE_ID)
                || AlertNote.isStacked(null, AlertNote.STACK_ID)
                || AlertNote.isStacked("file-open", AlertNote.STACK_ID)
                || AlertNote.isStacked("keep", 1)) {
            throw new AssertionError("clear only stacked reminders");
        }
        AlertNote claude = AlertNote.parse("标题", "内容", "replace", "Claude", 1L, 2L);
        AlertNote unnamed = AlertNote.parse("标题", "内容", "replace", null, 1L, 2L);
        AlertNote other = AlertNote.parse("标题", "内容", "replace", "chatgpt", 1L, 2L);
        if (claude == null || !"Claude".equals(claude.agent)
                || unnamed == null || !"".equals(unnamed.agent)
                || other == null || !"".equals(other.agent)
                || !"Grok".equals(AlertNote.knownAgent("Grok"))
                || !"".equals(AlertNote.knownAgent("grok"))
                || !"".equals(AlertNote.knownAgent(null))) {
            throw new AssertionError("agent");
        }
        if (AlertNote.parse("", "内容", "replace", null, 0L, 0L) != null
                || AlertNote.parse("标题", "", "replace", null, 0L, 0L) != null
                || AlertNote.parse(null, "内容", "replace", null, 0L, 0L) != null
                || AlertNote.parse("标题", "内容", null, null, 0L, 0L) != null
                || AlertNote.parse("标题", "内容", "shell", "Grok", 0L, 0L) != null) {
            throw new AssertionError("reject");
        }
        System.out.println("AlertNoteTest ok");
    }
}
