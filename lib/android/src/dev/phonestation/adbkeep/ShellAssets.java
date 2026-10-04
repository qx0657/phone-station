package dev.phonestation.adbkeep;

import android.content.Context;
import android.os.Process;
import android.system.Os;
import android.system.OsConstants;
import java.io.InputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.zip.*;

/** Extract only APK-owned assets into a shell-owned directory; never execute shared storage. */
final class ShellAssets {
    static String extract(Context context, String name, boolean executable) throws Exception {
        byte[] bytes;
        try (ZipFile apk = new ZipFile(context.getApplicationInfo().sourceDir)) {
            ZipEntry entry = apk.getEntry("assets/" + name);
            if (entry == null || entry.getSize() < 1 || entry.getSize() > 16 * 1024 * 1024) { throw new java.io.IOException("asset"); }
            try (InputStream in = apk.getInputStream(entry)) { bytes = in.readNBytes(16 * 1024 * 1024 + 1); }
            if (bytes.length > 16 * 1024 * 1024) { throw new java.io.IOException("asset size"); }
        }
        String hash = FileOps.toHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        Path root = Paths.get("/data/local/tmp/phone-station-screen");
        try { Os.mkdir(root.toString(), 0700); } catch (android.system.ErrnoException e) { if (e.errno != OsConstants.EEXIST) { throw e; } }
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Os.lstat(root.toString()).st_uid != Process.myUid()) { throw new java.io.IOException("owner"); }
        Os.chmod(root.toString(), 0700);
        Path dest = root.resolve(name + "-" + hash);
        if (!Files.isRegularFile(dest, LinkOption.NOFOLLOW_LINKS) || !MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(dest)), MessageDigest.getInstance("SHA-256").digest(bytes))) {
            Path temp = Files.createTempFile(root, ".screen-", ".tmp");
            try { Files.write(temp, bytes); Os.chmod(temp.toString(), executable ? 0700 : 0400); Files.move(temp, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            finally { Files.deleteIfExists(temp); }
        }
        return dest.toString();
    }
}
