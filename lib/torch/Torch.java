import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;

// 相机服务把开灯请求绑在调用进程的 binder 上，进程一死灯就灭。
// on / level 因此要停在这里不退出；listen 则一直读亮度直到管道断开。
public class Torch {
    private static final String PID_FILE = "/data/local/tmp/torch.pid";

    private CameraManager manager;
    private Context stationContext;
    private long policyCheckedNs;
    private String cameraId;
    private int maxLevel = 1;
    private int current = -1;
    private boolean strengthOk = true;
    private boolean reported;
    private volatile boolean liveStop;
    private final Object liveLock = new Object();
    private int requested;
    private long zeroSinceNs;
    private long lastHalNs;
    private int strengthFails;

    public static void main(String[] args) {
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true));
        exemptHiddenApi();
        Torch torch = new Torch();
        try {
            Looper.prepareMainLooper();
            torch.setup();
            torch.dispatch(args);
        } catch (Throwable t) {
            torch.fail(torch.explain(t));
        }
    }

    private void dispatch(String[] args) {
        if (args.length == 0) {
            fail("用法: Torch on|off|status|listen|level <n>");
        }
        String cmd = args[0];
        if ("status".equals(cmd)) {
            status();
            return;
        }
        if ("off".equals(cmd)) {
            worker(new Runnable() {
                @Override
                public void run() {
                    try {
                        apply(0);
                        say("off");
                        System.exit(0);
                    } catch (Throwable t) {
                        fail(explain(t));
                    }
                }
            });
            return;
        }
        if ("on".equals(cmd)) {
            hold(maxLevel);
            return;
        }
        if ("level".equals(cmd)) {
            if (args.length < 2) {
                fail("用法: Torch level <亮度>");
            }
            int level;
            try {
                level = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                fail("亮度不是整数");
                return;
            }
            if (level <= 0) {
                dispatch(new String[] {"off"});
                return;
            }
            hold(level);
            return;
        }
        if ("listen".equals(cmd)) {
            listen();
            return;
        }
        fail("用法: Torch on|off|status|listen|level <n>");
    }

    private void hold(final int level) {
        worker(new Runnable() {
            @Override
            public void run() {
                try {
                    apply(level);
                    writePid();
                    say("holding " + current + " " + maxLevel);
                    for (;;) {
                        enforcePolicy();
                        Thread.sleep(1_000L);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Throwable t) {
                    fail(explain(t));
                }
            }
        });
    }

    private void listen() {
        worker(new Runnable() {
            @Override
            public void run() {
                try {
                    writePid();
                    say("ready " + cameraId + " " + maxLevel);
                    liveStop = false;
                    Thread driver = new Thread(new Runnable() {
                        @Override
                        public void run() {
                            driveLive();
                        }
                    }, "torch-live");
                    driver.start();
                    BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
                    String line;
                    while ((line = in.readLine()) != null) {
                        line = line.trim();
                        if (line.length() == 0) {
                            continue;
                        }
                        if ("q".equals(line) || "quit".equals(line)) {
                            break;
                        }
                        int level;
                        try {
                            level = Integer.parseInt(line);
                        } catch (NumberFormatException e) {
                            say("err 无法识别的亮度");
                            continue;
                        }
                        requestLive(level);
                    }
                    liveStop = true;
                    driver.interrupt();
                    apply(0);
                    clearPid();
                    say("bye");
                    System.exit(0);
                } catch (Throwable t) {
                    fail(explain(t));
                }
            }
        });
    }

    private void worker(Runnable task) {
        new Thread(task, "torch").start();
        Looper.loop();
    }

    private void setup() throws Exception {
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Object thread = activityThread.getMethod("systemMain").invoke(null);
        Context context = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
        stationContext = context;
        Object service = context.getSystemService(Context.CAMERA_SERVICE);
        if (!(service instanceof CameraManager)) {
            throw new IllegalStateException("拿不到相机服务");
        }
        manager = (CameraManager) service;
        String[] ids = manager.getCameraIdList();
        String chosen = null;
        int chosenMax = 1;
        int best = -1;
        for (int i = 0; i < ids.length; i++) {
            CameraCharacteristics chars = manager.getCameraCharacteristics(ids[i]);
            Boolean flash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            if (flash == null || !flash.booleanValue()) {
                continue;
            }
            int score = 1;
            Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing.intValue() == CameraCharacteristics.LENS_FACING_BACK) {
                score += 10;
            }
            if ("0".equals(ids[i])) {
                score += 5;
            }
            Integer max = chars.get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL);
            int level = (max == null || max.intValue() < 1) ? 1 : max.intValue();
            if (level > 1) {
                score += 1;
            }
            if (score > best) {
                best = score;
                chosen = ids[i];
                chosenMax = level;
            }
        }
        if (chosen == null) {
            throw new IllegalStateException("这台手机没有可用的闪光灯");
        }
        cameraId = chosen;
        maxLevel = chosenMax;
    }

    private void status() {
        manager.registerTorchCallback(new CameraManager.TorchCallback() {
            @Override
            public void onTorchModeChanged(String id, boolean enabled) {
                if (!id.equals(cameraId)) {
                    return;
                }
                if (!enabled) {
                    report("off");
                    return;
                }
                int strength = 0;
                try {
                    strength = manager.getTorchStrengthLevel(cameraId);
                } catch (Throwable ignored) {
                }
                if (strength > 0) {
                    report("on " + strength);
                } else {
                    report("on");
                }
            }

            @Override
            public void onTorchModeUnavailable(String id) {
                if (id.equals(cameraId)) {
                    report("off");
                }
            }
        }, new Handler(Looper.getMainLooper()));
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                report("err 没有读到闪光灯状态");
            }
        }, 1500);
        Looper.loop();
    }

    private void report(String line) {
        if (reported) {
            return;
        }
        reported = true;
        say(line);
        System.exit(line.startsWith("err ") ? 1 : 0);
    }

    // 关掉再打开会拆掉高通相机的闪光灯会话，连续几次后相机会自己退出。
    // 跟随声音时，短时间的 0 只表示这一拍过去了，先保持当前亮度。
    private void requestLive(int level) {
        synchronized (liveLock) {
            requested = level;
            if (level > 0) {
                zeroSinceNs = 0;
            } else if (zeroSinceNs == 0) {
                zeroSinceNs = System.nanoTime();
            }
        }
    }

    private void driveLive() {
        int shown = -1;
        long retryAfterNs = 0;
        while (!liveStop) {
            enforcePolicy();
            int want;
            long since;
            synchronized (liveLock) {
                want = requested;
                since = zeroSinceNs;
            }
            long now = System.nanoTime();
            boolean paced = lastHalNs != 0 && now - lastHalNs < 120_000_000L;
            try {
                if (now < retryAfterNs || paced) {
                    // 这一拍先不动灯。
                } else if (want > 0) {
                    if (want != shown) {
                        apply(want);
                        shown = current;
                        strengthFails = 0;
                    }
                } else if (shown != 0 && since != 0 && now - since >= 800_000_000L) {
                    apply(0);
                    shown = 0;
                    strengthFails = 0;
                }
            } catch (InterruptedException e) {
                break;
            } catch (CameraAccessException e) {
                recoverLive(e);
                shown = -1;
                retryAfterNs = System.nanoTime() + 400_000_000L;
            } catch (IllegalArgumentException e) {
                recoverLive(e);
                shown = -1;
                retryAfterNs = System.nanoTime() + 800_000_000L;
            } catch (Throwable t) {
                fail(explain(t));
            }
            try {
                Thread.sleep(40);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    // 相机服务重启后，原来的编号会暂时失效。重新找一次闪光灯，监听继续。
    private void recoverLive(Throwable error) {
        current = -1;
        String message = error.getMessage() == null ? "" : error.getMessage();
        if (message.contains("not valid camera") || message.contains("Disconnected")
                || message.contains("disabled")) {
            try {
                setup();
                strengthOk = true;
            } catch (Throwable ignored) {
            }
            return;
        }
        strengthFails++;
        if (strengthFails < 3) {
            return;
        }
        strengthFails = 0;
        try {
            manager.setTorchMode(cameraId, false);
        } catch (Throwable ignored) {
        }
        current = 0;
    }

    private synchronized void apply(int level) throws Exception {
        if (level > 0) { enforcePolicy(); }
        CameraAccessException last = null;
        for (int attempt = 0; attempt < 6; attempt++) {
            try {
                applyOnce(level);
                return;
            } catch (CameraAccessException e) {
                last = e;
                if (e.getReason() != CameraAccessException.CAMERA_IN_USE || attempt == 5) {
                    throw e;
                }
                Thread.sleep(80L * (attempt + 1));
            }
        }
        if (last != null) {
            throw last;
        }
    }

    private void enforcePolicy() {
        long now = System.nanoTime();
        if (policyCheckedNs != 0 && now - policyCheckedNs < 1_000_000_000L) { return; }
        policyCheckedNs = now;
        try {
            Object resolver = Context.class.getMethod("getContentResolver").invoke(stationContext);
            java.lang.reflect.Method read = Class.forName("android.provider.Settings$Global").getMethod("getString",
                    Class.forName("android.content.ContentResolver"), String.class);
            String master = (String) read.invoke(null, resolver, "phonestation_enabled");
            String feature = (String) read.invoke(null, resolver, "phonestation_feature_torch");
            if ("0".equals(master) || "0".equals(feature)) {
                manager.setTorchMode(cameraId, false); say("off"); System.exit(0);
            }
        } catch (Throwable error) { fail("无法核对手机功能开关"); }
    }

    private void applyOnce(int level) throws CameraAccessException {
        if (level <= 0) {
            if (current != 0) {
                manager.setTorchMode(cameraId, false);
                current = 0;
                lastHalNs = System.nanoTime();
            }
            return;
        }
        if (level > maxLevel) {
            level = maxLevel;
        }
        if (level == current) {
            return;
        }
        if (strengthOk && maxLevel > 1) {
            try {
                manager.turnOnTorchWithStrengthLevel(cameraId, level);
                current = level;
                lastHalNs = System.nanoTime();
                return;
            } catch (IllegalArgumentException e) {
                String message = e.getMessage() == null ? "" : e.getMessage();
                if (message.contains("not valid camera") || message.contains("Camera ID")) {
                    throw e;
                }
                strengthOk = false;
                maxLevel = 1;
                level = 1;
            } catch (UnsupportedOperationException e) {
                strengthOk = false;
                maxLevel = 1;
                level = 1;
            }
        }
        manager.setTorchMode(cameraId, true);
        current = 1;
        lastHalNs = System.nanoTime();
    }

    private void writePid() {
        FileWriter out = null;
        try {
            out = new FileWriter(PID_FILE);
            out.write(Integer.toString(android.os.Process.myPid()));
        } catch (Throwable ignored) {
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private void clearPid() {
        new File(PID_FILE).delete();
    }

    private void say(String line) {
        System.out.println(line);
        System.out.flush();
    }

    private void fail(String message) {
        say("err " + message);
        System.exit(1);
    }

    private String explain(Throwable t) {
        if (t instanceof CameraAccessException) {
            if (((CameraAccessException) t).getReason() == CameraAccessException.CAMERA_IN_USE) {
                return "相机正在使用，闪光灯暂时不可用";
            }
        }
        if (t instanceof SecurityException) {
            return "没有闪光灯权限";
        }
        String name = t.getClass().getName();
        if (name.contains("DeadObject") || name.contains("DeadSystem")) {
            return "相机服务中断了，闪光灯已关掉";
        }
        String message = t.getMessage();
        if (message == null || message.length() == 0) {
            return t.getClass().getSimpleName();
        }
        return message;
    }

    private static void exemptHiddenApi() {
        try {
            Class<?> runtimeClass = Class.forName("dalvik.system.VMRuntime");
            Object runtime = runtimeClass.getMethod("getRuntime").invoke(null);
            runtimeClass.getMethod("setHiddenApiExemptions", String[].class)
                    .invoke(runtime, new Object[] {new String[] {"L"}});
        } catch (Throwable ignored) {
        }
    }
}
