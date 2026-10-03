package dev.phonestation.adbkeep;

/**
 * MCP 和 adb 广播共用的设置事务。先保存原值，再改设置。
 * 开着：息屏 2147483647 毫秒，充电时常亮的掩码是 7（交流电、USB、无线充）。
 * 关掉：只恢复仍由本应用占用的值；遗留无快照常亮才使用 60 秒/0。
 */
final class StayAwake {
    static final int ON_TIMEOUT = 2147483647;
    static final int OFF_TIMEOUT = 60000;
    static final int ON_PLUGGED = 7;
    static final int OFF_PLUGGED = 0;

    private StayAwake() {}

    interface Settings {
        String timeout();
        String plugged();
        boolean timeout(String value);
        boolean plugged(String value);
    }
    interface Store {
        Snapshot load();
        void save(Snapshot value);
        void clear();
    }
    static final class Snapshot {
        final String timeout, plugged;
        Snapshot(String timeout, String plugged) { this.timeout = timeout; this.plugged = plugged; }
    }

    static synchronized void apply(Settings settings, Store store, boolean on) {
        Snapshot saved = store.load();
        String timeout = settings.timeout(), plugged = settings.plugged();
        String heldTimeout = Integer.toString(ON_TIMEOUT), heldPlugged = Integer.toString(ON_PLUGGED);
        if (!on) {
            if (saved == null) {
                if (!heldTimeout.equals(timeout) || !heldPlugged.equals(plugged)) { return; }
                // Upgrade from versions which did not record the user's values.
                saved = new Snapshot(Integer.toString(OFF_TIMEOUT), Integer.toString(OFF_PLUGGED));
                store.save(saved);
            }
            restore(settings, store, saved);
            return;
        }
        if (saved == null) {
            saved = heldTimeout.equals(timeout) && heldPlugged.equals(plugged)
                    ? new Snapshot(Integer.toString(OFF_TIMEOUT), Integer.toString(OFF_PLUGGED))
                    : new Snapshot(timeout, plugged);
        } else {
            // Explicitly re-enabling after a manual change adopts the new value
            // for that field without replacing the other original value.
            saved = new Snapshot(heldTimeout.equals(timeout) ? saved.timeout : timeout,
                    heldPlugged.equals(plugged) ? saved.plugged : plugged);
        }
        store.save(saved); // Must be durable before the first settings write.
        try {
            if (!settings.timeout(heldTimeout) || !settings.plugged(heldPlugged)
                    || !heldTimeout.equals(settings.timeout()) || !heldPlugged.equals(settings.plugged())) {
                throw new IllegalStateException("亮屏设置没有写成");
            }
        } catch (RuntimeException failure) {
            try { restore(settings, store, saved); }
            catch (RuntimeException rollback) { throw new IllegalStateException("亮屏写入与恢复未完成；保留原设置记录，请再次关闭以恢复", rollback); }
            throw new IllegalStateException("亮屏设置未成功，已恢复原设置", failure);
        }
    }

    private static void restore(Settings settings, Store store, Snapshot saved) {
        if (Integer.toString(ON_TIMEOUT).equals(settings.timeout())) {
            if (!settings.timeout(saved.timeout) || !java.util.Objects.equals(saved.timeout, settings.timeout())) {
                throw new IllegalStateException("原息屏时间未恢复，请再次关闭以恢复");
            }
        }
        if (Integer.toString(ON_PLUGGED).equals(settings.plugged())) {
            if (!settings.plugged(saved.plugged) || !java.util.Objects.equals(saved.plugged, settings.plugged())) {
                throw new IllegalStateException("原充电亮屏设置未恢复，请再次关闭以恢复");
            }
        }
        store.clear();
    }

    static boolean held(int timeout, int plugged) {
        return timeout == ON_TIMEOUT && plugged == ON_PLUGGED;
    }

    static int timeout(boolean on) {
        return on ? ON_TIMEOUT : OFF_TIMEOUT;
    }

    static int plugged(boolean on) {
        return on ? ON_PLUGGED : OFF_PLUGGED;
    }
}
