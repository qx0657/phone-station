package dev.phonestation.adbkeep;

import dev.phonestation.adbkeep.link.LinkEvents;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** 把现有剪贴板、通知和状态交给已经订阅的本地链路。 */
final class LinkLiveEvents {
    private static volatile LinkEvents current;
    private static final Map<String, byte[]> LAST = new HashMap<String, byte[]>();

    private LinkLiveEvents() {}

    static void attach(LinkEvents events) {
        current = events;
    }

    static void detach(LinkEvents events) {
        if (current == events) {
            current = null;
            LAST.clear();
        }
    }

    /** 状态主题。正文与上次相同就不入队。grants 必须带 32 位 Mac id。 */
    static void state(String topic, String macId, byte[] body) {
        LinkEvents events = current;
        if (events == null || body == null) {
            return;
        }
        String key = topic + "\0" + (macId == null ? "" : macId);
        synchronized (LinkLiveEvents.class) {
            byte[] previous = LAST.get(key);
            if (previous != null && Arrays.equals(previous, body)) {
                return;
            }
            LAST.put(key, body.clone());
        }
        try {
            events.publishState(topic, macId, body);
        } catch (RuntimeException error) {
            synchronized (LinkLiveEvents.class) {
                LAST.remove(key);
            }
            throw error;
        }
    }

    /** 把还在合并窗口里的状态立刻发出。撤销授权时会话马上会关掉。 */
    static void flush() {
        LinkEvents events = current;
        if (events != null) {
            events.deliver(Long.MAX_VALUE);
        }
    }

    static void note(String topic) {
        LinkEvents events = current;
        if (events == null || (!"clipboard".equals(topic) && !"notifications".equals(topic))) {
            return;
        }
        events.broadcastOrdered(topic, new byte[0]);
    }
}
