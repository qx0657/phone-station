package dev.phonestation.adbkeep;

import java.util.concurrent.TimeUnit;

/** 本地挂起等待。计数只在内存里递增，多个等待者看到同一次变化。 */
final class LocalEventWait {
    static final long TIMEOUT_MS = 25_000L;
    private static final Object LOCK = new Object();
    private static long clipboard;
    private static long notifications;

    static void note(String topic) {
        if (topic == null) {
            return;
        }
        synchronized (LOCK) {
            if ("clipboard".equals(topic)) {
                clipboard++;
            } else if ("notifications".equals(topic)) {
                notifications++;
            } else {
                return;
            }
            LOCK.notifyAll();
        }
    }

    static Json await(long clipSeen, long noteSeen, long timeoutMs) {
        synchronized (LOCK) {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, timeoutMs));
            while (clipboard == clipSeen && notifications == noteSeen) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    break;
                }
                try {
                    LOCK.wait(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(left)));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            return Json.obj().put("clipboard", clipboard).put("notifications", notifications);
        }
    }

    private LocalEventWait() {}
}
