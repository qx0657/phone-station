package dev.phonestation.adbkeep;

import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;

/**
 * 剪贴板、shell 和手电共用的 Shizuku 进程。按调用方写入的接口名分给原来的实现。
 * 不是导出的 Android Service。终端和投屏仍使用各自的会话进程。
 */
public final class HelperUserService extends Binder {
    private static final int DESTROY = 16_777_115;
    private final int ownerUid;
    private final ClipboardUserService clipboard;
    private final ShellUserService shell;
    private final TorchUserService torch;

    public HelperUserService(Context context) {
        ownerUid = context.getApplicationInfo().uid;
        clipboard = new ClipboardUserService(context);
        shell = new ShellUserService(context);
        torch = new TorchUserService(context);
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (code == DESTROY) {
            int caller = Binder.getCallingUid();
            if (caller != ownerUid && caller != Process.myUid()) {
                throw new SecurityException("只允许手机工位或 Shizuku 停止助手");
            }
            System.exit(0);
            return true;
        }
        if (forward(clipboard, ClipboardUserService.DESCRIPTOR, code, data, reply, flags)) {
            return true;
        }
        if (forward(shell, ShellUserService.DESCRIPTOR, code, data, reply, flags)) {
            return true;
        }
        if (forward(torch, TorchUserService.DESCRIPTOR, code, data, reply, flags)) {
            return true;
        }
        return super.onTransact(code, data, reply, flags);
    }

    private static boolean forward(Binder target, String descriptor, int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        int start = data.dataPosition();
        try {
            data.enforceInterface(descriptor);
        } catch (RuntimeException mismatch) {
            data.setDataPosition(start);
            return false;
        }
        data.setDataPosition(start);
        return target.transact(code, data, reply, flags);
    }
}
