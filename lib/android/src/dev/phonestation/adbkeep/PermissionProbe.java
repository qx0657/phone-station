package dev.phonestation.adbkeep;

import android.Manifest;
import android.app.AlarmManager;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import android.os.PowerManager;

import java.lang.reflect.Method;

/** 权限页要读的几项。自启动不在这里，荣耀的启动管理读不到。 */
final class PermissionProbe {
    final boolean canWrite;
    final boolean notifications;
    final boolean banner;
    final boolean storage;
    final boolean battery;
    final boolean alarm;

    private PermissionProbe(
            boolean canWrite,
            boolean notifications,
            boolean banner,
            boolean storage,
            boolean battery,
            boolean alarm) {
        this.canWrite = canWrite;
        this.notifications = notifications;
        this.banner = banner;
        this.storage = storage;
        this.battery = battery;
        this.alarm = alarm;
    }

    static PermissionProbe read(Context context) {
        return new PermissionProbe(
                KeeperEngine.canWrite(context),
                notifications(context),
                AlertChannel.pops(context),
                storage(),
                battery(context),
                alarm(context));
    }

    private static boolean notifications(Context context) {
        if (Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        return manager != null && manager.areNotificationsEnabled();
    }

    /** API 29 的框架里没有这个方法，直接调用会在加载类时验证失败。 */
    private static boolean storage() {
        if (Build.VERSION.SDK_INT < 30) {
            return true;
        }
        try {
            Method method = Environment.class.getMethod("isExternalStorageManager");
            Object value = method.invoke(null);
            return value instanceof Boolean && (Boolean) value;
        } catch (ReflectiveOperationException error) {
            return false;
        }
    }

    private static boolean battery(Context context) {
        PowerManager power = context.getSystemService(PowerManager.class);
        return power != null && power.isIgnoringBatteryOptimizations(context.getPackageName());
    }

    /** API 31 才有。更早的系统没有这道开关。 */
    private static boolean alarm(Context context) {
        if (Build.VERSION.SDK_INT < 31) {
            return true;
        }
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        if (alarms == null) {
            return false;
        }
        try {
            Method method = AlarmManager.class.getMethod("canScheduleExactAlarms");
            Object value = method.invoke(alarms);
            return value instanceof Boolean && (Boolean) value;
        } catch (ReflectiveOperationException error) {
            return true;
        }
    }
}
