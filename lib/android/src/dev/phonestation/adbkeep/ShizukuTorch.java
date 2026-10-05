package dev.phonestation.adbkeep;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Parcel;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import rikka.shizuku.Shizuku;

/** 灯的生命周期独立于单次 shell；进程停止、升级或撤销授权后不自动重开灯。 */
final class ShizukuTorch {
    private static Connection connection;
    private static Shizuku.UserServiceArgs serviceArgs;

    static synchronized Json status(Context context) {
        try { return call(context, TorchUserService.STATUS, false, () -> {}); }
        catch (FileFailure error) { return Json.obj().put("available", false).put("on", Json.nul()).put("reason", error.getMessage()); }
    }

    static synchronized Json set(Context context, boolean on) { return set(context, on, () -> {}); }

    static synchronized Json set(Context context, boolean on, Runnable authorize) { return call(context, TorchUserService.SET, on, authorize); }

    private static Json call(Context context, int code, boolean on, Runnable authorize) {
        authorize.run();
        Json status = ShizukuShell.status(context);
        if (!status.get("available").boolValue()) { close(); throw new FileFailure(status.get("reason").string()); }
        if (connection == null || connection.binder == null || !connection.binder.isBinderAlive()) {
            close();
            serviceArgs = new Shizuku.UserServiceArgs(new ComponentName(context, TorchUserService.class))
                    .tag("station-torch").processNameSuffix("torch").daemon(false).version(58);
            connection = new Connection();
            try {
                Shizuku.bindUserService(serviceArgs, connection);
                if (!connection.ready.await(5, TimeUnit.SECONDS) || connection.binder == null) {
                    close(); throw new FileFailure("手电筒服务未连接，请检查手机上的 Shizuku");
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt(); close(); throw new FileFailure("手电筒连接被中断");
            } catch (RuntimeException error) { close(); throw new FileFailure("手电筒服务未连接，请检查手机上的 Shizuku"); }
        }
        Parcel input = Parcel.obtain(), output = Parcel.obtain();
        try {
            authorize.run();
            input.writeInterfaceToken(TorchUserService.DESCRIPTOR);
            if (code == TorchUserService.SET) { input.writeInt(on ? 1 : 0); }
            if (!connection.binder.transact(code, input, output, 0)) { throw new FileFailure("请更新手机端手电筒服务"); }
            output.readException();
            Json result = Json.parse(output.readString());
            if (result.get("error") != null) { throw new FileFailure(result.get("error").string()); }
            return result;
        } catch (android.os.RemoteException | SecurityException error) {
            close(); throw new FileFailure("手电筒连接中断；请核实结果，不要自动重做");
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
