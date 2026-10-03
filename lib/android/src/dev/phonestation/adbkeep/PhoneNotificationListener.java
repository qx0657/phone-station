package dev.phonestation.adbkeep;

import android.app.Notification;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/** 系统通知使用权由用户授予；不读取未选择应用的正文，不补扫旧通知。 */
public final class PhoneNotificationListener extends NotificationListenerService {
    @Override public void onListenerConnected() { PhoneNotifications.state(this).listener(true); }
    @Override public void onListenerDisconnected() { PhoneNotifications.state(this).listener(false); }
    @Override public void onDestroy() { PhoneNotifications.state(this).listener(false); super.onDestroy(); }

    @Override public void onNotificationPosted(StatusBarNotification posted) {
        if (posted == null || !Process.myUserHandle().equals(posted.getUser())) { return; }
        NotificationSyncState state = PhoneNotifications.state(this);
        long now = SystemClock.elapsedRealtime();
        String pkg = posted.getPackageName();
        if (!state.accepts(pkg, now)) { return; }
        Notification note = posted.getNotification();
        if (note == null || (note.flags & (Notification.FLAG_ONGOING_EVENT | Notification.FLAG_GROUP_SUMMARY)) != 0) { return; }
        try {
            Bundle extras = note.extras;
            if (extras == null) { return; }
            String title = text(extras.getCharSequence(Notification.EXTRA_TITLE));
            String body = text(extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
            if (body.isEmpty()) { body = text(extras.getCharSequence(Notification.EXTRA_TEXT)); }
            if (body.isEmpty()) {
                CharSequence[] lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
                if (lines != null && lines.length > 0) { body = text(lines[lines.length - 1]); }
            }
            String app = pkg;
            try { app = getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0)).toString(); }
            catch (PackageManager.NameNotFoundException ignored) { }
            state.posted(pkg, posted.getKey(), app, title, body, false, false, now);
        } catch (RuntimeException malformed) {
            // 来自其他应用的 extras 可能不符合类型；不让一个通知断掉整个监听。
        }
    }
    @Override public void onNotificationRemoved(StatusBarNotification posted) {
        if (posted != null) { PhoneNotifications.state(this).removed(posted.getKey()); }
    }
    private static String text(CharSequence value) { return value == null ? "" : value.toString(); }
}
