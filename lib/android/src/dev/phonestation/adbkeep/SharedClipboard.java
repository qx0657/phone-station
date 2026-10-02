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
    private static android.content.SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("clipboard", Context.MODE_PRIVATE);
    }
    static boolean shared(Context context) { return prefs(context).getBoolean("shared", false); }
    static boolean automatic(Context context) { return prefs(context).getBoolean("automatic", true); }
    static synchronized Json configure(Context context, Json args) {
        boolean beforeShared = shared(context);
        boolean beforeAutomatic = automatic(context);
        android.content.SharedPreferences.Editor edit = prefs(context).edit();
        if (args.get("shared") != null) { edit.putBoolean("shared", args.get("shared").boolValue()); }
        if (args.get("automatic") != null) { edit.putBoolean("automatic", args.get("automatic").boolValue()); }
        if (!edit.commit()) { throw new FileFailure("剪贴板设置没有保存，请重试"); }
        if (beforeShared != shared(context) || beforeAutomatic != automatic(context)) { STATE.clear(); }
        if (!shared(context)) { close(); }
        return status(context);
    }
    static synchronized Json status(Context context) {
        return STATE.snapshot(SystemClock.elapsedRealtime()).put("shared", shared(context))
                .put("automatic", automatic(context));
    }
    static synchronized Json exchange(Context context, Json args) {
        if (!shared(context)) { return status(context).put("appliedMac", false); }
        try {
            Json result = STATE.exchange(new ClipboardState.Access() {
                public ClipboardState.Clip read() {
                    KeyguardManager lock = context.getSystemService(KeyguardManager.class);
                    if (lock != null && lock.isDeviceLocked()) { return new ClipboardState.Clip("locked", null); }
                    Json clip = call(context, ClipboardUserService.READ, null);
                    return new ClipboardState.Clip(clip.get("kind").string(),
                            clip.get("text").isNull() ? null : clip.get("text").string(),
                            clip.get("sourceVersion") == null ? 0 : clip.get("sourceVersion").longValue());
                }
                public void write(String text) { call(context, ClipboardUserService.WRITE, text); }
            }, args, automatic(context), SystemClock.elapsedRealtime());
            return result.put("shared", true).put("automatic", automatic(context));
        } catch (FileFailure unavailable) {
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
        Json status = ShizukuShell.status(context);
        if (!status.get("available").boolValue()) { close(); throw new FileFailure(status.get("reason").string()); }
        if (connection == null || connection.binder == null || !connection.binder.isBinderAlive()) {
            close();
            serviceArgs = new Shizuku.UserServiceArgs(new ComponentName(context, ClipboardUserService.class))
                    .tag("station-clipboard").processNameSuffix("clipboard").daemon(false).version(42);
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
        connection = null; serviceArgs = null; STATE.clear();
    }
    private static final class Connection implements ServiceConnection {
        final CountDownLatch ready = new CountDownLatch(1);
        volatile IBinder binder;
        public void onServiceConnected(ComponentName name, IBinder service) { binder = service; ready.countDown(); }
        public void onServiceDisconnected(ComponentName name) { binder = null; ready.countDown(); }
    }
}
