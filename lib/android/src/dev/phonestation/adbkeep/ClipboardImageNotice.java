package dev.phonestation.adbkeep;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;

/** One quiet receipt for the latest image, posted only after the album item is published. */
final class ClipboardImageNotice {
    private static final String CHANNEL = "clipboard-images";
    private static final String TAG = "clipboard-image";
    private static final int ID = 6;

    static void post(Context context, Uri uri) {
        // Notification permission and channel choices never determine whether an image can be saved.
        try {
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) { return; }
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager == null || !manager.areNotificationsEnabled()) { return; }
            NotificationChannel channel = new NotificationChannel(CHANNEL, StationText.translate("剪贴板图片已保存"),
                    NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription(StationText.translate("Mac 图片保存到相册后的提示，点击查看图片。默认无声、无震动。"));
            channel.setSound(null, null);
            channel.enableVibration(false);
            channel.setShowBadge(false);
            manager.createNotificationChannel(channel);
            Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/png");
            view.setClipData(ClipData.newRawUri("", uri));
            view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            PendingIntent pending = PendingIntent.getActivity(context, ID, view,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification receipt = new Notification.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ic_stat_station)
                    .setContentTitle(StationText.translate("Mac 图片已保存到相册"))
                    .setContentText(StationText.translate("已保存到「手机工位」，点此查看图片"))
                    .setContentIntent(pending)
                    .setCategory(Notification.CATEGORY_STATUS)
                    .setVisibility(Notification.VISIBILITY_PRIVATE)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .setShowWhen(true)
                    .build();
            manager.notify(TAG, ID, receipt);
        } catch (RuntimeException unavailable) {
            // The published image is already a success. Never delete it or retry its import for a failed notice.
            android.util.Log.w("StationClipboard", "Image saved; notification unavailable");
        }
    }
}
