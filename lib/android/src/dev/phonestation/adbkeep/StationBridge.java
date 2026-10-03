package dev.phonestation.adbkeep;

import android.app.BroadcastOptions;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** 手机侧工具。状态只读。亮屏只写 stay-awake.sh 那一对值。 */
final class StationBridge implements StationHost {
    /** 打开文件失败时留下的那条。会话提醒占 2 和 3，旧的 MCP 单独通知占 4。 */
    private static final int OPEN_ID = 5;
    private static final String OPEN_TAG = "open";

    private final Context context;
    private final FileOps files;

    StationBridge(Context context, FileOps files) {
        this.context = context;
        this.files = files;
    }

    @Override
    public Json shellStatus() {
        return ShizukuShell.status(context);
    }

    @Override
    public Json shellExecute(ShellRequest request) {
        return ShizukuShell.execute(context, request);
    }

    @Override
    public Json status() {
        BatteryManager battery = context.getSystemService(BatteryManager.class);
        AudioManager audio = context.getSystemService(AudioManager.class);
        if (battery == null || audio == null) {
            throw new FileFailure("读不了手机状态");
        }
        Json volume = files.volume();
        int timeout = systemInt(Settings.System.SCREEN_OFF_TIMEOUT);
        int plugged = globalInt(Settings.Global.STAY_ON_WHILE_PLUGGED_IN);
        HostProbe host = HostProbe.current(context);
        return Json.obj()
                .put("connected", host.linked)
                .put("connectionType", host.connectionType)
                .put("remoteConnected", host.remoteConnected)
                .put("remoteChecking", host.remoteChecking)
                .put("remoteState", PhoneRelayClient.connectionLabel(context))
                .put("health", PhoneHealth.read(context))
                .put("batteryPercent", battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
                .put("charging", battery.isCharging())
                .put("ringer", ringer(audio.getRingerMode()))
                .put("wifiConnected", wifiConnected())
                .put("adbEnabled", globalInt(Settings.Global.ADB_ENABLED) == 1)
                .put("adbWifiEnabled", globalInt("adb_wifi_enabled") == 1)
                .put("screenOffTimeoutMs", timeout)
                .put("stayOnWhilePluggedIn", plugged)
                .put("stayAwake", StayAwake.held(timeout, plugged))
                .put("freeBytes", volume.get("freeBytes").longValue())
                .put("totalBytes", volume.get("totalBytes").longValue());
    }

    @Override
    public Json stayAwake(boolean on) {
        boolean timeoutOk = Settings.System.putInt(
                context.getContentResolver(),
                Settings.System.SCREEN_OFF_TIMEOUT,
                StayAwake.timeout(on));
        boolean pluggedOk = Settings.Global.putInt(
                context.getContentResolver(),
                Settings.Global.STAY_ON_WHILE_PLUGGED_IN,
                StayAwake.plugged(on));
        if (!timeoutOk || !pluggedOk) {
            throw new FileFailure("写不了息屏设置");
        }
        int timeout = systemInt(Settings.System.SCREEN_OFF_TIMEOUT);
        int plugged = globalInt(Settings.Global.STAY_ON_WHILE_PLUGGED_IN);
        if (timeout != StayAwake.timeout(on) || plugged != StayAwake.plugged(on)) {
            throw new FileFailure("息屏设置没有写成");
        }
        return Json.obj()
                .put("stayAwake", StayAwake.held(timeout, plugged))
                .put("screenOffTimeoutMs", timeout)
                .put("stayOnWhilePluggedIn", plugged);
    }

    @Override
    public Json notify(String title, String text, String agent, String sound) {
        Intent intent = new Intent(AlertNote.ACTION);
        intent.setClass(context, AlertReceiver.class);
        intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        intent.putExtra("title", title);
        intent.putExtra("text", text);
        if (sound != null && !sound.isEmpty()) {
            intent.putExtra("sound", sound);
        }
        if (agent != null && !agent.isEmpty()) {
            intent.putExtra("agent", agent);
        }
        final int[] code = new int[] {0};
        final String[] mode = new String[] {""};
        final CountDownLatch done = new CountDownLatch(1);
        Bundle options = null;
        if (Build.VERSION.SDK_INT >= 34) {
            options = BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle();
        }
        context.sendOrderedBroadcast(
                intent,
                null,
                options,
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context ignored, Intent result) {
                        code[0] = getResultCode();
                        Bundle extras = getResultExtras(false);
                        if (extras != null) {
                            mode[0] = extras.getString("mode", "");
                        }
                        done.countDown();
                    }
                },
                null,
                0,
                null,
                null);
        try {
            if (!done.await(15, TimeUnit.SECONDS)) {
                throw new FileFailure("提醒没有在时限内完成");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new FileFailure("提醒被打断");
        }
        Json out = Json.obj()
                .put("title", title)
                .put("text", text)
                .put("mode", mode[0])
                .put("agent", agent == null ? "" : agent);
        if (code[0] == AlertSoundPlan.PLAYED) {
            return out.put("posted", true).put("sound", "played");
        }
        if (code[0] == AlertSoundPlan.POSTED_SILENT) {
            return out.put("posted", true).put("sound", "silent");
        }
        if (code[0] == AlertSoundPlan.SOUND_FAILED) {
            return out.put("posted", true).put("sound", "failed");
        }
        if (code[0] == AlertSoundPlan.MISSING) {
            throw new FileFailure("没有铃声，这次不发通知");
        }
        throw new FileFailure("提醒没有发出");
    }

