package dev.phonestation.adbkeep;

import android.os.Build;

/** The only model check. Other layers trust the verified flag this produces. */
final class VerifiedDevice {
    private VerifiedDevice() {}

    static boolean current() {
        return "PGT-AN20".equals(Build.MODEL.replace('_', '-'));
    }
}
