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
final class ShizukuTerminal {
    private static Connection connection;
    private static Shizuku.UserServiceArgs serviceArgs;

    static synchronized Json call(Context context, Json request, boolean start) {
        return call(context, request, start, () -> {});
    }

    static synchronized Json call(Context context, Json request, boolean start, Runnable authorize) {
        authorize.run();
        String id = request.get("sessionId").string();
        Json status = ShizukuShell.status(context);
        if (!status.get("available").boolValue()) { close(); throw new FileFailure(status.get("reason").string()); }
        if (connection == null || connection.binder == null || !connection.binder.isBinderAlive()) {
            close();
            if (!start) { return TerminalJobs.ended(id, "lost", "原会话已结束，不会重新创建"); }
            serviceArgs = new Shizuku.UserServiceArgs(new ComponentName(context, TerminalUserService.class))
                    .tag("station-terminal").processNameSuffix("terminal").daemon(false).version(61);
            connection = new Connection();
            try {
                Shizuku.bindUserService(serviceArgs, connection);
                if (!connection.ready.await(5, TimeUnit.SECONDS) || connection.binder == null) {
                    close(); throw new FileFailure("远程终端服务没有连上");
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt(); close(); throw new FileFailure("终端创建结果未确认，请查询原编号");
            } catch (RuntimeException error) { close(); throw new FileFailure("终端创建结果未确认，请查询原编号"); }
        }
        Parcel input = Parcel.obtain(), output = Parcel.obtain();
        try {
            authorize.run();
            input.writeInterfaceToken(TerminalUserService.DESCRIPTOR);
            input.writeString(request.emit());
            if (!connection.binder.transact(TerminalUserService.CALL, input, output, 0)) { throw new FileFailure("请更新手机工位以支持远程终端"); }
            output.readException();
            Json result = Json.parse(output.readString());
            if (result.get("error") != null) { throw new FileFailure(result.get("error").string()); }
            return result;
        } catch (android.os.RemoteException | SecurityException error) {
            close(); throw new FileFailure("终端连接中断；查询原会话，不重发输入");
        } finally { input.recycle(); output.recycle(); }
    }
    static synchronized void close() {
        if (connection != null && serviceArgs != null) {
            try { Shizuku.unbindUserService(serviceArgs, connection, true); } catch (RuntimeException ignored) {}
        }
        connection = null; serviceArgs = null;
    }
    static synchronized void closeSessions(Context context, java.util.Set<String> ids) {
        if (connection == null) { return; }
        for (String id : ids) {
            try { call(context, TerminalJobs.request("close", id), false); }
            catch (FileFailure ignored) { /* Never bind a service during revocation. */ }
        }
    }
    private static final class Connection implements ServiceConnection {
        final CountDownLatch ready = new CountDownLatch(1);
        volatile IBinder binder;
        public void onServiceConnected(ComponentName name, IBinder service) { binder = service; ready.countDown(); }
        public void onServiceDisconnected(ComponentName name) { binder = null; ready.countDown(); }
    }
}
