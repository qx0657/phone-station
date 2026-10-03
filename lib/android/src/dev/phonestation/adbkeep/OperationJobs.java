package dev.phonestation.adbkeep;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** 私有目录里的短期任务回执。提交去重，查询不执行；进程重起后未完成任务为未知。 */
final class OperationJobs implements AutoCloseable {
    static final long RETENTION_MS = 24L * 60 * 60 * 1000;
    static final long QUEUE_MS = 10000;
    static final int RECORD_LIMIT = 256;
    private final Path root;
    private final java.util.function.LongSupplier clock;
    private final Map<String, Json> active = new HashMap<>();
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<Runnable>(4), task -> {
                Thread thread = new Thread(task, "station-operation-job"); thread.setDaemon(true); return thread;
            });
    private boolean closed;

    OperationJobs(Path root) { this(root, System::currentTimeMillis); }
    OperationJobs(Path root, java.util.function.LongSupplier clock) {
        this.root = root;
        this.clock = clock;
        try {
            Files.createDirectories(root);
            Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"));
        } catch (IOException error) { throw new FileFailure("创建任务目录失败"); }
    }

    synchronized Json start(String id, String kind, Json request, Callable<Json> operation) {
        validate(id);
        String fingerprint = digest(kind + ":" + request.emit());
        Json previous = read(id);
        if (previous != null) {
            if (!previous.get("fingerprint").string().equals(fingerprint)) { throw new FileFailure("同一任务编号不能用于不同操作"); }
            return status(id);
        }
        if (closed) { throw new FileFailure("任务服务已停止，尚未执行"); }
        cleanup();
        Json receipt = Json.obj().put("jobId", id).put("kind", kind).put("fingerprint", fingerprint)
                .put("createdAt", clock.getAsLong()).put("state", "queued");
        save(receipt);
        active.put(id, receipt);
        try { worker.execute(() -> {
            try { run(receipt, operation); }
            catch (RuntimeException storageFailure) {
                synchronized (OperationJobs.this) {
                    receipt.put("state", "result_unknown").put("error", "任务回执不可用，请核实结果，不要重做");
                    active.remove(id);
                    try { save(receipt); } catch (FileFailure ignored) {}
                }
            }
        }); }
        catch (java.util.concurrent.RejectedExecutionException busy) {
            receipt.put("state", "rejected").put("error", "任务队列已满，尚未执行");
            save(receipt); active.remove(id);
        }
        return copy(receipt);
    }

    synchronized Json status(String id) {
        validate(id);
        Json receipt = read(id);
        if (receipt == null) { return Json.obj().put("jobId", id).put("state", "missing"); }
        String state = receipt.get("state").string();
        if ((state.equals("queued") || state.equals("running")) && !active.containsKey(id)) {
            receipt.put("state", "result_unknown").put("error", "应用进程已重起，原任务结果未知；不要重做");
        } else if (state.equals("queued") && clock.getAsLong() - receipt.get("createdAt").longValue() > QUEUE_MS) {
            receipt.put("state", "expired").put("error", "排队期限已过，尚未执行");
            save(receipt);
        }
        return copy(receipt);
    }

    private void run(Json receipt, Callable<Json> operation) {
        String id = receipt.get("jobId").string();
        synchronized (this) {
            if (closed || !receipt.get("state").string().equals("queued")
                    || clock.getAsLong() - receipt.get("createdAt").longValue() > QUEUE_MS) {
                receipt.put("state", "expired").put("error", "排队取消或已过期，尚未执行");
                save(receipt); active.remove(id); return;
            }
            receipt.put("state", "running"); save(receipt);
        }
        Json result = null;
        String error = null;
        try {
            result = operation.call();
            if (result.emit().getBytes(StandardCharsets.UTF_8).length > 512 * 1024) { throw new FileFailure("任务结果超过上限"); }
        } catch (Exception failed) { error = failed instanceof FileFailure ? failed.getMessage() : "任务执行结果未确认，请核实"; }
        synchronized (this) {
            receipt.put("state", error == null ? "completed" : "result_unknown");
            if (error == null) { receipt.put("result", result); }
            else { receipt.put("error", error); }
            try { save(receipt); } finally { active.remove(id); }
        }
    }

    private Json read(String id) {
        if (active.containsKey(id)) { return active.get(id); }
        Path file = root.resolve(id + ".json");
        if (!Files.exists(file)) { return null; }
        try {
            if (!Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 600 * 1024) {
                throw new IOException();
            }
            Json receipt = Json.parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
            if (!id.equals(receipt.get("jobId").string())) { throw new IOException(); }
            return receipt;
        } catch (IOException | IllegalArgumentException error) { throw new FileFailure("任务记录无效；保留编号，不要重复执行"); }
    }

    private void save(Json receipt) {
        Path temp = null;
        try {
            temp = Files.createTempFile(root, ".receipt-", ".tmp");
            Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------"));
            Files.write(temp, receipt.emit().getBytes(StandardCharsets.UTF_8));
            try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(temp, java.nio.file.StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            Files.move(temp, root.resolve(receipt.get("jobId").string() + ".json"),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException error) { throw new FileFailure("任务回执保存失败；保留编号，不要重复执行"); }
        finally { if (temp != null) { try { Files.deleteIfExists(temp); } catch (IOException ignored) {} } }
    }

    private void cleanup() {
        int count = 0;
        try (java.nio.file.DirectoryStream<Path> entries = Files.newDirectoryStream(root, "*.json")) {
            for (Path file : entries) {
                String id = file.getFileName().toString().replace(".json", "");
                if (!id.matches("[0-9a-f]{32}")) { continue; }
                if (!active.containsKey(id) && clock.getAsLong() - Files.getLastModifiedTime(file).toMillis() >= RETENTION_MS) {
                    Files.delete(file);
                } else { count++; }
            }
        } catch (IOException error) { throw new FileFailure("任务目录不可用，尚未执行"); }
        if (count >= RECORD_LIMIT) { throw new FileFailure("任务回执已满，尚未执行；等待一天后自动过期"); }
    }

    @Override public synchronized void close() {
        closed = true; worker.shutdownNow();
        for (Json receipt : active.values()) {
            receipt.put("state", "result_unknown").put("error", "服务停止，结果未确认；不要重做");
            try { save(receipt); } catch (FileFailure ignored) {}
        }
        active.clear();
    }
    private static Json copy(Json value) { return Json.parse(value.emit()); }
    static void validate(String id) {
        if (id == null || !id.matches("[0-9a-f]{32}")) { throw new IllegalArgumentException("jobId 需要 32 位小写十六进制"); }
    }
    private static String digest(String value) {
        try { return FileOps.toHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
