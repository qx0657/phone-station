package dev.phonestation.adbkeep;

final class FindPhoneVolume {
    static boolean shouldRestore(int current, boolean muted, int applied) {
        return applied >= 0 && current == applied && !muted;
    }
    private FindPhoneVolume() {}
}
