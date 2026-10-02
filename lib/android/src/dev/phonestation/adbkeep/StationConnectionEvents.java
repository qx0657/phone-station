package dev.phonestation.adbkeep;

import android.os.Handler;
import android.os.Looper;
import java.util.HashSet;
import java.util.Set;

/** Visible activities subscribe only while resumed; services own network monitoring. */
final class StationConnectionEvents {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Set<Runnable> LISTENERS = new HashSet<>();

    static void add(Runnable listener) { LISTENERS.add(listener); }
    static void remove(Runnable listener) { LISTENERS.remove(listener); }
    static void changed() {
        MAIN.post(() -> {
            for (Runnable listener : new HashSet<>(LISTENERS)) { listener.run(); }
        });
    }
}
