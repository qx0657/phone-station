package dev.phonestation.adbkeep;

import android.content.Context;
import android.os.IBinder;
import android.os.Parcel;

/** 投屏会话走共用的特权助手。这一路失败只停采集，不退出仍被灯或剪贴板持有的进程。 */
final class ShizukuScreen {
    static synchronized Json call(Context context, Json request, boolean start) {
        return call(context, request, start, () -> {});
    }

    static synchronized Json call(Context context, Json request, boolean start, Runnable authorize) {
        authorize.run();
        String id = request.get("sessionId").string();
        Json status = ShizukuShell.status(context);
        if (!status.get("available").boolValue()) { close(); throw new FileFailure(status.get("reason").string()); }
        IBinder binder = liveBinder();
        if (binder == null) {
            if (!start) { return Json.obj().put("sessionId", id).put("state", "lost"); }
            try { binder = PrivilegedHelper.acquire(context, HelperPins.SCREEN); }
            catch (RuntimeException error) {
                PrivilegedHelper.release(HelperPins.SCREEN);
                if (error instanceof FileFailure) { throw error; }
                throw new FileFailure("投屏创建结果未确认，请查询原编号");
            }
        }
        Parcel input = Parcel.obtain(), output = Parcel.obtain();
        try {
            authorize.run();
            input.writeInterfaceToken(ScreenUserService.DESCRIPTOR);
            input.writeString(request.emit());
            if (!binder.transact(ScreenUserService.CALL, input, output, 0)) { close(); throw new FileFailure("请更新手机工位以支持远程投屏"); }
            output.readException();
            Json result = Json.parse(output.readString());
            if (result.get("error") != null) { close(); throw new FileFailure(result.get("error").string()); }
            finishIfEnded(request, result);
            return result;
        } catch (SecurityException error) {
            close(); throw new FileFailure("投屏服务身份检查未通过：" + error.getMessage());
        } catch (android.os.RemoteException error) {
            close(); throw new FileFailure("投屏服务连接中断（" + error.getClass().getSimpleName() + "）；查询原会话，不重发输入");
        } finally { input.recycle(); output.recycle(); }
    }

    static synchronized void close() { releaseBinder(); }

    private static IBinder liveBinder() {
        IBinder binder = PrivilegedHelper.binder();
        return PrivilegedHelper.holds(HelperPins.SCREEN) && binder != null ? binder : null;
    }

    private static void finishIfEnded(Json request, Json result) {
        String op = request.get("op").string();
        String state = result.get("state") == null ? "" : result.get("state").string();
        if (("close".equals(op) && !"lost".equals(state)) || "ended".equals(state)) { releaseBinder(); }
    }

    private static void releaseBinder() {
        IBinder binder = PrivilegedHelper.binder();
        if (binder != null && PrivilegedHelper.holds(HelperPins.SCREEN)) {
            Parcel input = Parcel.obtain(), output = Parcel.obtain();
            try {
                input.writeInterfaceToken(ScreenUserService.DESCRIPTOR);
                if (binder.transact(ScreenUserService.RELEASE, input, output, 0)) { output.readException(); }
            } catch (RuntimeException | android.os.RemoteException ignored) {
                // 进程已经退出时，放开持有者即可。
            } finally { input.recycle(); output.recycle(); }
        }
        PrivilegedHelper.release(HelperPins.SCREEN);
    }
}
