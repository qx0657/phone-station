package dev.phonestation.adbkeep;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import rikka.shizuku.Shizuku;

/** 剪贴板、手电、shell、终端和投屏共用一个非 daemon 进程。没有持有者时解除绑定，进程退出。 */
final class PrivilegedHelper {
    static final long SHELL_IDLE_MS = 30_000L;
    private static final Object LOCK = new Object();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final HelperPins pins = new HelperPins();
    private static Connection connection;
    private static Shizuku.UserServiceArgs args;
    private static int shellGeneration;
    private static boolean binding;

    private PrivilegedHelper() {}

    static IBinder acquire(Context context, String reason) {
        String start;
        Connection pending;
        Shizuku.UserServiceArgs pendingArgs;
        synchronized (LOCK) {
            if (HelperPins.SHELL.equals(reason)) {
                cancelShellIdle();
            }
            waitForBind();
            forgetIfDead();
            start = pins.acquire(reason);
            if (alive()) {
                if (start != null) {
                    pins.acquired();
                    UsageCounters.shared.helperStart(start);
                }
                return connection.binder;
            }
            binding = true;
            pendingArgs = new Shizuku.UserServiceArgs(new ComponentName(context, HelperUserService.class))
                    .tag("station-helper").processNameSuffix("helper").daemon(false).version(71);
            args = pendingArgs;
            pending = new Connection();
            connection = pending;
        }
        boolean ok = false;
        RuntimeException failure = null;
        try {
            Shizuku.bindUserService(pendingArgs, pending);
            ok = pending.ready.await(5, TimeUnit.SECONDS) && pending.binder != null && pending.binder.isBinderAlive();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            failure = new FileFailure("特权助手连接被中断");
        } catch (RuntimeException stopped) {
            failure = new FileFailure("特权助手没有连上，请检查手机上的 Shizuku");
        }
        synchronized (LOCK) {
            binding = false;
            try {
                if (!ok || !pins.holds(reason) || connection != pending) {
                    unbindQuiet();
                    pins.abort(reason);
                    throw failure != null ? failure : new FileFailure("特权助手没有连上，请检查手机上的 Shizuku");
                }
                if (start != null) {
                    pins.acquired();
                    UsageCounters.shared.helperStart(start);
                }
                return pending.binder;
            } finally {
                LOCK.notifyAll();
            }
        }
    }

    private static void waitForBind() {
        while (binding) {
            try {
                LOCK.wait();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new FileFailure("特权助手连接被中断");
            }
        }
    }

    static void release(String reason) {
        synchronized (LOCK) {
            if (HelperPins.SHELL.equals(reason)) {
                cancelShellIdle();
            }
            forgetIfDead();
            String stop = pins.release(reason);
            if (stop != null) {
                drop(stop);
            }
        }
    }

    /** 命令已经结束。还有别的持有者就立刻放开 shell；否则 30 秒内的下一条命令继续用这个进程。 */
    static void shellFinished() {
        synchronized (LOCK) {
            if (!pins.holds(HelperPins.SHELL)) {
                return;
            }
            if (!pins.shellAlone()) {
                pins.release(HelperPins.SHELL);
                return;
            }
            int token = ++shellGeneration;
            MAIN.postDelayed(() -> onShellIdle(token), SHELL_IDLE_MS);
        }
    }

    static IBinder binder() {
        synchronized (LOCK) {
            forgetIfDead();
            return alive() ? connection.binder : null;
        }
    }

    static boolean holds(String reason) {
        synchronized (LOCK) {
            return pins.holds(reason);
        }
    }

    private static void onShellIdle(int token) {
        synchronized (LOCK) {
            if (token != shellGeneration || !pins.holds(HelperPins.SHELL)) {
                return;
            }
            if (binding) {
                MAIN.postDelayed(() -> onShellIdle(token), SHELL_IDLE_MS);
                return;
            }
            if (!pins.shellAlone()) {
                pins.release(HelperPins.SHELL);
                return;
            }
            String stop = pins.release(HelperPins.SHELL);
            if (stop != null) {
                drop(stop);
            }
        }
    }

    private static void cancelShellIdle() {
        shellGeneration++;
    }

    private static void forgetIfDead() {
        if (binding || connection == null || alive()) {
            return;
        }
        cancelShellIdle();
        String stop = pins.stop();
        connection = null;
        args = null;
        if (stop != null) {
            UsageCounters.shared.helperStop(stop);
        }
    }

    private static void drop(String stopName) {
        unbindQuiet();
        if (stopName != null) {
            UsageCounters.shared.helperStop(stopName);
        }
    }

    private static void unbindQuiet() {
        Connection current = connection;
        Shizuku.UserServiceArgs currentArgs = args;
        connection = null;
        args = null;
        if (current != null && currentArgs != null) {
            try {
                Shizuku.unbindUserService(currentArgs, current, true);
            } catch (RuntimeException ignored) {
                // 进程已经退出时解除绑定没有第二轮清理。
            }
        }
    }

    private static boolean alive() {
        return connection != null && connection.binder != null && connection.binder.isBinderAlive();
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
