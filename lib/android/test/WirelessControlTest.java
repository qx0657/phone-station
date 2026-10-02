package dev.phonestation.adbkeep;

final class WirelessControlTest {
    public static void main(String[] args) {
        Fake phone = new Fake();
        expect(WirelessControl.Result.APPLIED, WirelessControl.change(phone, false));
        check(!phone.keeping && !phone.wireless, "关闭同时停用自动保持");
        check(phone.recoveryAtWrite == -1L, "设置观察者不能在手动关闭时触发恢复");

        phone = new Fake(); phone.keeping = false; phone.wireless = false;
        expect(WirelessControl.Result.APPLIED, WirelessControl.change(phone, true));
        check(phone.wireless && !phone.keeping, "手动开启不擅自启用自动保持");
        check(phone.recordedBeforeWrite, "开启前记录重试，避免系统拨回后立即自动重试");

        phone = new Fake(); phone.wifi = false; phone.adb = false;
        expect(WirelessControl.Result.APPLIED, WirelessControl.change(phone, false));
        check(!phone.keeping && !phone.wireless, "关闭不需要 Wi-Fi 或 USB 调试");

        phone = new Fake(); phone.permission = false;
        expect(WirelessControl.Result.NO_PERMISSION, WirelessControl.change(phone, false));
        check(phone.keeping && phone.writes == 0, "未授权不改变保持或系统设置");

        phone = new Fake(); phone.adb = false;
        expect(WirelessControl.Result.ADB_OFF, WirelessControl.change(phone, true));
        check(phone.writes == 0, "USB 调试未开启时不写入");

        phone = new Fake(); phone.wifi = false;
        expect(WirelessControl.Result.NO_WIFI, WirelessControl.change(phone, true));
        check(phone.writes == 0, "无 Wi-Fi 不写入");

        phone = new Fake(); phone.storeWorks = false;
        expect(WirelessControl.Result.STORE_FAILED, WirelessControl.change(phone, false));
        check(phone.keeping && phone.writes == 0, "无法保存停用时不关闭系统开关");

        phone = new Fake(); phone.writeWorks = false;
        expect(WirelessControl.Result.WRITE_FAILED, WirelessControl.change(phone, false));
        check(phone.keeping && phone.wireless, "系统写入失败恢复原自动保持选择");

        phone = new Fake(); phone.keeping = false; phone.writeWorks = false;
        expect(WirelessControl.Result.WRITE_FAILED, WirelessControl.change(phone, false));
        check(!phone.keeping, "失败不打开原本关闭的自动保持");
        System.out.println("WirelessControlTest ok");
    }

    private static final class Fake implements WirelessControl.Backend {
        boolean permission = true, adb = true, wifi = true, keeping = true, wireless = true;
        boolean storeWorks = true, writeWorks = true, recordedBeforeWrite;
        int writes;
        long recoveryAtWrite;
        public boolean canWrite() { return permission; }
        public boolean adbEnabled() { return adb; }
        public boolean wifiConnected() { return wifi; }
        public boolean keeping() { return keeping; }
        public boolean setKeeping(boolean value) {
            if (!storeWorks) { return false; }
            keeping = value; return true;
        }
        public void recordAttempt(boolean value) { recordedBeforeWrite = true; }
        public boolean writeWireless(boolean value) {
            writes++;
            recoveryAtWrite = KeeperPolicy.enableDelayMs(keeping, adb, false, wifi, 0, Long.MAX_VALUE);
            if (!writeWorks) { return false; }
            wireless = value; return true;
        }
    }

    private static void expect(WirelessControl.Result expected, WirelessControl.Result actual) {
        if (actual != expected) { throw new AssertionError("expected " + expected + " got " + actual); }
    }
    private static void check(boolean value, String message) {
        if (!value) { throw new AssertionError(message); }
    }
}
