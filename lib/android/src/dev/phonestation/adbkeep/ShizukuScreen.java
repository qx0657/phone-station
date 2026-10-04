package dev.phonestation.adbkeep;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Parcel;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import rikka.shizuku.Shizuku;

/** A dedicated non-daemon UserService, bound only after an explicit open. */
final class ShizukuScreen {
    private static Connection connection;
    private static Shizuku.UserServiceArgs serviceArgs;

    static synchronized Json call(Context context, Json request, boolean start) {
        String id = request.get("sessionId").string();
        Json status = ShizukuShell.status(context);
        if (!status.get("available").boolValue()) { close(); throw new FileFailure(status.get("reason").string()); }
        if (connection == null || connection.binder == null || !connection.binder.isBinderAlive()) {
            close();
            if (!start) { return Json.obj().put("sessionId", id).put("state", "lost"); }
            serviceArgs = new Shizuku.UserServiceArgs(new ComponentName(context, ScreenUserService.class))
                    .tag("station-screen").processNameSuffix("screen").daemon(false).version(62);
            connection = new Connection();
            try {
                Shizuku.bindUserService(serviceArgs, connection);
                if (!connection.ready.await(5, TimeUnit.SECONDS) || connection.binder == null) {
                    close(); throw new FileFailure("远程投屏服务没有连上");
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt(); close(); throw new FileFailure("投屏创建结果未确认，请查询原编号");
            } catch (RuntimeException error) { close(); throw new FileFailure("投屏创建结果未确认，请查询原编号"); }
        }
        Parcel input = Parcel.obtain(), output = Parcel.obtain();
        try {
            input.writeInterfaceToken(ScreenUserService.DESCRIPTOR);
            input.writeString(request.emit());
            if (!connection.binder.transact(ScreenUserService.CALL, input, output, 0)) { throw new FileFailure("请更新手机工位以支持远程投屏"); }
            output.readException();
            Json result = Json.parse(output.readString());
            if (result.get("error") != null) { throw new FileFailure(result.get("error").string()); }
            return result;
        } catch (android.os.RemoteException | SecurityException error) {
            close(); throw new FileFailure("投屏连接中断；查询原会话，不重发输入");
        } finally { input.recycle(); output.recycle(); }
    }
    static synchronized void close() {
        if (connection != null && serviceArgs != null) {
            try { Shizuku.unbindUserService(serviceArgs, connection, true); } catch (RuntimeException ignored) {}
        }
        connection = null; serviceArgs = null;
    }
    private static final class Connection implements ServiceConnection {
        final CountDownLatch ready = new CountDownLatch(1);
        volatile IBinder binder;
        public void onServiceConnected(ComponentName name, IBinder service) { binder = service; ready.countDown(); }
        public void onServiceDisconnected(ComponentName name) { binder = null; ready.countDown(); }
    }
}
