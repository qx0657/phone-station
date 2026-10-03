package dev.phonestation.adbkeep;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.BroadcastReceiver.PendingResult;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.util.Log;

/** 电脑经 adb 发来的会话提醒。常驻状态那条不动。 */
public final class AlertReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !AlertNote.ACTION.equals(intent.getAction())) {
            setResultCode(0);
            return;
        }
        if (Build.VERSION.SDK_INT >= 34
                && !AlertSender.allowed(getSentFromUid(), android.os.Process.myUid(), intent.getFlags())) {
            Log.w(KeeperEngine.TAG, "alert rejected");
            setResultCode(0);
            return;
        }
        AlertNote note = AlertNote.parse(
                intent.getStringExtra("title"),
                intent.getStringExtra("text"),
                KeeperStore.alertStacks(context) ? "stack" : "replace",
                intent.getStringExtra("agent"),
                System.currentTimeMillis(),
                System.nanoTime());
        if (note == null) {
            setResultCode(0);
            return;
        }
        String setting = Settings.System.getString(
                context.getContentResolver(), Settings.System.NOTIFICATION_SOUND);
        AlertSoundPlan chosen = AlertSoundPlan.choose(
                intent.getStringExtra("sound"), KeeperStore.alertSound(context), setting);
        boolean silent = chosen.kind == AlertSoundPlan.SILENT;
        AlertSoundPlan sound = silent ? chosen : AlertSound.openable(context, chosen);
        if (!silent && sound.kind == AlertSoundPlan.MISSING) {
            Log.w(KeeperEngine.TAG, "alert sound missing");
            setResultCode(AlertSoundPlan.MISSING);
            return;
        }
        AlertChannel.ensure(context);
        if (Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(KeeperEngine.TAG, "alert denied");
            setResultCode(0);
            return;
        }
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            setResultCode(0);
            return;
        }

        Intent open = new Intent(context, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(
                context,
                AlertNote.REPLACE_ID,
                open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(
                context, AlertChannel.id(KeeperStore.alertPops(context)))
                .setSmallIcon(smallIcon(note.agent))
                .setContentTitle(note.title)
                .setContentText(note.text)
                .setStyle(new Notification.BigTextStyle().bigText(note.text))
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true)
                .setContentIntent(pending);
        if (!note.stack) {
            for (StatusBarNotification active : manager.getActiveNotifications()) {
                if (AlertNote.isStacked(active.getTag(), active.getId())) {
                    manager.cancel(active.getTag(), active.getId());
                }
            }
        }
        manager.notify(note.tag, note.id, builder.build());
        Bundle extras = new Bundle();
        extras.putString("mode", note.stack ? "stack" : "replace");
        setResultExtras(extras);
        Log.i(KeeperEngine.TAG, note.stack ? "alert stack" : "alert replace");
        if (silent) {
            Log.i(KeeperEngine.TAG, "alert sound silent");
            setResultCode(AlertSoundPlan.POSTED_SILENT);
            return;
        }
        Log.i(KeeperEngine.TAG, sound.kind == AlertSoundPlan.FILE
                ? "alert sound file " + sound.value
                : "alert sound uri " + sound.value);
        PendingResult async = goAsync();
        Context app = context.getApplicationContext();
        new Thread(() -> {
            int code = AlertSound.play(app, sound);
            Log.i(KeeperEngine.TAG, code == AlertSoundPlan.PLAYED
                    ? "alert sound played"
                    : "alert sound fail");
            async.setResultCode(code);
            async.finish();
        }, "alert-sound").start();
    }

    /** 左侧图标就是 small icon。认得出 Agent 时用它的彩色图标，否则仍是手机工位。 */
    private static int smallIcon(String agent) {
        if ("Grok".equals(agent)) {
            return R.drawable.ic_agent_grok;
        }
        if ("Claude".equals(agent)) {
            return R.drawable.ic_agent_claude;
        }
        if ("Codex".equals(agent)) {
            return R.drawable.ic_agent_codex;
        }
        return R.drawable.ic_stat_station;
    }
}
