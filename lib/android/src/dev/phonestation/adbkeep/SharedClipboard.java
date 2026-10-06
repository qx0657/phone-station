package dev.phonestation.adbkeep;

import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Parcel;
import android.os.SystemClock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import rikka.shizuku.Shizuku;

final class SharedClipboard {
    private static final ClipboardState STATE = new ClipboardState();
    private static Connection connection;
    private static Shizuku.UserServiceArgs serviceArgs;
    private static volatile String lastFailure = "";
    private static volatile long macContact = -1;
    private static volatile String phoneKind = "";
    private static volatile String macKind = "";
    private static volatile long leaseAt = -1;
    private static IBinder watchedBinder;
    private static final android.os.Binder changes = new android.os.Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code != FIRST_CALL_TRANSACTION || (android.os.Binder.getCallingUid() != 2000 && android.os.Binder.getCallingUid() != 0)) { return false; }
            data.enforceInterface(ClipboardUserService.CHANGES);
            PhoneRelayClient.event("clipboard");
            return true;
        }
    };
    static synchronized boolean lease(Context context, String client) {
        long now = SystemClock.elapsedRealtime();
        boolean active = !client.isEmpty() && StationFeatures.active(context, "clipboard") && STATE.lease(client, now);
        IBinder binder = connection == null ? null : connection.binder;
        if (active && binder != null && binder.isBinderAlive()) {
            if (watchedBinder == binder || watch(binder, changes)) { watchedBinder = binder; leaseAt = now; return true; }
        }
        if (watchedBinder != null) { watch(watchedBinder, null); watchedBinder = null; }
        leaseAt = -1; STATE.lease("", now); return false;
    }
    private static boolean watch(IBinder binder, IBinder observer) {
        Parcel input = Parcel.obtain(), output = Parcel.obtain();
        try {
            input.writeInterfaceToken(ClipboardUserService.DESCRIPTOR); input.writeStrongBinder(observer);
            if (!binder.transact(ClipboardUserService.WATCH, input, output, 0)) { return false; }
            output.readException();
            Json result = Json.parse(output.readString());
            return result.get("watching") != null && result.get("watching").boolValue();
        } catch (android.os.RemoteException | RuntimeException error) { return false; }
        finally { input.recycle(); output.recycle(); }
    }
    // Home diagnostics must not wait on a Shizuku bind or read any clipboard content.
    static Json health(Context context) {
        if (!StationFeatures.master(context)) { return Json.obj().put("shared", shared(context)).put("automatic", automatic(context)).put("ready", false).put("status", "手机工位已暂停").put("reason", "请在首页开启总开关，子开关选择已保留").put("action", "master"); }
        long now = SystemClock.elapsedRealtime();
        Json state = ClipboardAvailability.present(shared(context), automatic(context), (FileMcpService.listening() || PhoneRelayClient.connected()),
                HostProbe.current(context).linked, ShizukuShell.status(context),
                (macContact >= 0 && now >= macContact && now - macContact < 10_000L) || (leaseAt >= 0 && now >= leaseAt && now - leaseAt < 45_000L), lastFailure,
                HostProbe.remoteOnly(context) && !RemoteStore.permission(context, "personal"));
        return ClipboardAvailability.withContent(state, phoneKind, macKind);
    }
    private static android.content.SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("clipboard", Context.MODE_PRIVATE);
    }
    static boolean shared(Context context) { return prefs(context).getBoolean("shared", StationFeatures.legacy(context)); }
    static boolean automatic(Context context) { return prefs(context).getBoolean("automatic", true); }
    static boolean images(Context context) { return prefs(context).getBoolean("images", false); }
    static synchronized Json configure(Context context, Json args) {
        boolean beforeShared = shared(context);
        boolean beforeAutomatic = automatic(context);
        boolean beforeImages = images(context);
        android.content.SharedPreferences.Editor edit = prefs(context).edit();
        if (args.get("shared") != null) { edit.putBoolean("shared", args.get("shared").boolValue()); }
        if (args.get("automatic") != null) { edit.putBoolean("automatic", args.get("automatic").boolValue()); }
        else if (args.get("shared") != null && args.get("shared").boolValue()) {
            edit.putBoolean("automatic", true);
        }
        if (args.get("images") != null) { edit.putBoolean("images", args.get("images").boolValue()); }
        if (!edit.commit()) { throw new FileFailure("剪贴板设置没有保存，请重试"); }
        if (beforeShared != shared(context) || beforeAutomatic != automatic(context) || beforeImages != images(context)) {
            STATE.clear(); macContact = -1; lastFailure = ""; phoneKind = ""; macKind = "";
        }
        if (!shared(context)) { close(); }
        StationFeatures.publish(context);
        return status(context);
    }
    static synchronized Json status(Context context) {
        Json availability = health(context);
        Json result = STATE.snapshot(SystemClock.elapsedRealtime()).put("shared", shared(context))
                .put("automatic", automatic(context)).put("images", images(context)).put("imagesSupported", true).put("recoverySupported", true)
                .put("availability", availability);
        if (shared(context) && !ShizukuShell.status(context).get("available").boolValue()) {
            result.put("phone", Json.nul()).put("error", availability.get("reason").string());
        }
        return result;
    }
    static synchronized Json status(Context context, Json args) {
        if (!shared(context) || args.get("refresh") == null || !args.get("refresh").boolValue()) { return status(context); }
        try {
            recordKinds(STATE.refresh(access(context), SystemClock.elapsedRealtime()));
            lastFailure = "";
            return status(context);
        } catch (FileFailure unavailable) {
            lastFailure = unavailable.getMessage();
            return status(context).put("phone", Json.nul()).put("error", unavailable.getMessage());
        }
    }
    private static ClipboardState.Access access(Context context) {
        return new ClipboardState.Access() {
            public ClipboardState.Clip read() {
                KeyguardManager lock = context.getSystemService(KeyguardManager.class);
                if (lock != null && lock.isDeviceLocked()) { return new ClipboardState.Clip("locked", null); }
                Json clip = call(context, ClipboardUserService.READ, null);
                return new ClipboardState.Clip(clip.get("kind").string(),
                        clip.get("text").isNull() ? null : clip.get("text").string(),
                        clip.get("sourceVersion") == null ? 0 : clip.get("sourceVersion").longValue());
            }
            public void write(String text) { call(context, ClipboardUserService.WRITE, text); }
            public void saveImage(byte[] png) { ClipboardImages.save(context, png); }
        };
    }
    static synchronized Json exchange(Context context, Json args) {
        if (!StationFeatures.active(context, "clipboard")) { return status(context).put("appliedMac", false); }
        try {
            Json result = STATE.exchange(access(context), args, automatic(context), images(context), SystemClock.elapsedRealtime());
            recordKinds(result);
            lastFailure = "";
            if (args.get("clientId") != null) { macContact = SystemClock.elapsedRealtime(); }
            return result.put("shared", true).put("automatic", automatic(context))
                    .put("images", images(context)).put("imagesSupported", true).put("recoverySupported", true)
                    .put("availability", health(context));
        } catch (FileFailure unavailable) {
            lastFailure = unavailable.getMessage();
            return status(context).put("phone", Json.nul()).put("error", unavailable.getMessage());
        }
    }
    static synchronized Json write(Context context, String text) {
        call(context, ClipboardUserService.WRITE, text);
        return Json.obj().put("copied", true).put("characters", text.length());
    }
    static synchronized Json readPhone(Context context) {
        KeyguardManager lock = context.getSystemService(KeyguardManager.class);
        if (lock != null && lock.isDeviceLocked()) { return new ClipboardState.Clip("locked", null).json(); }
        return call(context, ClipboardUserService.READ, null);
    }
    private static Json call(Context context, int code, String text) {
        StationFeatures.require(context, code == ClipboardUserService.READ ? "station_clipboard_get" : "station_clipboard_set");
        Json status = ShizukuShell.status(context);
        if (!status.get("available").boolValue()) { close(); throw new FileFailure(status.get("reason").string()); }
        if (connection == null || connection.binder == null || !connection.binder.isBinderAlive()) {
            close();
            serviceArgs = new Shizuku.UserServiceArgs(new ComponentName(context, ClipboardUserService.class))
                    .tag("station-clipboard").processNameSuffix("clipboard").daemon(false).version(69);
            Connection next = new Connection();
            connection = next;
            try {
                Shizuku.bindUserService(serviceArgs, next);
                if (!next.ready.await(5, TimeUnit.SECONDS) || next.binder == null) {
                    close(); throw new FileFailure("剪贴板后台服务未连接，请检查 Shizuku");
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt(); close(); throw new FileFailure("剪贴板连接被中断");
            } catch (RuntimeException error) { close(); throw new FileFailure("剪贴板后台服务未连接，请检查 Shizuku"); }
        }
        Parcel input = Parcel.obtain();
        Parcel output = Parcel.obtain();
        try {
            input.writeInterfaceToken(ClipboardUserService.DESCRIPTOR);
            if (code == ClipboardUserService.WRITE) { input.writeString(text); }
            if (!connection.binder.transact(code, input, output, 0)) { throw new FileFailure("请更新手机端剪贴板服务"); }
            StationFeatures.require(context, code == ClipboardUserService.READ ? "station_clipboard_get" : "station_clipboard_set");
            output.readException();
            Json result = Json.parse(output.readString());
            if (result.get("error") != null) { throw new FileFailure(result.get("error").string()); }
            return result;
        } catch (android.os.RemoteException | SecurityException error) {
            close(); throw new FileFailure("Shizuku 剪贴板连接已中断，请核实复制结果");
        } finally { input.recycle(); output.recycle(); }
    }
    static synchronized void close() {
        if (connection != null && serviceArgs != null) {
            try { Shizuku.unbindUserService(serviceArgs, connection, true); } catch (RuntimeException ignored) {}
        }
        connection = null; serviceArgs = null; watchedBinder = null; leaseAt = -1; STATE.clear();
        macContact = -1; lastFailure = ""; phoneKind = ""; macKind = "";
    }
    private static void recordKinds(Json state) {
        Json phone = state.get("phone"), mac = state.get("mac");
        phoneKind = phone == null || phone.isNull() ? "" : phone.get("kind").string();
        macKind = mac == null || mac.isNull() ? "" : mac.get("kind").string();
    }
    private static final class Connection implements ServiceConnection {
        final CountDownLatch ready = new CountDownLatch(1);
        volatile IBinder binder;
        public void onServiceConnected(ComponentName name, IBinder service) { binder = service; ready.countDown(); }
        public void onServiceDisconnected(ComponentName name) { binder = null; ready.countDown(); }
    }
}
