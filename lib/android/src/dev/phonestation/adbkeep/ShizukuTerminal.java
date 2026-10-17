package dev.phonestation.adbkeep;

import android.content.Context;
import android.os.IBinder;
import android.os.Parcel;
import java.util.HashSet;
import java.util.Set;

/** 终端会话走共用的特权助手。这一路失败只停 PTY，不退出仍被灯或剪贴板持有的进程。 */
final class ShizukuTerminal {
    private static final Set<String> sessions = new HashSet<>();

    static synchronized Json call(Context context, Json request, boolean start) {
        return call(context, request, start, () -> {});
    }

    static synchronized Json call(Context context, Json request, boolean start, Runnable authorize) {
        authorize.run();
        String id = request.get("sessionId").string();
        Json status = ShizukuShell.status(context);
        if (!status.get("available").boolValue()) { dropRole(); throw new FileFailure(status.get("reason").string()); }
        IBinder binder = liveBinder();
        if (binder == null) {
            sessions.remove(id);
            if (!start) { return TerminalJobs.ended(id, "lost", "原会话已结束，不会重新创建"); }
            try { binder = PrivilegedHelper.acquire(context, HelperPins.TERMINAL); }
            catch (RuntimeException error) {
                sessions.clear();
                PrivilegedHelper.release(HelperPins.TERMINAL);
                if (error instanceof FileFailure) { throw error; }
                throw new FileFailure("终端创建结果未确认，请查询原编号");
            }
        }
        Parcel input = Parcel.obtain(), output = Parcel.obtain();
        try {
            authorize.run();
            input.writeInterfaceToken(TerminalUserService.DESCRIPTOR);
            input.writeString(request.emit());
            if (!binder.transact(TerminalUserService.CALL, input, output, 0)) { dropRole(); throw new FileFailure("请更新手机工位以支持远程终端"); }
            output.readException();
            Json result = Json.parse(output.readString());
            if (result.get("error") != null) { dropRole(); throw new FileFailure(result.get("error").string()); }
            track(id, request.get("op").string(), result);
            return result;
        } catch (android.os.RemoteException | SecurityException error) {
            dropRole(); throw new FileFailure("终端连接中断；查询原会话，不重发输入");
        } finally { input.recycle(); output.recycle(); }
    }

    static synchronized void close() { dropRole(); }

    static synchronized void closeSessions(Context context, Set<String> ids) {
        if (liveBinder() == null) { sessions.clear(); return; }
        for (String id : ids) {
            try { call(context, TerminalJobs.request("close", id), false); }
            catch (FileFailure ignored) { /* 撤销时不重新绑定。 */ }
        }
    }

    private static IBinder liveBinder() {
        IBinder binder = PrivilegedHelper.binder();
        return PrivilegedHelper.holds(HelperPins.TERMINAL) && binder != null ? binder : null;
    }

    private static void track(String id, String op, Json result) {
        String state = result.get("state") == null ? "" : result.get("state").string();
        if ("open".equals(op) && "running".equals(state)) { sessions.add(id); }
        if ("closed".equals(state) || "exited".equals(state) || "expired".equals(state) || "lost".equals(state)) { sessions.remove(id); }
        if (sessions.isEmpty() && PrivilegedHelper.holds(HelperPins.TERMINAL)) { releaseBinder(); }
    }

    private static void dropRole() {
        sessions.clear();
        releaseBinder();
    }

    private static void releaseBinder() {
        IBinder binder = PrivilegedHelper.binder();
        if (binder != null && PrivilegedHelper.holds(HelperPins.TERMINAL)) {
            Parcel input = Parcel.obtain(), output = Parcel.obtain();
            try {
                input.writeInterfaceToken(TerminalUserService.DESCRIPTOR);
                if (binder.transact(TerminalUserService.RELEASE, input, output, 0)) { output.readException(); }
            } catch (RuntimeException | android.os.RemoteException ignored) {
                // 进程已经退出时，放开持有者即可。
            } finally { input.recycle(); output.recycle(); }
        }
        PrivilegedHelper.release(HelperPins.TERMINAL);
    }
}