    @Override
    public Json clipboard(String text) {
        ClipboardManager clips = context.getSystemService(ClipboardManager.class);
        if (clips == null) {
            throw new FileFailure("没有剪贴板");
        }
        clips.setPrimaryClip(ClipData.newPlainText("手机工位", text));
        return Json.obj().put("copied", true).put("characters", text.length());
    }

    @Override public Json clipboardGet() { return SharedClipboard.readPhone(context); }
    @Override public Json clipboardState(Json args) { return SharedClipboard.status(context, args); }
    @Override public Json clipboardConfigure(Json args) { return SharedClipboard.configure(context, args); }
    @Override public Json clipboardExchange(Json args) { return SharedClipboard.exchange(context, args); }
    @Override public Json notificationStatus() { return PhoneNotifications.status(context); }
    @Override public Json notificationConfigure(Json args) { return PhoneNotifications.configure(context, args); }
    @Override public Json notificationPoll(Json args) { return PhoneNotifications.poll(context, args); }
    @Override public Json notificationIcon(String packageName) { return NotificationAppIcon.read(context, packageName); }

    @Override
    public Json open(String input) {
        Path path = files.openable(input);
        String name = path.getFileName() == null ? path.toString() : path.getFileName().toString();
        String mime = FileTypes.mime(name);
        Intent view = viewIntent(SharedFileProvider.uri(path), mime);
        Json out = Json.obj().put("path", path.toString()).put("mime", mime);
        try {
            context.startActivity(view);
            return out.put("opened", true).put("notified", false);
        } catch (ActivityNotFoundException error) {
            throw new FileFailure("没有应用能打开这个文件");
        } catch (RuntimeException blocked) {
            postOpen(name, path, view);
            return out.put("opened", false)
                    .put("notified", true)
                    .put("reason", "系统没有允许从后台打开，下拉栏里有一条，点一下打开");
        }
    }

    private void postOpen(String name, Path path, Intent view) {
        if (Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            throw new FileFailure("系统没有允许从后台打开，通知也没开");
        }
        AlertChannel.ensure(context);
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            throw new FileFailure("系统没有允许从后台打开");
        }
        PendingIntent pending = PendingIntent.getActivity(
                context,
                OPEN_ID,
                view,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(context, AlertNote.QUIET_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_station)
                .setContentTitle("打开文件")
                .setContentText(name)
                .setStyle(new Notification.BigTextStyle().bigText(path.toString()))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build();
        manager.notify(OPEN_TAG, OPEN_ID, notification);
    }

    private static Intent viewIntent(Uri uri, String mime) {
        Intent view = new Intent(Intent.ACTION_VIEW);
        view.setDataAndType(uri, mime);
        view.setClipData(ClipData.newRawUri("", uri));
        view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return view;
    }

    private boolean wifiConnected() {
        ConnectivityManager networks = context.getSystemService(ConnectivityManager.class);
        if (networks == null) {
            return false;
        }
        Network[] all = networks.getAllNetworks();
        for (int i = 0; i < all.length; i++) {
            NetworkCapabilities caps = networks.getNetworkCapabilities(all[i]);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return true;
            }
        }
        return false;
    }

    private static String ringer(int mode) {
        if (mode == AudioManager.RINGER_MODE_SILENT) {
            return "silent";
        }
        if (mode == AudioManager.RINGER_MODE_VIBRATE) {
            return "vibrate";
        }
        return "normal";
    }

    private int systemInt(String key) {
        try {
            return Settings.System.getInt(context.getContentResolver(), key, -1);
        } catch (SecurityException error) {
            throw new FileFailure("读不了系统设置");
        }
    }

    private int globalInt(String key) {
        try {
            return Settings.Global.getInt(context.getContentResolver(), key, -1);
        } catch (SecurityException error) {
            throw new FileFailure("读不了系统设置");
        }
    }
}
