package dev.phonestation.adbkeep;

import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import rikka.shizuku.Shizuku;

/** 手机工位作为 Shizuku 客户端时能读到的状态。服务进程不在时，授权也用不了。 */
final class ShizukuLink {
    static final String MANAGER = "moe.shizuku.privileged.api";
    private static boolean logged;

    private ShizukuLink() {}

    static PermissionCopy.ShizukuState read(Context context) {
        if (!installed(context)) {
            return PermissionCopy.ShizukuState.ABSENT;
        }
        try {
            if (!Shizuku.pingBinder()) {
                return PermissionCopy.ShizukuState.STOPPED;
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                return PermissionCopy.ShizukuState.DENIED;
            }
        } catch (RuntimeException error) {
            return PermissionCopy.ShizukuState.STOPPED;
        }
        if (!logged) {
            logged = true;
            Log.i(KeeperEngine.TAG, "shizuku uid " + Shizuku.getUid());
        }
        return PermissionCopy.ShizukuState.GRANTED;
    }

    private static boolean installed(Context context) {
        try {
            context.getPackageManager().getPackageInfo(MANAGER, 0);
            return true;
        } catch (PackageManager.NameNotFoundException error) {
            return false;
        }
    }
}
