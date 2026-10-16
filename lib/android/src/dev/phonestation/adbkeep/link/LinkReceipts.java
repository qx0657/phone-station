package dev.phonestation.adbkeep.link;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Link v2 回执与三个执行队列。现有工具不调用。 */
public final class LinkReceipts implements AutoCloseable {
    public static final int BODY_LIMIT = 64 * 1024;
    public static final int IN_FLIGHT = 8;
    public static final int MAX_RECEIPTS = 256;
    public static final long KEEP_MS = 24L * 60 * 60 * 1000;
    public static final long BODY_MS = 10L * 60 * 1000;
    public static final long EVICT_MS = 60L * 60 * 1000;
    public static final long PRIVILEGED_QUEUE_MS = 10_000;

    public interface Clock {
        long elapsedMillis();
        String bootId();
    }

    public static final class Receipt {
        public final String state;
        public final boolean applied;
        public final String method;
        public final String digest;
        public final byte[] body;
        public final String epoch;
        public final String macId;
        public final String op;

        Receipt(String state, boolean applied, String method, String digest, byte[] body, String epoch, String macId, String op) {
            this.state = state;
            this.applied = applied;
            this.method = method;
            this.digest = digest;
            this.body = body;
            this.epoch = epoch;
            this.macId = macId;
            this.op = op;
        }
    }

