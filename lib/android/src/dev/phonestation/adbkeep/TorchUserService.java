package dev.phonestation.adbkeep;

import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;

/** 持续持有 CameraManager 的 shell 进程；不执行命令，也不申请相机授权。 */
public final class TorchUserService extends Binder {
    static final String DESCRIPTOR = "dev.phonestation.adbkeep.TorchUserService";
    static final int STATUS = IBinder.FIRST_CALL_TRANSACTION;
    static final int SET = STATUS + 1;
    private final int ownerUid;
    private final Object state = new Object();
    private CameraManager manager;
    private String cameraId;
    private int maxLevel = 1;
    private Boolean enabled;
    private boolean available;

    public TorchUserService(Context context) {
        ownerUid = context.getApplicationInfo().uid;
        attachInterface(null, DESCRIPTOR);
    }

    @Override protected synchronized boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        int caller = Binder.getCallingUid();
        if (code == 16_777_115) {
            if (caller != ownerUid && caller != Process.myUid()) {
                throw new SecurityException("只允许手机工位或 Shizuku 停止手电筒服务");
            }
            System.exit(0); // Android 会关闭由本进程持有的灯。
            return true;
        }
        if (code != STATUS && code != SET) { return super.onTransact(code, data, reply, flags); }
        data.enforceInterface(DESCRIPTOR);
        if (caller != ownerUid) { throw new SecurityException("只允许手机工位调用"); }
        long identity = Binder.clearCallingIdentity();
        Json result;
        try {
            if (Process.myUid() != 2000 && Process.myUid() != 0) {
                throw new SecurityException("手电筒服务没有 shell/root 身份");
            }
            setup();
            if (code == SET) {
                boolean on = data.readInt() != 0;
                if (on && android.os.Build.VERSION.SDK_INT >= 33 && maxLevel > 1) {
                    manager.turnOnTorchWithStrengthLevel(cameraId, maxLevel);
                } else { manager.setTorchMode(cameraId, on); }
                long deadline = android.os.SystemClock.elapsedRealtime() + 2000;
                synchronized (state) {
                    while (enabled == null || enabled.booleanValue() != on) {
                        long remaining = deadline - android.os.SystemClock.elapsedRealtime();
                        if (remaining <= 0) { throw new IllegalStateException("手电筒状态未确认；请核实结果，不要自动重做"); }
                        state.wait(remaining);
                    }
                }
            }
            result = snapshot();
        } catch (CameraAccessException error) {
            result = Json.obj().put("error", error.getReason() == CameraAccessException.CAMERA_IN_USE
                    || error.getReason() == CameraAccessException.MAX_CAMERAS_IN_USE
                    ? "相机正在使用，暂时不能控制手电筒" : "相机服务暂时不可用，请核实手电筒状态");
        } catch (Exception error) {
            result = Json.obj().put("error", error.getMessage() == null ? "无法读取或控制手电筒" : error.getMessage());
            if (error instanceof InterruptedException) { Thread.currentThread().interrupt(); }
        } finally { Binder.restoreCallingIdentity(identity); }
        reply.writeNoException();
        reply.writeString(result.emit());
        return true;
    }

    private void setup() throws Exception {
        if (manager != null) { return; }
        java.util.concurrent.FutureTask<Void> setup = new java.util.concurrent.FutureTask<>(() -> {
            setupOnMain(); return null;
        });
        new Handler(android.os.Looper.getMainLooper()).post(setup);
        setup.get(5, java.util.concurrent.TimeUnit.SECONDS);
    }

    private void setupOnMain() throws Exception {
        Class<?> runtime = Class.forName("dalvik.system.VMRuntime");
        runtime.getMethod("setHiddenApiExemptions", String[].class).invoke(
                runtime.getMethod("getRuntime").invoke(null), new Object[] { new String[] {"L"} });
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Object thread = activityThread.getMethod("currentActivityThread").invoke(null);
        if (thread == null) { thread = activityThread.getMethod("systemMain").invoke(null); }
        Context system = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
        CameraManager next = system.getSystemService(CameraManager.class);
        if (next == null) { throw new IllegalStateException("拿不到相机服务"); }
        String chosen = null;
        int best = -1;
        for (String id : next.getCameraIdList()) {
            CameraCharacteristics chars = next.getCameraCharacteristics(id);
            if (!Boolean.TRUE.equals(chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE))) { continue; }
            Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
            int score = Integer.valueOf(CameraCharacteristics.LENS_FACING_BACK).equals(facing) ? 10 : 1;
            if (score > best) {
                best = score; chosen = id;
                Integer max = android.os.Build.VERSION.SDK_INT >= 33
                        ? chars.get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) : null;
                maxLevel = max == null ? 1 : Math.max(1, max.intValue());
            }
        }
        if (chosen == null) { throw new IllegalStateException("没有可用的闪光灯"); }
        cameraId = chosen;
        HandlerThread callbacks = new HandlerThread("station-torch-state");
        callbacks.start();
        next.registerTorchCallback(new CameraManager.TorchCallback() {
            @Override public void onTorchModeChanged(String id, boolean on) {
                if (!id.equals(cameraId)) { return; }
                synchronized (state) { enabled = on; available = true; state.notifyAll(); }
            }
            @Override public void onTorchModeUnavailable(String id) {
                if (!id.equals(cameraId)) { return; }
                synchronized (state) { enabled = false; available = false; state.notifyAll(); }
            }
        }, new Handler(callbacks.getLooper()));
        manager = next;
    }

    private Json snapshot() throws InterruptedException {
        synchronized (state) {
            long deadline = android.os.SystemClock.elapsedRealtime() + 1500;
            while (enabled == null) {
                long remaining = deadline - android.os.SystemClock.elapsedRealtime();
                if (remaining <= 0) { break; }
                state.wait(remaining);
            }
            return Json.obj().put("available", enabled != null && available)
                    .put("on", enabled == null ? Json.nul() : Json.bool(enabled.booleanValue()))
                    .put("maxLevel", maxLevel).put("reason", enabled == null ? "暂时读不到手电筒状态"
                            : available ? "" : "相机正在使用，手电筒暂时不可用");
        }
    }
}
