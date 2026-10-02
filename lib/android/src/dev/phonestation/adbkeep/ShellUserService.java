package dev.phonestation.adbkeep;

import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.File;
import java.io.IOException;

/** 由 Shizuku 创建的 shell/root 进程，不是 Android Service，也不在清单里导出。 */
public final class ShellUserService extends Binder {
    static final String DESCRIPTOR = "dev.phonestation.adbkeep.ShellUserService";
    static final int EXECUTE = IBinder.FIRST_CALL_TRANSACTION;
    static final int DESTROY = 16_777_115;
    private final int ownerUid;

    // Shizuku 13 起提供包的 Context；使用它核实 IPC 调用方，不能信任请求传来的 UID。
    public ShellUserService(Context context) {
        ownerUid = context.getApplicationInfo().uid;
        attachInterface(null, DESCRIPTOR);
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (code == DESTROY) {
            int caller = Binder.getCallingUid();
            if (caller != ownerUid && caller != Process.myUid()) {
                throw new SecurityException("只允许手机工位或 Shizuku 停止服务");
            }
            System.exit(0);
            return true;
        }
        if (code != EXECUTE) {
            return super.onTransact(code, data, reply, flags);
        }
        data.enforceInterface(DESCRIPTOR);
        if (Binder.getCallingUid() != ownerUid) {
            throw new SecurityException("只允许手机工位调用");
        }
        Json result;
        try {
            int uid = Process.myUid();
            if (uid != 2000 && uid != 0) {
                throw new IllegalArgumentException("执行服务没有 shell/root 身份");
            }
            ShellRequest request = new ShellRequest(data.readString(), data.readInt(), data.readInt());
            ProcessBuilder builder = new ProcessBuilder(
                    "/system/bin/setsid", "/system/bin/sh", "-c", ShellRunner.WRAPPER,
                    "station-shell", request.command);
            builder.directory(new File("/"));
            result = ShellRunner.run(builder.start(), request, ShellUserService::killGroup)
                    .put("uid", uid).put("identity", uid == 0 ? "root" : "shell");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            result = Json.obj().put("error", "shell 执行被中断；请核实操作结果，不要自动重做");
        } catch (IOException error) {
            result = Json.obj().put("error", "无法启动或读取 shell；请核实操作结果");
        } catch (IllegalArgumentException error) {
            result = Json.obj().put("error", error.getMessage());
        }
        reply.writeNoException();
        reply.writeString(result.emit());
        return true;
    }

    private static void killGroup(long pid) {
        try {
            Os.kill(-(int) pid, OsConstants.SIGKILL);
        } catch (ErrnoException ignored) {
            // 正常结束的进程组可能已经消失。
        }
    }
}
