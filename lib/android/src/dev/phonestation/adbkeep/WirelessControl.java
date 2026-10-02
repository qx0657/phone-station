package dev.phonestation.adbkeep;

/** 手动操作与自动保持共用的规则；调用方与保持 tick 使用同一把锁。 */
final class WirelessControl {
    interface Backend {
        boolean canWrite();
        boolean adbEnabled();
        boolean wifiConnected();
        boolean keeping();
        boolean setKeeping(boolean enabled);
        boolean writeWireless(boolean enabled);
        void recordAttempt(boolean enabled);
    }

    enum Result { APPLIED, NO_PERMISSION, ADB_OFF, NO_WIFI, STORE_FAILED, WRITE_FAILED }

    static Result change(Backend backend, boolean enabled) {
        if (!backend.canWrite()) { return Result.NO_PERMISSION; }
        if (enabled && !backend.adbEnabled()) { return Result.ADB_OFF; }
        if (enabled && !backend.wifiConnected()) { return Result.NO_WIFI; }
        boolean wasKeeping = backend.keeping();
        // 先保存停用，再写系统开关；设置观察者被唤醒时不能把它重新打开。
        if (!enabled && !backend.setKeeping(false)) { return Result.STORE_FAILED; }
        backend.recordAttempt(enabled);
        if (!backend.writeWireless(enabled)) {
            if (!enabled && wasKeeping) { backend.setKeeping(true); }
            return Result.WRITE_FAILED;
        }
        return Result.APPLIED;
    }

    private WirelessControl() {}
}
