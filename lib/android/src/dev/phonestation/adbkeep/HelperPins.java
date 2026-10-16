package dev.phonestation.adbkeep;

import java.util.LinkedHashSet;

/**
 * 一个特权进程上的持有者。进程没起来时第一次持有要计数；已经起来时再持有不再计数。
 * 最后一个持有者放开时，停止计数与这次启动成对。
 */
final class HelperPins {
    static final String CLIPBOARD = "clipboard";
    static final String TORCH = "torch";
    static final String SHELL = "shell";

    private final LinkedHashSet<String> pins = new LinkedHashSet<>();
    private boolean up;
    private String counted;

    /** 记下一路持有。进程还没起来且这是第一路时，返回应计数的名字。 */
    String acquire(String reason) {
        boolean added = pins.add(reason);
        if (up || !added || counted != null) {
            return null;
        }
        counted = reason;
        return pins.size() == 1 ? reason : null;
    }

    void acquired() {
        up = true;
    }

    /** 绑定失败且进程没有起来时撤回这一路。 */
    void abort(String reason) {
        pins.remove(reason);
        if (!up) {
            counted = null;
        }
    }

    /** 放开一路。进程因此要退出时，返回与启动成对的名字。进程还没起来时只撤回这一路。 */
    String release(String reason) {
        pins.remove(reason);
        if (!up) {
            if (pins.isEmpty()) {
                counted = null;
            }
            return null;
        }
        if (!pins.isEmpty()) {
            return null;
        }
        return stop();
    }

    String stop() {
        if (!up) {
            counted = null;
            pins.clear();
            return null;
        }
        String name = counted;
        up = false;
        counted = null;
        pins.clear();
        return name;
    }

    boolean up() {
        return up;
    }

    boolean holds(String reason) {
        return pins.contains(reason);
    }

    boolean heldByOthers(String reason) {
        return pins.size() > 1 || (pins.size() == 1 && !pins.contains(reason));
    }

    boolean shellAlone() {
        return up && pins.size() == 1 && pins.contains(SHELL);
    }
}
