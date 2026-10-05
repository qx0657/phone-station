package dev.phonestation.adbkeep;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import rikka.shizuku.Shizuku;

/** 本地和中继共用。远程请求不弹授权框，也不回退到应用 UID 执行。 */
final class ShizukuShell {
    private static final Object EXECUTION = new Object();

    private ShizukuShell() {}

    static Json status(Context context) {
        PermissionCopy.ShizukuState state = ShizukuLink.read(context);
        String reason;
        switch (state) {
            case ABSENT: reason = "先在手机上安装 Shizuku"; break;
            case STOPPED: reason = "在手机上启动 Shizuku；重启手机后需要重新启动"; break;
            case DENIED: reason = "在手机工位的权限页允许使用 Shizuku"; break;
            default: reason = ""; break;
        }
        int uid = -1;
        int version = -1;
        if (state == PermissionCopy.ShizukuState.GRANTED) {
            try {
                uid = Shizuku.getUid();
                version = Shizuku.getVersion();
                if (version < 13) {
                    reason = "请升级到 Shizuku 13 或更高版本";
                } else if (uid != 2000 && uid != 0) {
                    reason = "Shizuku 没有 shell/root 身份";
                }
            } catch (RuntimeException stopped) {
                reason = "Shizuku 服务已停止，请在手机上重新启动";
            }
        }
        return Json.obj().put("available", reason.isEmpty())
                .put("state", state.name().toLowerCase(java.util.Locale.ROOT))
                .put("uid", uid < 0 ? Json.nul() : Json.num(uid))
                .put("identity", uid == 2000 ? "shell" : uid == 0 ? "root" : "unavailable")
                .put("serverVersion", version < 0 ? Json.nul() : Json.num(version))
                .put("reason", reason);
    }

    static Json execute(Context context, ShellRequest request) {
        return execute(context, request, () -> {});
    }

    static Json execute(Context context, ShellRequest request, Runnable authorize) {
        synchronized (EXECUTION) {
            authorize.run();
            requireAvailable(context);
            Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(
                    new ComponentName(context, ShellUserService.class))
                    .tag("station-shell").processNameSuffix("shell").daemon(false).version(1);
            Connection connection = new Connection();
            boolean binding = false;
            try {
                // 仅在真正执行时启动特权进程；每次结束删除，避免留下旧 APK 的执行服务。
                binding = true;
                Shizuku.bindUserService(args, connection);
                if (!connection.ready.await(5_000, TimeUnit.MILLISECONDS) || connection.binder == null) {
                    throw new FileFailure("Shizuku 执行服务没有连上，请检查手机上的 Shizuku 状态");
                }
                requireAvailable(context);
                authorize.run();
                Parcel input = Parcel.obtain();
                Parcel output = Parcel.obtain();
                try {
                    input.writeInterfaceToken(ShellUserService.DESCRIPTOR);
                    input.writeString(request.command);
                    input.writeInt(request.timeoutMs);
                    input.writeInt(request.maxOutputBytes);
                    if (!connection.binder.transact(ShellUserService.EXECUTE, input, output, 0)) {
                        throw new FileFailure("Shizuku 执行服务不支持这个操作");
                    }
                    output.readException();
                    Json result = Json.parse(output.readString());
                    if (result.get("error") != null) {
                        throw new FileFailure(result.get("error").string());
                    }
                    return result;
                } finally {
                    output.recycle();
                    input.recycle();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new FileFailure("shell 请求被中断；请核实结果，不要自动重做");
            } catch (RemoteException | SecurityException stopped) {
                throw new FileFailure("Shizuku 连接中断或授权已撤销；请核实结果，不要自动重做");
            } catch (IllegalStateException stopped) {
                throw new FileFailure("Shizuku 服务已停止，请在手机上重新启动");
            } finally {
                if (binding) {
                    try {
                        Shizuku.unbindUserService(args, connection, true);
                    } catch (RuntimeException ignored) {}
                }
            }
        }
    }

    private static void requireAvailable(Context context) {
        Json state = status(context);
        if (!state.get("available").boolValue()) {
            throw new FileFailure(state.get("reason").string());
        }
    }

    private static final class Connection implements ServiceConnection {
        final CountDownLatch ready = new CountDownLatch(1);
        volatile IBinder binder;

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            binder = service;
            ready.countDown();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            binder = null;
            ready.countDown();
        }
    }
}
