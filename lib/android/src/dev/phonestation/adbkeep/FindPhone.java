package dev.phonestation.adbkeep;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** A bounded alarm, independent of notification sound and ringer mode. All mutations run on main. */
final class FindPhone {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final int NOTE = 74;
    private static final String CHANNEL = "find-phone";
    private static MediaPlayer player;
    private static volatile boolean ringing;
    private static volatile long until;
    private static Runnable timeout;
    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("find_phone", Context.MODE_PRIVATE); }

    static Json status(Context c) {
        String reason = !StationFeatures.active(c, "alerts") ? FeaturePolicy.denied(StationFeatures.master(c), "alerts")
                : Build.VERSION.SDK_INT >= 33 && c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED ? "请在手机上允许通知，以便随时停止响铃" : "";
        NotificationManager manager = c.getSystemService(NotificationManager.class);
        NotificationChannel channel = manager == null ? null : manager.getNotificationChannel(CHANNEL);
        if (reason.isEmpty() && (manager == null || !manager.areNotificationsEnabled()
                || channel != null && channel.getImportance() == NotificationManager.IMPORTANCE_NONE)) {
            reason = "请在手机上允许找手机通知，以便随时停止响铃";
        }
        return Json.obj().put("available", reason.isEmpty()).put("ringing", ringing).put("until", until).put("reason", reason);
    }

    static Json set(Context c, boolean on, Runnable authorize) {
        if (Looper.myLooper() == Looper.getMainLooper()) { return change(c, on, authorize); }
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Json> value = new AtomicReference<>();
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        AtomicBoolean expired = new AtomicBoolean();
        MAIN.post(() -> {
            try { if (!expired.get()) { value.set(change(c, on, authorize)); } }
            catch (RuntimeException e) { failure.set(e); }
            finally { done.countDown(); }
        });
        try {
            if (!done.await(5, TimeUnit.SECONDS)) { expired.set(true); throw new FileFailure("响铃结果未确认，请查看实际状态，不要重发"); }
        } catch (InterruptedException e) {
            expired.set(true); Thread.currentThread().interrupt(); throw new FileFailure("响铃结果未确认，请查看实际状态");
        }
        if (failure.get() != null) { throw failure.get(); }
        return value.get();
    }

    private static Json change(Context c, boolean on, Runnable authorize) {
        if (!on) { stop(c); return status(c); }
        authorize.run();
        Json state = status(c);
        if (!state.get("available").boolValue()) { throw new FileFailure(state.get("reason").string()); }
        if (ringing) { return state; } // Do not extend the deadline on a duplicate start.
        AudioManager audio = c.getSystemService(AudioManager.class);
        if (audio == null || audio.isVolumeFixed()) { throw new FileFailure("此设备无法调整找手机音量"); }
        recover(c);
        if (prefs(c).getBoolean("pending", false)) { throw new FileFailure("原音量尚未恢复，请稍后重试"); }
        int original = audio.getStreamVolume(AudioManager.STREAM_ALARM);
        int maximum = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM);
        if (maximum <= 0) { throw new FileFailure("此设备无法调整找手机音量"); }
        boolean muted = audio.isStreamMute(AudioManager.STREAM_ALARM);
        if (!prefs(c).edit().putBoolean("pending", true).putInt("original", original)
                .putInt("applied", maximum).putBoolean("muted", muted).commit()) {
            throw new FileFailure("无法保存原音量，未开始响铃");
        }
        try {
            audio.setStreamVolume(AudioManager.STREAM_ALARM, maximum, 0);
            audio.adjustStreamVolume(AudioManager.STREAM_ALARM, AudioManager.ADJUST_UNMUTE, 0);
            if (audio.getStreamVolume(AudioManager.STREAM_ALARM) <= 0 || audio.isStreamMute(AudioManager.STREAM_ALARM)) {
                throw new IllegalStateException("alarm stream remains muted");
            }
            player = MediaPlayer.create(c, R.raw.find_phone,
                    new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(), 0);
            if (player == null) { throw new IllegalStateException(); }
            for (AudioDeviceInfo device : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) { player.setPreferredDevice(device); break; }
            }
            player.setLooping(true);
            player.setWakeMode(c, android.os.PowerManager.PARTIAL_WAKE_LOCK);
            player.setOnErrorListener((mp, what, extra) -> { stop(c); return true; });
            player.start();
            ringing = true;
            until = System.currentTimeMillis() + 30_000;
            timeout = () -> stop(c);
            MAIN.postDelayed(timeout, 30_000);
            showNotification(c);
            return status(c);
        } catch (RuntimeException e) {
            stop(c);
            throw new FileFailure("无法播放找手机铃声，请检查闹钟音量与勿扰设置");
        }
    }

    private static void showNotification(Context c) {
        NotificationManager manager = c.getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL, StationText.translate("找手机"), NotificationManager.IMPORTANCE_HIGH);
        channel.setSound(null, null);
        manager.createNotificationChannel(channel);
        Intent intent = new Intent(c, FindPhoneStopReceiver.class);
        PendingIntent stop = PendingIntent.getBroadcast(c, NOTE, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        manager.notify(NOTE, new Notification.Builder(c, CHANNEL).setSmallIcon(R.drawable.ic_stat_station)
                .setContentTitle(StationText.translate("正在响铃找手机"))
                .setContentText(StationText.translate("点击停止响铃，30 秒后自动结束"))
                .setContentIntent(stop).setDeleteIntent(stop).setOngoing(true).setCategory(Notification.CATEGORY_ALARM)
                .addAction(new Notification.Action.Builder(null, StationText.translate("停止响铃"), stop).build()).build());
    }

    static void close(Context c) {
        if (Looper.myLooper() == Looper.getMainLooper()) { stop(c); }
        else { MAIN.post(() -> stop(c)); }
    }
    private static void stop(Context c) {
        if (timeout != null) { MAIN.removeCallbacks(timeout); timeout = null; }
        ringing = false; until = 0;
        if (player != null) { try { player.release(); } catch (RuntimeException ignored) { } finally { player = null; } }
        NotificationManager manager = c.getSystemService(NotificationManager.class);
        if (manager != null) { manager.cancel(NOTE); }
        recover(c);
    }

    /** Survives process death. Preserve volume changes made by the user during ringing. */
    static void recover(Context c) {
        SharedPreferences saved = prefs(c);
        if (ringing || !saved.getBoolean("pending", false)) { return; }
        AudioManager audio = c.getSystemService(AudioManager.class);
        if (audio == null) { return; }
        try {
            if (FindPhoneVolume.shouldRestore(audio.getStreamVolume(AudioManager.STREAM_ALARM),
                    audio.isStreamMute(AudioManager.STREAM_ALARM), saved.getInt("applied", -1))) {
                audio.setStreamVolume(AudioManager.STREAM_ALARM, saved.getInt("original", 0), 0);
                if (saved.getBoolean("muted", false)) { audio.adjustStreamVolume(AudioManager.STREAM_ALARM, AudioManager.ADJUST_MUTE, 0); }
            }
            saved.edit().clear().commit();
        } catch (RuntimeException ignored) { /* Keep the snapshot for the next recovery. */ }
    }
    private FindPhone() {}
}
