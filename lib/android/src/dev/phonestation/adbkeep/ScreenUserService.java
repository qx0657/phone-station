package dev.phonestation.adbkeep;

import android.content.Context;
import android.os.*;
import android.os.Process;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Privileged capture is owned by a non-daemon Shizuku service, not a shell job. */
public final class ScreenUserService extends Binder {
    static final String DESCRIPTOR = "dev.phonestation.adbkeep.ScreenUserService";
    static final int CALL = IBinder.FIRST_CALL_TRANSACTION;
    private final Context context;
    private final int ownerUid;
    private java.lang.Process helper;
    private OutputStream lease;
    private String id = "";
    private volatile String state = "idle";
    private volatile long frames;
    public ScreenUserService(Context context) { this.context = context; ownerUid = context.getApplicationInfo().uid; attachInterface(null, DESCRIPTOR); }
    @Override protected synchronized boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (code == 16_777_115) {
            if (Binder.getCallingUid() != ownerUid && Binder.getCallingUid() != Process.myUid()) { throw new SecurityException("owner"); }
            stop(); System.exit(0); return true;
        }
        if (code != CALL) { return super.onTransact(code, data, reply, flags); }
        data.enforceInterface(DESCRIPTOR);
        if (Binder.getCallingUid() != ownerUid || (Process.myUid() != 2000 && Process.myUid() != 0)) { throw new SecurityException("caller=" + Binder.getCallingUid() + ", owner=" + ownerUid + ", service=" + Process.myUid()); }
        Json result;
        boolean opening = false;
        try {
            Json request = Json.parse(data.readString()); String wanted = request.get("sessionId").string(); OperationJobs.validate(wanted);
            String op = request.get("op").string();
            if (op.equals("open")) {
                if (helper != null && helper.isAlive()) { if (!id.equals(wanted)) { throw new FileFailure("已有投屏，请先关闭"); } }
                else {
                    if (id.equals(wanted)) { throw new FileFailure("原投屏已结束，请使用新编号"); }
                    opening = true;
                    id = wanted; frames = 0; state = "starting";
                    String executable = ShellAssets.extract(context, "screen-arm64", true);
                    String server = ShellAssets.extract(context, "scrcpy-server", false);
                    // Android's libcore does not provide the desktop JDK DISCARD field.
                    helper = new ProcessBuilder(executable).start();
                    final java.lang.Process active = helper;
                    Thread errors = new Thread(() -> {
                        try (InputStream in = active.getErrorStream()) { byte[] buffer = new byte[4096]; while (in.read(buffer) != -1) {} }
                        catch (IOException ignored) {}
                    }, "station-screen-errors"); errors.setDaemon(true); errors.start();
                    request.put("server", server);
                    lease = helper.getOutputStream(); lease.write((request.emit() + "\n").getBytes(StandardCharsets.UTF_8)); lease.flush();
                    Thread reader = new Thread(() -> {
                        try (BufferedReader in = new BufferedReader(new InputStreamReader(active.getInputStream(), StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = in.readLine()) != null) {
                                if (line.length() > 4096) { break; }
                                Json value = Json.parse(line);
                                synchronized (this) {
                                    if (helper != active) { break; }
                                    state = value.get("state").string();
                                    if (value.get("videoFrames") != null) { frames = value.get("videoFrames").longValue(); }
                                }
                            }
                        } catch (Exception ignored) {} finally { synchronized(this) { if (helper == active && !state.equals("ended")) { state = "ended"; } } }
                    }, "station-screen-status"); reader.setDaemon(true); reader.start();
                }
            } else if (op.equals("close")) { if (id.equals(wanted)) { stop(); } }
            else if (!op.equals("status")) { throw new FileFailure("投屏操作无效"); }
            result = Json.obj().put("sessionId", wanted).put("state", id.equals(wanted) ? state : "lost").put("videoFrames", id.equals(wanted) ? frames : 0);
        } catch (Exception e) { if (opening) { stop(); } result = Json.obj().put("error", "投屏操作未确认，请查询原编号"); }
        reply.writeNoException(); reply.writeString(result.emit()); return true;
    }
    private void stop() {
        state = "ended";
        try { if (lease != null) { lease.close(); } } catch (IOException ignored) {}
        if (helper != null) { helper.destroy(); try { if (!helper.waitFor(3, TimeUnit.SECONDS)) { helper.destroyForcibly(); } } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
        lease = null;
    }
}
