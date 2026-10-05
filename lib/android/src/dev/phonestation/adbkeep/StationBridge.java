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

    private static OperationJobs operationJobs;
    private static TerminalJobs terminalJobs;
    private static synchronized OperationJobs jobs(Context context) {
        if (operationJobs == null) { operationJobs = new OperationJobs(context.getFilesDir().toPath().resolve("operation-jobs")); }
        return operationJobs;
    }
    static synchronized void closeJobs() {
        if (operationJobs != null) { operationJobs.close(); operationJobs = null; }
        ShizukuTerminal.close(); terminalJobs = null; ShizukuScreen.close();
    }
    private static synchronized TerminalJobs terminals(Context context) {
        if (terminalJobs == null) {
            terminalJobs = new TerminalJobs(jobs(context), new TerminalJobs.Backend() {
                public Json call(Json request, boolean start) { return ShizukuTerminal.call(context, request, start); }
                public Json call(Json request, boolean start, Runnable authorize) { return ShizukuTerminal.call(context, request, start, authorize); }
            });
        }
        return terminalJobs;
    }
    private final Context context;
    private final FileOps files;
    private final java.util.function.Consumer<String> access;
    private final java.util.function.Consumer<String> terminalOwner;
    private final String localRevision;

    StationBridge(Context context, FileOps files) {
        this(context, files, null, null);
    }

    StationBridge(Context context, FileOps files, java.util.function.Consumer<String> access,
            java.util.function.Consumer<String> terminalOwner) {
        this.context = context;
        this.files = files;
        this.access = access;
        this.terminalOwner = terminalOwner;
        this.localRevision = RemoteStore.permissionRevision(context);
    }

    @Override public void authorize(String tool) {
        StationFeatures.require(context, tool);
        if (access != null) { access.accept(tool); }
        else if (!FeaturePolicy.safe(tool) && !localRevision.equals(RemoteStore.permissionRevision(context))) {
            throw new FileFailure("功能开关已变化，旧请求不会继续执行。请重新操作。");
        }
    }

    @Override public Json shellStart(String id, ShellRequest request) {
        requireVerifiedModel();
        Json args = Json.obj().put("command", request.command).put("timeoutMs", request.timeoutMs).put("maxOutputBytes", request.maxOutputBytes);
        return jobs(context).start(id, "shell", args, () -> ShizukuShell.execute(context, request, () -> authorize("station_shell_start")));
    }
    @Override public Json captureStart(String id, String requestId) {
        requireVerifiedModel(); ScreenCapture.path(requestId);
        return jobs(context).start(id, "capture", Json.obj().put("requestId", requestId), () -> screenCapture(requestId));
    }
    @Override public Json operationStatus(String id) {
        Json receipt = jobs(context).status(id);
        StationFeatures.requireJob(context, receipt);
        if (access != null) { authorize("station_operation_status"); RemoteStore.policy(context).requireJob(receipt); }
        return receipt;
    }
    @Override public Json screenOpen(String id) {
        requireVerifiedModel();
        if (!RemoteStore.enabled(context) || !RemoteStore.configured(context)) { throw new FileFailure("请先连接远程中继"); }
        RemoteStore.policy(context).require("station_screen_open");
        String revision = RemoteStore.permissionRevision(context);
        return jobs(context).start(id, "screen.open", Json.obj().put("sessionId", id), () -> {
            Runnable check = () -> {
                authorize("station_screen_open");
                if (!RemoteStore.enabled(context) || !revision.equals(RemoteStore.permissionRevision(context))) {
                    throw new FileFailure("投屏权限或远程连接已变化，尚未执行");
                }
                RemoteStore.policy(context).require("station_screen_open");
            };
            check.run();
            String token;
            try { token = RemoteStore.decryptToken(RemoteStore.encryptedToken(context)); }
            catch (Exception e) { throw new FileFailure("远程凭据不可用"); }
            Json addresses = Json.arr();
            try { for (java.net.InetAddress address : java.net.InetAddress.getAllByName(new java.net.URL(RemoteStore.endpoint(context)).getHost())) { addresses.add(Json.str(address.getHostAddress())); } }
            catch (Exception e) { throw new FileFailure("无法解析中继地址"); }
            return ShizukuScreen.call(context, Json.obj().put("op", "open").put("sessionId", id)
                    .put("endpoint", RemoteStore.endpoint(context)).put("pin", RemoteStore.pin(context)).put("token", token).put("addresses", addresses), true, check);
        });
    }
    @Override public Json screenStatus(String id) { OperationJobs.validate(id); return ShizukuScreen.call(context, Json.obj().put("op", "status").put("sessionId", id), false); }
    @Override public Json screenClose(String id) { OperationJobs.validate(id); return ShizukuScreen.call(context, Json.obj().put("op", "close").put("sessionId", id), false); }
    @Override public Json terminalOpen(String id, int columns, int rows) {
        requireVerifiedModel(); OperationJobs.validate(id);
        if (terminalOwner != null) { terminalOwner.accept(id); }
        return terminals(context).open(id, columns, rows, () -> authorize("station_terminal_open"));
    }
    @Override public Json terminalRead(String id, long offset) { return terminals(context).read(id, offset); }
    @Override public Json terminalInput(String id, long sequence, String hex) {
        requireVerifiedModel(); return terminals(context).input(id, sequence, hex);
    }
    @Override public Json terminalResize(String id, int columns, int rows) {
        requireVerifiedModel(); return terminals(context).resize(id, columns, rows);
    }
    @Override public Json terminalClose(String id) { return terminals(context).close(id); }

    @Override
    public Json shellStatus() {
        Json state = ShizukuShell.status(context).put("terminalSupported", true).put("terminalProtocol", 1);
        if (!StationFeatures.active(context, "shell")) {
            state.put("available", false).put("terminalSupported", false).put("reason", FeaturePolicy.denied(StationFeatures.master(context), "shell"));
        } else if (access != null && !RemoteStore.policy(context).allows("station_shell_start")) {
            state.put("available", false).put("terminalSupported", false).put("reason", RemotePermissionInfo.denied("shell"));
        }
        return state;
    }

    @Override
    public Json shellExecute(ShellRequest request) {
        return ShizukuShell.execute(context, request, () -> authorize("station_shell_exec"));
    }

    private static boolean verifiedModel() { return "PGT-AN20".equals(Build.MODEL.replace('_', '-')); }
    private static void requireVerifiedModel() {
        if (!verifiedModel()) { throw new FileFailure("当前机型 " + Build.MODEL + " 尚未验证，停止设备操作"); }
    }

    @Override public Json controlsStatus() {
        Json shell = ShizukuShell.status(context);
        boolean verified = verifiedModel();
        boolean storage = Build.VERSION.SDK_INT < 30 || android.os.Environment.isExternalStorageManager();
        boolean settings = context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
        String unsupported = verified ? "" : "当前机型 " + Build.MODEL + " 尚未验证，停止设备操作";
        Json lamp = verified ? ShizukuTorch.status(context) : Json.obj().put("available", false)
                .put("on", Json.nul()).put("reason", unsupported);
        boolean screenAllowed = StationFeatures.active(context, "screen") && RemoteStore.policy(context).allows("station_screen_open");
        boolean controlsAllowed = access == null || RemoteStore.policy(context).allows("station_screen_capture");
        if (!StationFeatures.active(context, "torch")) { lamp = Json.obj().put("available", false).put("on", Json.nul()).put("reason", FeaturePolicy.denied(StationFeatures.master(context), "torch")); }
        else if (!controlsAllowed) { lamp = Json.obj().put("available", false).put("on", Json.nul()).put("reason", RemotePermissionInfo.denied("controls")); }
        Json permissions = Json.obj();
        for (String scope : RemotePolicy.SCOPES) { permissions.put(scope, RemoteStore.permission(context, scope)); }
        return Json.obj().put("model", Build.MODEL).put("verified", verified).put("remotePermissions", permissions).put("remotePermissionPageVersion", 1).put("features", StationFeatures.status(context))
                .put("screenAvailable", screenAllowed && verified && shell.get("available").boolValue() && RemoteStore.enabled(context) && RemoteStore.configured(context))
                .put("screenReason", !StationFeatures.active(context, "screen") ? FeaturePolicy.denied(StationFeatures.master(context), "screen") : !screenAllowed ? RemotePermissionInfo.denied("screen") : !verified ? unsupported
                        : !shell.get("available").boolValue() ? shell.get("reason").string() : "")
                .put("screenProtocol", 1)
                .put("stayAwake", StayAwake.held(systemInt(Settings.System.SCREEN_OFF_TIMEOUT), globalInt(Settings.Global.STAY_ON_WHILE_PLUGGED_IN)))
                .put("stayAwakeAvailable", StationFeatures.active(context, "awake") && controlsAllowed && verified && settings)
                .put("stayAwakeReason", !StationFeatures.active(context, "awake") ? FeaturePolicy.denied(StationFeatures.master(context), "awake") : !controlsAllowed ? RemotePermissionInfo.denied("controls") : !verified ? unsupported : settings ? "" : "请先在手机上授予写系统设置权限")
                .put("screenshotAvailable", StationFeatures.active(context, "capture") && controlsAllowed && verified && storage && shell.get("available").boolValue())
                .put("screenshotReason", !StationFeatures.active(context, "capture") ? FeaturePolicy.denied(StationFeatures.master(context), "capture") : !controlsAllowed ? RemotePermissionInfo.denied("controls") : !verified ? unsupported : !storage ? "请在手机权限页允许所有文件访问" : shell.get("reason").string())
                .put("torch", lamp);
    }

    @Override public Json screenCapture(String requestId) {
        requireVerifiedModel();
        return ScreenCapture.capture(files, request -> ShizukuShell.execute(context, request, () -> authorize("station_screen_capture")),
                requestId, () -> authorize("station_screen_capture"));
    }

    @Override public Json torch(boolean on) {
        requireVerifiedModel();
        return ShizukuTorch.set(context, on, () -> authorize("station_torch"));
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
        StayAwakeController.set(context, on);
        int timeout = systemInt(Settings.System.SCREEN_OFF_TIMEOUT);
        int plugged = globalInt(Settings.Global.STAY_ON_WHILE_PLUGGED_IN);
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
    @Override public Json notificationStatus() { return PhoneNotifications.status(context).put("remoteAllowed", RemoteStore.permission(context, "personal")).put("alertsEnabled", StationFeatures.selected(context, "alerts")).put("alertsReady", StationFeatures.active(context, "alerts") && PermissionProbe.read(context).notifications); }
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
