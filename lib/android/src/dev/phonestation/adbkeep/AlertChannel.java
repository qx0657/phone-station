package dev.phonestation.adbkeep;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.media.AudioAttributes;
import android.net.Uri;

/**
 * 会话提醒的两条通道。系统建好通道之后不允许应用再改重要程度，所以弹出和不弹出各一条。
 * 旧的 {@code alert}、{@code remind}、{@code popup} 和没有声音的 {@code banner} 删掉后不再使用。
 * 这台 MagicOS 会把第三方应用新建的高重要程度通道降到默认，应用再调不回去。
 * 通道设置里勾上「横幅通知」才会锁回高。通道没有声音时，这个勾选不会升重要程度。
 * 弹出这条因此带一段听不见的声音，设置里用资源名，不用资源号。听得见的铃声仍由电脑上的播放负责。
 */
final class AlertChannel {
    private static final String[] RETIRED = {"alert", "remind", "popup", "banner"};

    private AlertChannel() {}

    static String id(boolean popup) {
        return popup ? AlertNote.CHANNEL_ID : AlertNote.QUIET_CHANNEL_ID;
    }

    static void ensure(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        for (String retired : RETIRED) {
            if (manager.getNotificationChannel(retired) != null) {
                manager.deleteNotificationChannel(retired);
            }
        }
        NotificationChannel popup = new NotificationChannel(
                AlertNote.CHANNEL_ID,
                StationText.translate(AlertNote.CHANNEL_NAME),
                NotificationManager.IMPORTANCE_HIGH);
        popup.setDescription(StationText.translate("电脑上的会话提醒。在屏幕上弹出，不另响一声。"));
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        popup.setSound(
                Uri.parse("android.resource://" + context.getPackageName() + "/raw/silence"),
                attributes);
        popup.enableVibration(false);
        popup.setShowBadge(false);
        manager.createNotificationChannel(popup);
        create(
                manager,
                AlertNote.QUIET_CHANNEL_ID,
                AlertNote.QUIET_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT,
                "电脑上的会话提醒。只出现在下拉栏。");
    }

    static boolean pops(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            return false;
        }
        NotificationChannel channel = manager.getNotificationChannel(AlertNote.CHANNEL_ID);
        return channel != null && channel.getImportance() >= NotificationManager.IMPORTANCE_HIGH;
    }

    private static void create(
            NotificationManager manager, String id, String name, int importance, String description) {
        NotificationChannel channel = new NotificationChannel(id, StationText.translate(name), importance);
        channel.setDescription(StationText.translate(description));
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }
}
