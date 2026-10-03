package dev.phonestation.adbkeep;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.UUID;

/** 原编号查询只恢复已有图片，不重新截屏。 */
final class ScreenCapture {
    static final String ROOT = "Download/手机工位/.captures";
    static final long LIMIT = 32L * 1024 * 1024;
    static final long TTL_MS = 15L * 60 * 1000;
    static final long RECEIPT_TTL_MS = 24L * 60 * 60 * 1000;
    static final long QUOTA = 128L * 1024 * 1024;
    interface Shell { Json execute(ShellRequest request); }

    static String path(String requestId) {
        if (requestId == null || !requestId.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            throw new IllegalArgumentException("requestId 必须是 UUID");
        }
        return ROOT + "/" + UUID.fromString(requestId).toString() + ".png";
    }

    static Json capture(FileOps files, Shell shell, String requestId) {
        synchronized (FileOps.TRANSACTIONS) {
            String input = path(requestId);
            files.createDirectory(ROOT);
            long[] usage = cleanup(files, System.currentTimeMillis());
            if (usage[0] >= 8 || usage[1] > QUOTA - LIMIT || usage[2] >= 4096) {
                throw new FileFailure("截图临时空间已满，请清理先前截图或等待过期");
            }
            // 回执保留一天，release 后重用编号也不会重拍。
            files.writeText(input + ".request", "reserved", null);
            Json created = files.writeBytes(input, "", null);
            String absolute = created.get("path").string();
            boolean complete = false;
            try {
                Json result = shell.execute(new ShellRequest("/system/bin/screencap -p '" + absolute.replace("'", "'\\''") + "'", 15000, 4096));
                if (result.get("timedOut").boolValue() || result.get("exitCode").isNull()
                        || result.get("exitCode").longValue() != 0) {
                    throw new FileFailure("截屏没有完成，请检查 Shizuku 和手机屏幕状态");
                }
                Json info = information(files, requestId);
                complete = true;
                return info;
            } finally {
                if (!complete) { try { release(files, requestId); } catch (FileFailure ignored) {} }
            }
        }
    }

    static Json status(FileOps files, String requestId) {
        synchronized (FileOps.TRANSACTIONS) {
            path(requestId);
            cleanup(files, System.currentTimeMillis());
            try { return information(files, requestId).put("available", true); }
            catch (FileFailure failure) {
                if (!"没有这个文件".equals(failure.getMessage())) { throw failure; }
                return Json.obj().put("requestId", requestId).put("available", false).put("state", "unavailable");
            }
        }
    }

    private static Json information(FileOps files, String requestId) {
        String input = path(requestId);
        Json info = files.stat(input);
        long size = info.get("size").longValue();
        if (size < 8 || size > LIMIT) { throw new FileFailure("截图大小无效或超过 32 MiB"); }
        try (InputStream stream = Files.newInputStream(files.openable(input))) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];
            byte[] signature = new byte[8];
            int count;
            long total = 0;
            while ((count = stream.read(buffer)) != -1) {
                int prefix = (int) Math.min(count, Math.max(0, 8 - total));
                if (prefix > 0) { System.arraycopy(buffer, 0, signature, (int) total, prefix); }
                total += count;
                if (total > LIMIT) { throw new FileFailure("截图超过 32 MiB"); }
                digest.update(buffer, 0, count);
            }
            if (total != size || !FileOps.toHex(signature).equalsIgnoreCase("89504e470d0a1a0a")
                    || !files.stat(input).get("targetVersion").string().equals(info.get("targetVersion").string())) {
                throw new FileFailure("截图已变化或不是 PNG");
            }
            return info.put("requestId", requestId).put("sha256", FileOps.toHex(digest.digest()));
        } catch (IOException error) { throw new FileFailure("读不了本次截图"); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    static Json release(FileOps files, String requestId) {
        synchronized (FileOps.TRANSACTIONS) {
            try { return files.delete(path(requestId)).put("released", true); }
            catch (FileFailure failure) {
                if (!"没有这个文件".equals(failure.getMessage())) { throw failure; }
                return Json.obj().put("requestId", requestId).put("released", true).put("alreadyReleased", true);
            }
        }
    }

    /** 仅处理本服务 UUID 命名的普通文件；不跟随链接，不删用户文件。 */
    static long[] cleanup(FileOps files, long now) {
        synchronized (FileOps.TRANSACTIONS) {
            long[] usage = new long[3]; // png 个数、字节、保留的编号回执个数
            Json list;
            try { list = files.list(ROOT); }
            catch (FileFailure missing) {
                if ("没有这个文件".equals(missing.getMessage())) { return usage; }
                throw missing;
            }
            for (Json entry : list.get("entries").array()) {
                String name = entry.get("name").string();
                if (!"file".equals(entry.get("type").string())
                        || !name.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.png(\\.request)?")) { continue; }
                boolean receipt = name.endsWith(".request");
                long age = now - entry.get("mtime").longValue();
                if (age >= (receipt ? RECEIPT_TTL_MS : TTL_MS)) {
                    files.delete(ROOT + "/" + name);
                } else if (receipt) { usage[2]++; }
                else { usage[0]++; usage[1] += entry.get("size").longValue(); }
            }
            return usage;
        }
    }
}