    private final Path root;
    private final Clock clock;
    private final int maxReceipts;
    private final Object lock = new Object();
    private final Map<String, Integer> inFlight = new HashMap<String, Integer>();
    private final Set<String> cancelRequested = new HashSet<String>();
    private final Semaphore interactive = new Semaphore(4);
    private final Semaphore files = new Semaphore(2);
    private final Semaphore privileged = new Semaphore(1);
    private final ExecutorService workers = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "link-receipt");
        thread.setDaemon(true);
        return thread;
    });
    private final String epoch;
    private int privilegedQueued;
    private boolean closed;

    public LinkReceipts(Path root, Clock clock) throws IOException {
        this(root, clock, MAX_RECEIPTS);
    }

    public LinkReceipts(Path root, Clock clock, int maxReceipts) throws IOException {
        if (clock == null || clock.bootId() == null || !clock.bootId().matches("[A-Za-z0-9._-]{1,64}") || maxReceipts < 1) {
            throw new IllegalArgumentException("clock");
        }
        this.root = root;
        this.clock = clock;
        this.maxReceipts = maxReceipts;
        if (Files.isSymbolicLink(root)) {
            throw new IOException("receipt directory");
        }
        Files.createDirectories(root);
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"));
        this.epoch = loadEpoch();
        recover();
    }

    public String epoch() {
        return epoch;
    }

    public static boolean durable(String method) {
        return !("device.status".equals(method) || "capabilities.get".equals(method) || "op.query".equals(method)
                || "file.stat".equals(method) || "file.read".equals(method) || "clipboard.get".equals(method)
                || "notifications.list".equals(method));
    }

    public static String lane(String method) {
        if (method.startsWith("shell.") || method.startsWith("install.") || method.startsWith("screen.") || "controls.capture".equals(method)) {
            return "privileged";
        }
        if (method.startsWith("file.")) {
            return "file";
        }
        return "interactive";
    }

    public Receipt submit(String macId, String op, String method, byte[] params, Callable<byte[]> work) throws Exception {
        validate(macId);
        validate(op);
        if (method == null || !method.matches("[a-z]+\\.[a-z]+") || work == null) {
            throw new IllegalArgumentException("method");
        }
        byte[] body = params == null ? new byte[0] : params;
        String digest = sha256(body);
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("closed");
            }
        }
        if (!durable(method)) {
            return new Receipt("complete", false, method, digest, work.call(), epoch, macId, op);
        }
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("closed");
            }
            maintain();
            Record existing = read(macId, op);
            if (existing != null) {
                if (!digest.equals(existing.digest)) {
                    return new Receipt("conflict", existing.applied, existing.method, existing.digest, existing.body, epoch, macId, op);
                }
                return view(existing);
            }
            if (count(macId) >= IN_FLIGHT || ("privileged".equals(lane(method)) && privilegedQueued >= 4) || !room()) {
                return new Receipt("busy", false, method, digest, null, epoch, macId, op);
            }
            Record created = new Record(macId, op, method, digest, "queued", false, clock.bootId(), clock.elapsedMillis(), null);
            write(created);
            inFlight.put(macId, count(macId) + 1);
            if ("privileged".equals(lane(method))) {
                privilegedQueued++;
            }
        }
        workers.execute(() -> run(macId, op, work));
        synchronized (lock) {
            Record stored = read(macId, op);
            return stored == null ? new Receipt("not_found", false, method, digest, null, epoch, macId, op) : view(stored);
        }
    }

    public Receipt query(String macId, String op) throws IOException {
        validate(macId);
        validate(op);
        synchronized (lock) {
            maintain();
            Record stored = read(macId, op);
            if (stored == null) {
                return new Receipt("not_found", false, "", "", null, epoch, macId, op);
            }
            if (terminal(stored.state) && stored.body != null && age(stored) >= BODY_MS) {
                stored.body = null;
                write(stored);
            }
            return view(stored);
        }
    }

    public Receipt ack(String macId, String op) throws IOException {
        validate(macId);
        validate(op);
        synchronized (lock) {
            Record stored = read(macId, op);
            if (stored == null) {
                return new Receipt("not_found", false, "", "", null, epoch, macId, op);
            }
            stored.body = null;
            write(stored);
            return view(stored);
        }
    }

    public Receipt cancel(String macId, String op) throws IOException {
        validate(macId);
        validate(op);
        synchronized (lock) {
            Record stored = read(macId, op);
            if (stored == null) {
                return new Receipt("not_found", false, "", "", null, epoch, macId, op);
            }
            if ("queued".equals(stored.state)) {
                if ("privileged".equals(lane(stored.method))) {
                    privilegedQueued--;
                }
                stored.state = "canceled";
                stored.applied = false;
                write(stored);
                releaseQuiet(stored.macId);
                return view(stored);
            }
            if ("running".equals(stored.state)) {
                cancelRequested.add(stored.macId + "." + stored.op);
            }
            return view(stored);
        }
    }

    public boolean cancelRequested(String macId, String op) {
        validate(macId);
        validate(op);
        synchronized (lock) {
            return cancelRequested.contains(macId + "." + op);
        }
    }

    @Override public void close() {
        synchronized (lock) {
            closed = true;
        }
        workers.shutdownNow();
        try {
            workers.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private void run(String macId, String op, Callable<byte[]> work) {
        Record stored;
        synchronized (lock) {
            stored = quietRead(macId, op);
        }
        if (stored == null) {
            return;
        }
        Semaphore lane = semaphore(lane(stored.method));
        try {
            lane.acquire();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return;
        }
        boolean started = false;
        try {
            synchronized (lock) {
                stored = quietRead(macId, op);
                if (stored == null || !"queued".equals(stored.state) || closed) {
                    return;
                }
                if ("privileged".equals(lane(stored.method)) && age(stored) > PRIVILEGED_QUEUE_MS) {
                    stored.state = "expired";
                    stored.applied = false;
                    write(stored);
                    privilegedQueued--;
                    releaseQuiet(stored.macId);
                    return;
                }
                stored.state = "running";
                write(stored);
                if ("privileged".equals(lane(stored.method))) {
                    privilegedQueued--;
                }
                started = true;
            }
            byte[] body = null;
            Exception failure = null;
            try {
                body = work.call();
            } catch (Exception error) {
                failure = error;
            }
            synchronized (lock) {
                if (closed) {
                    return;
                }
                stored = quietRead(macId, op);
                if (stored == null) {
                    return;
                }
                stored.applied = true;
                if (failure != null || (body != null && body.length > BODY_LIMIT)) {
                    stored.state = "failed";
                    stored.body = null;
                } else {
                    stored.state = "complete";
                    stored.body = body;
                }
                write(stored);
                cancelRequested.remove(stored.macId + "." + stored.op);
                releaseQuiet(stored.macId);
            }
        } catch (IOException error) {
            synchronized (lock) {
                if (started) {
                    releaseQuiet(macId);
                }
            }
        } finally {
            lane.release();
        }
    }

    private void releaseQuiet(String macId) {
        int left = count(macId) - 1;
        if (left <= 0) {
            inFlight.remove(macId);
        } else {
            inFlight.put(macId, left);
        }
    }

    private Semaphore semaphore(String lane) {
        if ("privileged".equals(lane)) {
            return privileged;
        }
        if ("file".equals(lane)) {
            return files;
        }
        return interactive;
    }

    private void recover() throws IOException {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(root, "*.rec")) {
            for (Path file : entries) {
                Record stored = readFile(file);
                if (stored == null) {
                    continue;
                }
                if ("queued".equals(stored.state)) {
                    stored.state = "expired";
                    stored.applied = false;
                    write(stored);
                } else if ("running".equals(stored.state)) {
                    stored.state = "unknown";
                    stored.applied = true;
                    write(stored);
                }
            }
        }
    }

    private void maintain() throws IOException {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(root, "*.rec")) {
            for (Path file : entries) {
                Record stored = readFile(file);
                if (stored == null || !terminal(stored.state)) {
                    continue;
                }
                if (age(stored) >= KEEP_MS) {
                    Files.deleteIfExists(file);
                }
            }
        }
    }

    private boolean room() throws IOException {
        int count = 0;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(root, "*.rec")) {
            for (Path file : entries) {
                count++;
            }
        }
        if (count < maxReceipts) {
            return true;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(root, "*.rec")) {
            for (Path file : entries) {
                Record stored = readFile(file);
                if (stored != null && terminal(stored.state) && age(stored) >= EVICT_MS) {
                    Files.deleteIfExists(file);
                    count--;
                }
            }
        }
        return count < maxReceipts;
    }

    private String loadEpoch() throws IOException {
        Path file = root.resolve("epoch");
        if (Files.isSymbolicLink(file)) {
            throw new IOException("epoch");
        }
        if (!Files.exists(file)) {
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(root, "*.rec")) {
                if (entries.iterator().hasNext()) {
                    throw new IOException("epoch missing");
                }
            }
            byte[] raw = new byte[16];
            new SecureRandom().nextBytes(raw);
            String created = hex(raw);
            Files.write(file, created.getBytes(StandardCharsets.US_ASCII));
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            return created;
        }
        String text = Files.readString(file).trim();
        if (!text.matches("[0-9a-f]{32}")) {
            throw new IOException("epoch");
        }
        return text;
    }

    private Record read(String macId, String op) throws IOException {
        return readFile(path(macId, op));
    }

    private Record quietRead(String macId, String op) {
        try {
            return read(macId, op);
        } catch (IOException error) {
            return null;
        }
    }

    private Record readFile(Path file) throws IOException {
        if (!Files.exists(file)) {
            return null;
        }
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file) || Files.size(file) > BODY_LIMIT + 1024) {
            throw new IOException("receipt");
        }
        String[] parts = Files.readString(file).split("\t", -1);
        if (parts.length != 10 || !"v1".equals(parts[0])) {
            throw new IOException("receipt");
        }
        final long elapsed;
        final byte[] body;
        try {
            elapsed = Long.parseLong(parts[8]);
            body = "-".equals(parts[9]) ? null : Base64.getDecoder().decode(parts[9]);
        } catch (IllegalArgumentException error) {
            throw new IOException("receipt");
        }
        Record stored = new Record(parts[1], parts[2], parts[3], parts[4], parts[5], "1".equals(parts[6]), parts[7], elapsed, body);
        if (!file.getFileName().toString().equals(stored.macId + "." + stored.op + ".rec")) {
            throw new IOException("receipt");
        }
        return stored;
    }

    private void write(Record stored) throws IOException {
        Path temp = Files.createTempFile(root, ".pending-", ".tmp");
        try {
            Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------"));
            String body = stored.body == null ? "-" : Base64.getEncoder().encodeToString(stored.body);
            String line = String.join("\t", "v1", stored.macId, stored.op, stored.method, stored.digest, stored.state, stored.applied ? "1" : "0", stored.boot, Long.toString(stored.elapsed), body);
            Files.write(temp, line.getBytes(StandardCharsets.US_ASCII));
            try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(temp, java.nio.file.StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            Files.move(temp, path(stored.macId, stored.op), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private Path path(String macId, String op) {
        return root.resolve(macId + "." + op + ".rec");
    }

    private int count(String macId) {
        Integer value = inFlight.get(macId);
        return value == null ? 0 : value;
    }

    private long age(Record stored) {
        if (!stored.boot.equals(clock.bootId())) {
            return Math.max(0, clock.elapsedMillis());
        }
        return Math.max(0, clock.elapsedMillis() - stored.elapsed);
    }

    private Receipt view(Record stored) {
        return new Receipt(stored.state, stored.applied, stored.method, stored.digest, stored.body, epoch, stored.macId, stored.op);
    }

    private static boolean terminal(String state) {
        return "complete".equals(state) || "failed".equals(state) || "expired".equals(state) || "canceled".equals(state) || "unknown".equals(state);
    }

    private static void validate(String id) {
        if (id == null || !id.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException("id");
        }
    }

    private static String sha256(byte[] body) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(body));
    }

    private static String hex(byte[] raw) {
        StringBuilder out = new StringBuilder(raw.length * 2);
        for (byte value : raw) {
            out.append(Character.forDigit((value >> 4) & 0xf, 16)).append(Character.forDigit(value & 0xf, 16));
        }
        return out.toString();
    }

    private static final class Record {
        final String macId;
        final String op;
        final String method;
        final String digest;
        String state;
        boolean applied;
        final String boot;
        final long elapsed;
        byte[] body;

        Record(String macId, String op, String method, String digest, String state, boolean applied, String boot, long elapsed, byte[] body) {
            this.macId = macId;
            this.op = op;
            this.method = method;
            this.digest = digest;
            this.state = state;
            this.applied = applied;
            this.boot = boot;
            this.elapsed = elapsed;
            this.body = body;
        }
    }
}
