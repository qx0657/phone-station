package dev.phonestation.adbkeep;

import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import android.system.Os;
import android.system.OsConstants;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;

/** Shizuku-owned PTY helper. Only the owning application can call this binder. */
public final class TerminalUserService extends Binder {
    static final String DESCRIPTOR = "dev.phonestation.adbkeep.TerminalUserService";
    static final int CALL = IBinder.FIRST_CALL_TRANSACTION;
    static final int DESTROY = 16_777_115;
    private final Context context;
    private final int ownerUid;
    private final ExecutorService io = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "station-terminal-io"); thread.setDaemon(true); return thread;
    });
    private java.lang.Process helper;
    private BufferedReader reader;
    private OutputStreamWriter writer;

    public TerminalUserService(Context context) {
        this.context = context; ownerUid = context.getApplicationInfo().uid;
        attachInterface(null, DESCRIPTOR);
    }

    @Override protected synchronized boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (code == DESTROY) {
            if (Binder.getCallingUid() != ownerUid && Binder.getCallingUid() != Process.myUid()) { throw new SecurityException("只允许手机工位或 Shizuku 停止服务"); }
            stop(); System.exit(0); return true;
        }
        if (code != CALL) { return super.onTransact(code, data, reply, flags); }
        data.enforceInterface(DESCRIPTOR);
        if (Binder.getCallingUid() != ownerUid) { throw new SecurityException("只允许手机工位调用"); }
        Json result;
        try {
            int uid = Process.myUid();
            if (uid != 2000 && uid != 0) { throw new IllegalArgumentException("终端服务没有 shell/root 身份"); }
            String body = data.readString();
            if (body == null || body.length() > 12000) { throw new IllegalArgumentException("终端请求过大"); }
            Json request = Json.parse(body);
            OperationJobs.validate(request.get("sessionId").string());
            boolean open = request.get("op").string().equals("open");
            if (helper == null || !helper.isAlive()) {
                if (!open) {
                    result = TerminalJobs.ended(request.get("sessionId").string(), "lost", "原会话已结束，不会重新创建");
                    reply.writeNoException(); reply.writeString(result.emit()); return true;
                }
                start();
            }
            // Native operations return immediately; a hung helper is terminated without replay.
            result = io.submit(() -> exchange(body)).get(3, TimeUnit.SECONDS);
            result.put("uid", uid).put("identity", uid == 0 ? "root" : "shell");
        } catch (Exception error) {
            stop();
            if (error instanceof InterruptedException) { Thread.currentThread().interrupt(); }
            result = Json.obj().put("error", "终端操作结果未确认，请查询原会话，不重发输入");
        }
        reply.writeNoException(); reply.writeString(result.emit()); return true;
    }

    private Json exchange(String body) throws Exception {
        writer.write(body); writer.write('\n'); writer.flush();
        String line = reader.readLine();
        if (line == null || line.length() > 100000) { throw new java.io.IOException(); }
        return Json.parse(line);
    }

    private void start() throws Exception {
        // Go's Android/arm64 build has no dynamic libraries or NDK dependency.
        byte[] binary;
        try (ZipFile apk = new ZipFile(context.getApplicationInfo().sourceDir)) {
            java.util.zip.ZipEntry asset = apk.getEntry("assets/terminal-arm64");
            if (asset == null || asset.getSize() < 1 || asset.getSize() > 16 * 1024 * 1024) { throw new java.io.IOException(); }
            try (java.io.InputStream stream = apk.getInputStream(asset);
                 java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = stream.read(buffer)) != -1) {
                    if (bytes.size() + count > 16 * 1024 * 1024) { throw new java.io.IOException(); }
                    bytes.write(buffer, 0, count);
                }
                binary = bytes.toByteArray();
            }
        }
        String hash = FileOps.toHex(MessageDigest.getInstance("SHA-256").digest(binary));
        Path root = java.nio.file.Paths.get("/data/local/tmp/phone-station-terminal");
        try { Os.mkdir(root.toString(), 0700); } catch (android.system.ErrnoException error) { if (error.errno != OsConstants.EEXIST) { throw error; } }
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Os.lstat(root.toString()).st_uid != Process.myUid()) { throw new java.io.IOException(); }
        Os.chmod(root.toString(), 0700);
        Path executable = root.resolve("pty-" + hash);
        if (!Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS)
                || !MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(executable)),
                                          MessageDigest.getInstance("SHA-256").digest(binary))) {
            Path temp = Files.createTempFile(root, ".pty-", ".tmp");
            try {
                Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rwx------"));
                Files.write(temp, binary);
                Files.move(temp, executable, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(temp); }
        }
        Os.chmod(executable.toString(), 0700);
        helper = new ProcessBuilder(executable.toString()).redirectError(new java.io.File("/dev/null")).start();
        reader = new BufferedReader(new InputStreamReader(helper.getInputStream(), StandardCharsets.UTF_8));
        writer = new OutputStreamWriter(helper.getOutputStream(), StandardCharsets.UTF_8);
    }
    private void stop() {
        // SIGTERM is handled by the helper and cleans all PTYs. Kill before
        // closing Java's buffered writer so a stalled pipe cannot hold its lock.
        if (helper != null) {
            helper.destroy();
            try { if (!helper.waitFor(1, TimeUnit.SECONDS)) { helper.destroyForcibly(); } }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); helper.destroyForcibly(); }
        }
        try { if (writer != null) { writer.close(); } } catch (java.io.IOException ignored) {}
        helper = null; reader = null; writer = null;
    }
}
