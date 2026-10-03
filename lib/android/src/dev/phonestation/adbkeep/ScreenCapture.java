package dev.phonestation.adbkeep;

import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.UUID;

/** 每次截图有独立文件；不会在丢失响应后重新截取。 */
final class ScreenCapture {
    static final String ROOT = "Download/手机工位/.captures";
    static final long LIMIT = 32L * 1024 * 1024;
    interface Shell { Json execute(ShellRequest request); }

    static String path(String requestId) {
        if (requestId == null || !requestId.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            throw new IllegalArgumentException("requestId 必须是 UUID");
        }
        return ROOT + "/" + UUID.fromString(requestId).toString() + ".png";
    }

    static Json capture(FileOps files, Shell shell, String requestId) {
        String input = path(requestId);
        files.createDirectory(ROOT);
        // FileOps 检查路径、不跟随链接；同一编号已有文件时拒绝覆盖和重拍。
        Json created = files.writeBytes(input, "", null);
        String absolute = created.get("path").string();
        boolean complete = false;
        try {
            Json result = shell.execute(new ShellRequest("/system/bin/screencap -p '" + absolute.replace("'", "'\\''") + "'", 15000, 4096));
            if (result.get("timedOut").boolValue() || result.get("exitCode").isNull()
                    || result.get("exitCode").longValue() != 0) {
                throw new FileFailure("截屏没有完成，请检查 Shizuku 和手机屏幕状态");
            }
            Json info = files.stat(input);
            long size = info.get("size").longValue();
            if (size < 8 || size > LIMIT) { throw new FileFailure("截图大小无效或超过 32 MiB"); }
            byte[] png;
            try { png = Files.readAllBytes(files.openable(input)); }
            catch (IOException error) { throw new FileFailure("读不了本次截图"); }
            if (png.length != size) { throw new FileFailure("本次截图已变化"); }
            if (!FileOps.toHex(java.util.Arrays.copyOf(png, 8)).equalsIgnoreCase("89504e470d0a1a0a")) {
                throw new FileFailure("截屏没有返回 PNG 图片");
            }
            String hash;
            try { hash = FileOps.toHex(MessageDigest.getInstance("SHA-256").digest(png)); }
            catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
            complete = true;
            return info.put("requestId", requestId).put("sha256", hash);
        } finally {
            if (!complete) { try { files.delete(input); } catch (FileFailure ignored) {} }
        }
    }

    static Json release(FileOps files, String requestId) { return files.delete(path(requestId)); }
}
