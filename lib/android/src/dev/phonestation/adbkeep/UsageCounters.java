package dev.phonestation.adbkeep;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 本次进程内的开销计数。进程退出即清零，不写磁盘，也不因此多访问系统设置。
 * 手机页展示这里的数字；Mac 侧计数在网关和菜单栏 App，不从手机读取。
 */
final class UsageCounters {
    static final UsageCounters shared = new UsageCounters();

    static final class Line {
        final String label;
        final String value;

        Line(String label, String value) {
            this.label = label;
            this.value = value;
        }
    }

    private final ConcurrentHashMap<String, AtomicLong> requests = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> helperStarts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> helperStops = new ConcurrentHashMap<>();
    private final AtomicLong settingsWrites = new AtomicLong();
    private final AtomicLong reconnects = new AtomicLong();
    private final java.util.concurrent.CopyOnWriteArrayList<Runnable> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** 工具调用按能力归类；协议方法单独计入「协议」。 */
    static String category(String tool) {
        if (tool == null) {
            return "other";
        }
        if (tool.startsWith("station_file_")) {
            return "file";
        }
        if (tool.startsWith("station_clipboard_")) {
            return "clipboard";
        }
        if (tool.startsWith("station_notification_")) {
            return "notification";
        }
        if (tool.startsWith("station_screen_")) {
            return "screen";
        }
        if (tool.startsWith("station_shell_") || tool.startsWith("station_terminal_")) {
            return "shell";
        }
        if (tool.startsWith("station_")) {
            return "control";
        }
        return "other";
    }

    void listen(Runnable listener) { listeners.add(listener); }
    void unlisten(Runnable listener) { listeners.remove(listener); }

    void rpc(String method, String tool) {
        if ("tools/call".equals(method)) {
            add(requests, category(tool));
        } else if ("initialize".equals(method) || "tools/list".equals(method) || "ping".equals(method)) {
            add(requests, "protocol");
        } else {
            add(requests, "other");
        }
        changed();
    }

    void settingsWrite() {
        settingsWrites.incrementAndGet();
        changed();
    }

    /** 只在写入返回成功时计数。拒绝或抛错的尝试不算落地。 */
    static boolean noteWrite(boolean wrote) {
        if (wrote) {
            shared.settingsWrite();
        }
        return wrote;
    }

    void helperStart(String name) {
        add(helperStarts, name);
        changed();
    }

    void helperStop(String name) {
        add(helperStops, name);
        changed();
    }

    void reconnect() {
        reconnects.incrementAndGet();
        changed();
    }

    private void changed() {
        for (Runnable listener : listeners) { listener.run(); }
    }

    Line[] lines() {
        return new Line[] {
                new Line("各类请求", requestText()),
                new Line("系统设置写入", Long.toString(settingsWrites.get())),
                new Line("助手启动", helperText(helperStarts)),
                new Line("助手停止", helperText(helperStops)),
                new Line("重连次数", Long.toString(reconnects.get()))
        };
    }

    private String requestText() {
        return sum(requests)
                + "（文件 " + count(requests, "file")
                + "，剪贴板 " + count(requests, "clipboard")
                + "，通知 " + count(requests, "notification")
                + "，屏幕 " + count(requests, "screen")
                + "，命令 " + count(requests, "shell")
                + "，控制 " + count(requests, "control")
                + "，协议 " + count(requests, "protocol")
                + "，其他 " + count(requests, "other") + "）";
    }

    private String helperText(ConcurrentHashMap<String, AtomicLong> bucket) {
        return sum(bucket)
                + "（手电筒 " + count(bucket, "torch")
                + "，剪贴板 " + count(bucket, "clipboard")
                + "，命令 " + count(bucket, "shell")
                + "，终端 " + count(bucket, "terminal")
                + "，投屏 " + count(bucket, "screen") + "）";
    }

    private static void add(ConcurrentHashMap<String, AtomicLong> bucket, String key) {
        bucket.computeIfAbsent(key, ignored -> new AtomicLong()).incrementAndGet();
    }

    private static long count(ConcurrentHashMap<String, AtomicLong> bucket, String key) {
        AtomicLong value = bucket.get(key);
        return value == null ? 0L : value.get();
    }

    private static long sum(ConcurrentHashMap<String, AtomicLong> bucket) {
        long total = 0L;
        for (AtomicLong value : bucket.values()) {
            total += value.get();
        }
        return total;
    }
}
