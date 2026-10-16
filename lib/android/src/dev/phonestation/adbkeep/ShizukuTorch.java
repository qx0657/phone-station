package dev.phonestation.adbkeep;

import android.content.Context;
import android.os.IBinder;
import android.os.Parcel;

/** 灯留在共享特权进程里。状态查询不启动进程；没开灯且进程不在时直接报告未开。 */
final class ShizukuTorch {
    private ShizukuTorch() {}

    static synchronized Json status(Context context) {
        try {
            IBinder binder = PrivilegedHelper.binder();
            if (binder == null) {
                return unavailableOrOff(context);
            }
            return transact(context, binder, TorchUserService.STATUS, false, () -> {});
        } catch (FileFailure error) {
            return Json.obj().put("available", false).put("on", Json.nul()).put("reason", error.getMessage());
        }
    }

    static synchronized Json set(Context context, boolean on) {
        return set(context, on, () -> {});
    }

    static synchronized Json set(Context context, boolean on, Runnable authorize) {
        boolean addedTorch = false;
        try {
            if (!on && PrivilegedHelper.binder() == null) {
                return unavailableOrOff(context);
            }
            Json shell = ShizukuShell.status(context);
            if (!shell.get("available").boolValue()) {
                throw new FileFailure(shell.get("reason").string());
            }
            IBinder binder;
            if (on) {
                addedTorch = !PrivilegedHelper.holds(HelperPins.TORCH);
                binder = PrivilegedHelper.acquire(context, HelperPins.TORCH);
            } else {
                binder = PrivilegedHelper.binder();
            }
            if (binder == null) {
                throw new FileFailure("手电筒服务未连接，请检查手机上的 Shizuku");
            }
            Json result = transact(context, binder, TorchUserService.SET, on, authorize);
            if (!on) {
                PrivilegedHelper.release(HelperPins.TORCH);
            }
            return result;
        } catch (RuntimeException error) {
            if (!on || addedTorch) {
                PrivilegedHelper.release(HelperPins.TORCH);
            }
            if (error instanceof FileFailure) {
                throw error;
            }
            throw new FileFailure("手电筒连接中断；请核实结果，不要自动重做");
        }
    }

    static synchronized void close() {
        IBinder binder = PrivilegedHelper.binder();
        if (binder != null) {
            try {
                transact(null, binder, TorchUserService.SET, false, () -> {});
            } catch (RuntimeException ignored) {
                // 进程退出时系统也会关掉本进程持有的灯。
            }
        }
        PrivilegedHelper.release(HelperPins.TORCH);
    }

    private static Json unavailableOrOff(Context context) {
        Json shell = ShizukuShell.status(context);
        if (!shell.get("available").boolValue()) {
            return Json.obj().put("available", false).put("on", Json.nul()).put("reason", shell.get("reason").string());
        }
        return Json.obj().put("available", true).put("on", false).put("reason", "");
    }

    private static Json transact(Context context, IBinder binder, int code, boolean on, Runnable authorize) {
        authorize.run();
        if (context != null) {
            Json shell = ShizukuShell.status(context);
            if (!shell.get("available").boolValue()) {
                throw new FileFailure(shell.get("reason").string());
            }
        }
        Parcel input = Parcel.obtain();
        Parcel output = Parcel.obtain();
        try {
            authorize.run();
            input.writeInterfaceToken(TorchUserService.DESCRIPTOR);
            if (code == TorchUserService.SET) {
                input.writeInt(on ? 1 : 0);
            }
            if (!binder.transact(code, input, output, 0)) {
                throw new FileFailure("请更新手机端手电筒服务");
            }
            output.readException();
            Json result = Json.parse(output.readString());
            if (result.get("error") != null) {
                throw new FileFailure(result.get("error").string());
            }
            return result;
        } catch (android.os.RemoteException | SecurityException error) {
            throw new FileFailure("手电筒连接中断；请核实结果，不要自动重做");
        } finally {
            input.recycle();
            output.recycle();
        }
    }
}
