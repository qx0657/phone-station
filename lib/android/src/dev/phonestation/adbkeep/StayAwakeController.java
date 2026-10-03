package dev.phonestation.adbkeep;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;

final class StayAwakeController {
    private StayAwakeController() {}

    static void set(Context context, boolean on) {
        if (!"PGT-AN20".equals(Build.MODEL.replace('_', '-'))) { throw new FileFailure("此机型尚未验证，未改亮屏设置"); }
        SharedPreferences prefs = context.getSharedPreferences("stay-awake", Context.MODE_PRIVATE);
        StayAwake.Settings settings = new StayAwake.Settings() {
            public String timeout() { return Settings.System.getString(context.getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT); }
            public String plugged() { return Settings.Global.getString(context.getContentResolver(), Settings.Global.STAY_ON_WHILE_PLUGGED_IN); }
            public boolean timeout(String value) { return Settings.System.putString(context.getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, value); }
            public boolean plugged(String value) { return Settings.Global.putString(context.getContentResolver(), Settings.Global.STAY_ON_WHILE_PLUGGED_IN, value); }
        };
        StayAwake.Store store = new StayAwake.Store() {
            public StayAwake.Snapshot load() {
                if (!prefs.getBoolean("saved", false)) { return null; }
                return new StayAwake.Snapshot(prefs.getString("timeout", null), prefs.getString("plugged", null));
            }
            public void save(StayAwake.Snapshot value) {
                if (!prefs.edit().putString("timeout", value.timeout).putString("plugged", value.plugged).putBoolean("saved", true).commit()) {
                    throw new IllegalStateException("无法保存原息屏设置，未继续更改");
                }
            }
            public void clear() {
                if (!prefs.edit().clear().commit()) { throw new IllegalStateException("原设置已恢复，但恢复记录清理未确认"); }
            }
        };
        try { StayAwake.apply(settings, store, on); }
        catch (RuntimeException error) { throw new FileFailure(error.getMessage() == null ? "息屏设置未确认" : error.getMessage()); }
    }
}
