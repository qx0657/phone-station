package dev.phonestation.adbkeep;

import android.content.ClipData;
import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import java.lang.reflect.Method;

/** A dedicated Shizuku service; never executes shell commands. */
public final class ClipboardUserService extends Binder {
    static final String DESCRIPTOR = "dev.phonestation.adbkeep.ClipboardUserService";
    static final int READ = IBinder.FIRST_CALL_TRANSACTION;
    static final int WRITE = READ + 1;
    private final int ownerUid;
    private Object clipboard;

    public ClipboardUserService(Context context) {
        ownerUid = context.getApplicationInfo().uid;
        attachInterface(null, DESCRIPTOR);
    }
    @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (code == 16_777_115) {
            if (Binder.getCallingUid() != ownerUid && Binder.getCallingUid() != Process.myUid()) {
                throw new SecurityException("只允许手机工位停止剪贴板服务");
            }
            System.exit(0);
            return true;
        }
        if (code != READ && code != WRITE) { return super.onTransact(code, data, reply, flags); }
        data.enforceInterface(DESCRIPTOR);
        if (Binder.getCallingUid() != ownerUid) { throw new SecurityException("只允许手机工位调用"); }
        long identity = Binder.clearCallingIdentity();
        Json result;
        try {
            if (Process.myUid() != 2000 && Process.myUid() != 0) { throw new SecurityException("没有 shell/root 身份"); }
            if (code == WRITE) {
                String text = data.readString();
                if (text == null || text.length() > ClipboardState.LIMIT) { throw new IllegalArgumentException("文字太长"); }
                invoke("setPrimaryClip", ClipData.newPlainText("手机工位", text));
                result = Json.obj().put("copied", true);
            } else {
                ClipData clip = (ClipData) invoke("getPrimaryClip", null);
                result = read(clip).json().put("sourceVersion", clip == null ? 0 : clip.getDescription().getTimestamp());
            }
        } catch (Exception error) {
            android.util.Log.w("StationClipboard", "system clipboard call failed", error);
            result = Json.obj().put("error", "无法访问系统剪贴板，请检查 Shizuku 或重新启动手机工位");
        } finally { Binder.restoreCallingIdentity(identity); }
        reply.writeNoException();
        reply.writeString(result.emit());
        return true;
    }
    private Object invoke(String name, ClipData clip) throws Exception {
        if (clipboard == null) {
            IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class).invoke(null, "clipboard");
            clipboard = Class.forName("android.content.IClipboard$Stub")
                    .getMethod("asInterface", IBinder.class).invoke(null, binder);
        }
        for (Method method : clipboard.getClass().getMethods()) {
            if (!name.equals(method.getName())) { continue; }
            Class<?>[] types = method.getParameterTypes();
            if (clip != null && (types.length == 0 || types[0] != ClipData.class)) { continue; }
            Object[] values = ClipboardMethods.arguments(types, clip != null, clip, ownerUid / 100_000);
            if (values == null) { continue; }
            method.setAccessible(true);
            return method.invoke(clipboard, values);
        }
        throw new NoSuchMethodException(name);
    }
    static ClipboardState.Clip read(ClipData clip) {
        if (clip == null || clip.getItemCount() == 0) { return new ClipboardState.Clip("empty", null); }
        if (clip.getDescription().getExtras() != null
                && clip.getDescription().getExtras().getBoolean("android.content.extra.IS_SENSITIVE", false)) {
            return new ClipboardState.Clip("sensitive", null);
        }
        CharSequence text = clip.getItemAt(0).getText();
        if (text == null) { return new ClipboardState.Clip("unsupported", null); }
        if (text.length() > ClipboardState.LIMIT) { return new ClipboardState.Clip("oversize", null); }
        return new ClipboardState.Clip("text", text.toString());
    }
}
