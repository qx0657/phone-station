package dev.phonestation.adbkeep.link;

import java.io.FileDescriptor;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Link v2 回执与三个执行队列。现有工具不调用。 */
public final class LinkReceipts implements AutoCloseable {
    public static final int BODY_LIMIT = 64 * 1024;
    public static final int BODY_BUDGET = 8 * 1024 * 1024;
    public static final int IN_FLIGHT = 8;
    public static final int MAX_RECEIPTS = 4096;
    public static final long KEEP_MS = 24L * 60 * 60 * 1000;
    public static final long BODY_MS = 10L * 60 * 1000;
    public static final long EVICT_MS = 60L * 60 * 1000;
    public static final long PRIVILEGED_QUEUE_MS = 10_000;

    /** 方法在产生任何副作用之前失败。回执记为 failed 且 applied 为 false。 */
    public static final class NotApplied extends Exception {
        public NotApplied() {}
    }

    /** 原子改名之后同步目录。电脑测试注入替身；手机上走 android.system.Os。 */
    public interface DirectorySync {
        void sync(Path directory) throws IOException;

        static DirectorySync platform() {
            try {
                Class.forName("android.system.Os");
            } catch (ClassNotFoundException missing) {
                return directory -> { };
            }
            try {
                Class<?> os = Class.forName("android.system.Os");
                Class<?> constants = Class.forName("android.system.OsConstants");
                int flags = constants.getField("O_RDONLY").getInt(null);
                Method open = os.getMethod("open", String.class, int.class, int.class);
                Method fsync = os.getMethod("fsync", FileDescriptor.class);
                Method close = os.getMethod("close", FileDescriptor.class);
                return directory -> syncAndroid(open, fsync, close, directory, flags);
            } catch (ReflectiveOperationException error) {
                return directory -> { throw new IOException("directory sync", error); };
            }
        }
    }

    /** 测试用来让某次状态写入失败。 */
    public interface WriteGate {
        void beforeWrite(String state) throws IOException;
    }

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
        public final boolean resultKept;
        public final String epoch;
        public final String macId;
        public final String op;

        Receipt(String state, boolean applied, String method, String digest, byte[] body, boolean resultKept, String epoch, String macId, String op) {
            this.state = state;
            this.applied = applied;
            this.method = method;
            this.digest = digest;
            this.body = body;
            this.resultKept = resultKept;
            this.epoch = epoch;
            this.macId = macId;
            this.op = op;
        }
    }

    private final Path root;
    private final Clock clock;
    private final int maxReceipts;
    private final int bodyBudget;
    private final DirectorySync directories;
    private final WriteGate gate;
    private final Object lock = new Object();
    private final Map<String, Record> index = new HashMap<String, Record>();
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
    private final ScheduledExecutorService sweeper = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "link-receipt-sweep");
        thread.setDaemon(true);
        return thread;
    });
    private ScheduledFuture<?> dueTask;
    private final String epoch;
    private int privilegedQueued;
    private boolean closed;
    private Listener listener;
    private LinkEvents events;
    private final List<Receipt> notes = new ArrayList<Receipt>();

    public LinkReceipts(Path root) throws IOException {
        this(root, systemClock());
    }

    public static Clock systemClock() {
        String boot = bootId();
        return new Clock() {
            @Override public long elapsedMillis() { return uptimeMillis(); }
            @Override public String bootId() { return boot; }
        };
    }

    private static String bootId() {
        try {
            String line = new String(Files.readAllBytes(Path.of("/proc/sys/kernel/random/boot_id")), StandardCharsets.UTF_8).trim();
            if (line.matches("[A-Za-z0-9._-]{1,64}")) {
                return line;
            }
        } catch (IOException ignored) {
            // 电脑上的测试没有这个文件。同一次进程用固定开机编号。
        }
        return "desktop";
    }

    private static long uptimeMillis() {
        try {
            String text = new String(Files.readAllBytes(Path.of("/proc/uptime")), StandardCharsets.UTF_8).trim();
            int space = text.indexOf(' ');
            String seconds = space < 0 ? text : text.substring(0, space);
            return (long) (Double.parseDouble(seconds) * 1000);
        } catch (IOException | RuntimeException ignored) {
            return TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
        }
    }

    public LinkReceipts(Path root, Clock clock) throws IOException {
        this(root, clock, MAX_RECEIPTS, BODY_BUDGET, null, null);
    }

    public LinkReceipts(Path root, Clock clock, int maxReceipts) throws IOException {
        this(root, clock, maxReceipts, BODY_BUDGET, null, null);
    }

    public LinkReceipts(Path root, Clock clock, int maxReceipts, int bodyBudget, DirectorySync directories, WriteGate gate) throws IOException {
        if (clock == null || clock.bootId() == null || !clock.bootId().matches("[A-Za-z0-9._-]{1,64}") || maxReceipts < 1 || bodyBudget < 0) {
            throw new IllegalArgumentException("clock");
        }
        this.root = root;
        this.clock = clock;
        this.maxReceipts = maxReceipts;
        this.bodyBudget = bodyBudget;
        this.directories = directories == null ? DirectorySync.platform() : directories;
        this.gate = gate;
        if (Files.isSymbolicLink(root)) {
            throw new IOException("receipt directory");
        }
        Files.createDirectories(root);
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"));
        this.epoch = loadEpoch();
        recover();
        armLocked();
    }

    public String epoch() {
        return epoch;
    }

    /** 回执状态变成 queued、running 或终态时通知一次。 */
    public interface Listener {
        void changed(Receipt receipt);
    }

    public LinkEvents events() {
        synchronized (lock) {
            return events;
        }
    }

    /** 已经建过就沿用。clock 为 null 时用自动合并的系统时钟。 */
    public LinkEvents ensureEvents(LinkEvents.Clock clock) {
        synchronized (lock) {
            if (events != null) {
                return events;
            }
            events = clock == null ? LinkEvents.automatic() : LinkEvents.manual(clock);
            listener = events::changed;
            return events;
        }
    }

    public static boolean durable(String method) {
        if (method == null || method.startsWith("meta.")) {
            return false;
        }
        int dot = method.indexOf('.');
        String action = dot < 0 ? method : method.substring(dot + 1);
        return !(action.equals("status") || action.equals("stat") || action.equals("get") || action.equals("read")
                || action.equals("list") || action.equals("query") || action.equals("poll") || action.equals("state")
                || action.equals("summary") || action.startsWith("read") || action.startsWith("search")
                || action.endsWith("status"));
    }

    public static String lane(String method) {
        if (method.startsWith("shell.") || method.startsWith("install.") || method.startsWith("screen.")
                || method.startsWith("terminal.") || "controls.capture".equals(method)) {
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
        if (!durable(method)) {
            return submitReadOnly(macId, op, method, digest, work);
        }
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("closed");
            }
            expireDueLocked();
            Record existing = index.get(key(macId, op));
            if (existing != null) {
                if (!digest.equals(existing.digest)) {
                    return conflict(existing);
                }
                return view(existing);
            }
            if (count(macId) >= IN_FLIGHT || ("privileged".equals(lane(method)) && privilegedQueued >= 4) || !room()) {
                return busy(method, digest, macId, op);
            }
            Record created = new Record(macId, op, method, digest, "queued", false, clock.bootId(), clock.elapsedMillis(), null);
            try {
                write(created);
            } catch (IOException error) {
                try {
                    Files.deleteIfExists(path(macId, op));
                    directories.sync(root);
                } catch (IOException ignored) {
                }
                return busy(method, digest, macId, op);
            }
            index.put(key(macId, op), created);
            inFlight.put(macId, count(macId) + 1);
            if ("privileged".equals(lane(method))) {
                privilegedQueued++;
            }
            armLocked();
            noteLocked(created);
        }
        flushNotes();
        workers.execute(() -> run(macId, op, work));
        synchronized (lock) {
            Record stored = index.get(key(macId, op));
            return stored == null ? missing(macId, op) : view(stored);
        }
    }

    public Receipt query(String macId, String op) throws IOException {
        validate(macId);
        validate(op);
        Receipt receipt;
        synchronized (lock) {
            expireDueLocked();
            Record stored = index.get(key(macId, op));
            receipt = stored == null ? missing(macId, op) : view(stored);
        }
        flushNotes();
        return receipt;
    }

    public Receipt ack(String macId, String op) throws IOException {
        validate(macId);
        validate(op);
        synchronized (lock) {
            Record stored = index.get(key(macId, op));
            if (stored == null) {
                return missing(macId, op);
            }
            byte[] previous = stored.body;
            byte[] previousHeld = stored.held;
            boolean previousKept = stored.resultKept;
            stored.body = null;
            stored.held = null;
            stored.resultKept = false;
            try {
                write(stored);
            } catch (IOException error) {
                stored.body = previous;
                stored.held = previousHeld;
                stored.resultKept = previousKept;
                throw error;
            }
            return view(stored);
        }
    }

    public Receipt cancel(String macId, String op) throws IOException {
        validate(macId);
        validate(op);
        Receipt receipt;
        synchronized (lock) {
            Record stored = index.get(key(macId, op));
            if (stored == null) {
                receipt = missing(macId, op);
            } else if ("queued".equals(stored.state)) {
                stored.state = "canceled";
                stored.applied = false;
                markFinished(stored);
                try {
                    write(stored);
                } catch (IOException ignored) {
                }
                if ("privileged".equals(lane(stored.method)) && privilegedQueued > 0) {
                    privilegedQueued--;
                }
                releaseQuiet(stored.macId);
                noteLocked(stored);
                receipt = view(stored);
            } else {
                if ("running".equals(stored.state)) {
                    cancelRequested.add(key(stored.macId, stored.op));
                }
                receipt = view(stored);
            }
        }
        flushNotes();
        return receipt;
    }

    public boolean cancelRequested(String macId, String op) {
        validate(macId);
        validate(op);
        synchronized (lock) {
            return cancelRequested.contains(key(macId, op));
        }
    }

    @Override public void close() {
        LinkEvents stopping;
        synchronized (lock) {
            closed = true;
            stopping = events;
        }
        if (stopping != null) {
            stopping.close();
        }
        if (dueTask != null) {
            dueTask.cancel(false);
        }
        sweeper.shutdownNow();
        workers.shutdownNow();
        try {
            workers.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private Receipt submitReadOnly(String macId, String op, String method, String digest, Callable<byte[]> work) throws Exception {
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("closed");
            }
            expireDueLocked();
            if (count(macId) >= IN_FLIGHT) {
                return busy(method, digest, macId, op);
            }
            inFlight.put(macId, count(macId) + 1);
        }
        Semaphore lane = semaphore(lane(method));
        boolean acquired = false;
        try {
            lane.acquire();
            acquired = true;
            byte[] result = work.call();
            if (result == null) {
                result = new byte[0];
            }
            return receipt("complete", false, method, digest, result, true, macId, op);
        } finally {
            if (acquired) {
                lane.release();
            }
            synchronized (lock) {
                releaseQuiet(macId);
            }
        }
    }

    private void run(String macId, String op, Callable<byte[]> work) {
        synchronized (lock) {
            if (index.get(key(macId, op)) == null) {
                releaseQuiet(macId);
                return;
            }
        }
        Semaphore lane = semaphore(indexLane(macId, op));
        try {
            lane.acquire();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            synchronized (lock) {
                releaseQuiet(macId);
            }
            return;
        }
        boolean started = false;
        try {
            boolean stop = false;
            synchronized (lock) {
                Record stored = index.get(key(macId, op));
                if (stored == null || !"queued".equals(stored.state) || closed) {
                    stop = true;
                } else if ("privileged".equals(lane(stored.method)) && age(stored) > PRIVILEGED_QUEUE_MS) {
                    expireQueued(stored);
                    stop = true;
                } else {
                    stored.state = "running";
                    try {
                        write(stored);
                    } catch (IOException error) {
                        stored.state = "expired";
                        stored.applied = false;
                        try {
                            write(stored);
                        } catch (IOException ignored) {
                        }
                        if ("privileged".equals(lane(stored.method)) && privilegedQueued > 0) {
                            privilegedQueued--;
                        }
                        releaseQuiet(macId);
                        noteLocked(stored);
                        stop = true;
                    }
                    if (!stop) {
                        if ("privileged".equals(lane(stored.method)) && privilegedQueued > 0) {
                            privilegedQueued--;
                        }
                        noteLocked(stored);
                        started = true;
                    }
                }
            }
            flushNotes();
            if (stop || !started) {
                return;
            }
            byte[] body = null;
            Exception failure = null;
            boolean applied = true;
            try {
                body = work.call();
            } catch (NotApplied notApplied) {
                failure = notApplied;
                applied = false;
            } catch (Exception error) {
                failure = error;
            }
            synchronized (lock) {
                if (closed) {
                    return;
                }
                Record stored = index.get(key(macId, op));
                if (stored == null) {
                    return;
                }
                if (failure != null) {
                    stored.state = "failed";
                    stored.applied = applied;
                    stored.held = null;
                    stored.body = null;
                    stored.resultKept = false;
                } else {
                    byte[] full = body == null ? new byte[0] : body;
                    stored.state = "complete";
                    stored.applied = true;
                    stored.held = full;
                    stored.resultKept = full.length <= BODY_LIMIT;
                    stored.body = stored.resultKept ? full : null;
                }
                markFinished(stored);
                try {
                    if (stored.body != null) {
                        trimBodies();
                    }
                    write(stored);
                } catch (IOException ignored) {
                }
                cancelRequested.remove(key(macId, op));
                releaseQuiet(macId);
                armLocked();
                noteLocked(stored);
                started = false;
            }
            flushNotes();
        } finally {
            lane.release();
            if (started) {
                synchronized (lock) {
                    releaseQuiet(macId);
                }
            }
        }
    }

    private String indexLane(String macId, String op) {
        synchronized (lock) {
            Record stored = index.get(key(macId, op));
            return stored == null ? "interactive" : lane(stored.method);
        }
    }

    private void expireQueued(Record stored) {
        stored.state = "expired";
        stored.applied = false;
        markFinished(stored);
        try {
            write(stored);
        } catch (IOException ignored) {
        }
        if ("privileged".equals(lane(stored.method)) && privilegedQueued > 0) {
            privilegedQueued--;
        }
        releaseQuiet(stored.macId);
        noteLocked(stored);
    }

    private void sweepQuiet() {
        try {
            synchronized (lock) {
                if (!closed) {
                    expireDueLocked();
                }
            }
            flushNotes();
        } catch (Exception ignored) {
        }
    }

    private void expireDueLocked() throws IOException {
        List<Record> due = new ArrayList<Record>();
        for (Record stored : index.values()) {
            if ("queued".equals(stored.state) && "privileged".equals(lane(stored.method)) && age(stored) > PRIVILEGED_QUEUE_MS) {
                due.add(stored);
            }
        }
        for (Record stored : due) {
            expireQueued(stored);
        }
        List<Record> dropBody = new ArrayList<Record>();
        List<Record> dropAll = new ArrayList<Record>();
        for (Record stored : index.values()) {
            if (!terminal(stored.state)) {
                continue;
            }
            if (age(stored) >= KEEP_MS) {
                dropAll.add(stored);
            } else if ((stored.body != null || stored.held != null) && age(stored) >= BODY_MS) {
                dropBody.add(stored);
            }
        }
        for (Record stored : dropBody) {
            byte[] previous = stored.body;
            byte[] previousHeld = stored.held;
            boolean previousKept = stored.resultKept;
            stored.body = null;
            stored.held = null;
            stored.resultKept = false;
            try {
                write(stored);
            } catch (IOException error) {
                stored.body = previous;
                stored.held = previousHeld;
                stored.resultKept = previousKept;
                throw error;
            }
        }
        for (Record stored : dropAll) {
            Files.deleteIfExists(path(stored.macId, stored.op));
            index.remove(key(stored.macId, stored.op));
        }
        if (!dropAll.isEmpty()) {
            directories.sync(root);
        }
        trimBodies();
        armLocked();
    }

    private void trimBodies() throws IOException {
        while (bodyBytes() > bodyBudget) {
            Record oldest = null;
            for (Record stored : index.values()) {
                if (stored.body == null || stored.body.length == 0) {
                    continue;
                }
                if (oldest == null || age(stored) > age(oldest)) {
                    oldest = stored;
                }
            }
            if (oldest == null) {
                return;
            }
            byte[] previous = oldest.body;
            byte[] previousHeld = oldest.held;
            boolean previousKept = oldest.resultKept;
            oldest.body = null;
            oldest.held = null;
            oldest.resultKept = false;
            try {
                write(oldest);
            } catch (IOException error) {
                oldest.body = previous;
                oldest.held = previousHeld;
                oldest.resultKept = previousKept;
                throw error;
            }
        }
    }

    private long bodyBytes() {
        long total = 0;
        for (Record stored : index.values()) {
            if (stored.body != null) {
                total += stored.body.length;
            }
        }
        return total;
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
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(root)) {
            for (Path file : entries) {
                String name = file.getFileName().toString();
                if (name.endsWith(".tmp") || name.startsWith(".pending-")) {
                    if (!Files.isDirectory(file)) {
                        Files.deleteIfExists(file);
                    }
                }
            }
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(root, "*.rec")) {
            for (Path file : entries) {
                Record stored;
                try {
                    stored = readFile(file);
                } catch (IOException error) {
                    quarantine(file);
                    continue;
                }
                if (stored == null) {
                    continue;
                }
                if ("queued".equals(stored.state)) {
                    stored.state = "expired";
                    stored.applied = false;
                    markFinished(stored);
                    write(stored);
                } else if ("running".equals(stored.state)) {
                    stored.state = "unknown";
                    stored.applied = true;
                    markFinished(stored);
                    write(stored);
                }
                index.put(key(stored.macId, stored.op), stored);
            }
        }
    }

    private void quarantine(Path file) throws IOException {
        String name = file.getFileName().toString();
        Path dir = root.resolve("corrupt");
        if (Files.isSymbolicLink(dir)) {
            throw new IOException("corrupt");
        }
        Files.createDirectories(dir);
        Files.move(file, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
        if (!name.endsWith(".rec")) {
            return;
        }
        String stem = name.substring(0, name.length() - 4);
        int dot = stem.indexOf('.');
        if (dot < 0) {
            return;
        }
        String mac = stem.substring(0, dot);
        String op = stem.substring(dot + 1);
        if (!mac.matches("[0-9a-f]{32}") || !op.matches("[0-9a-f]{32}")) {
            return;
        }
        Record unknown = new Record(mac, op, "op.query", "", "unknown", true, clock.bootId(), clock.elapsedMillis(), null);
        markFinished(unknown);
        index.put(key(mac, op), unknown);
        write(unknown);
    }

    private boolean room() throws IOException {
        if (index.size() < maxReceipts) {
            return true;
        }
        List<Record> old = new ArrayList<Record>();
        for (Record stored : index.values()) {
            if (terminal(stored.state) && age(stored) >= EVICT_MS) {
                old.add(stored);
            }
        }
        for (Record stored : old) {
            Files.deleteIfExists(path(stored.macId, stored.op));
            index.remove(key(stored.macId, stored.op));
        }
        if (!old.isEmpty()) {
            directories.sync(root);
        }
        return index.size() < maxReceipts;
    }

    private String loadEpoch() throws IOException {
        Path file = root.resolve("epoch");
        if (Files.isSymbolicLink(file)) {
            throw new IOException("epoch");
        }
        if (Files.exists(file)) {
            String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim();
            if (text.matches("[0-9a-f]{32}")) {
                return text;
            }
        }
        return createEpoch(file);
    }

    private String createEpoch(Path file) throws IOException {
        byte[] raw = new byte[16];
        new SecureRandom().nextBytes(raw);
        String created = hex(raw);
        Files.write(file, created.getBytes(StandardCharsets.US_ASCII));
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
        directories.sync(root);
        return created;
    }

    private Record readFile(Path file) throws IOException {
        if (!Files.exists(file)) {
            return null;
        }
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file) || Files.size(file) > BODY_LIMIT * 2L + 1024) {
            throw new IOException("receipt");
        }
        String[] parts = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).split("\t", -1);
        if ((parts.length != 10 && parts.length != 11) || !"v1".equals(parts[0])) {
            throw new IOException("receipt");
        }
        final long elapsed;
        final long finished;
        final byte[] body;
        try {
            elapsed = Long.parseLong(parts[8]);
            if (parts.length == 11) {
                finished = Long.parseLong(parts[9]);
                body = "-".equals(parts[10]) ? null : Base64.getDecoder().decode(parts[10]);
            } else {
                finished = elapsed;
                body = "-".equals(parts[9]) ? null : Base64.getDecoder().decode(parts[9]);
            }
        } catch (IllegalArgumentException error) {
            throw new IOException("receipt");
        }
        Record stored = new Record(parts[1], parts[2], parts[3], parts[4], parts[5], "1".equals(parts[6]), parts[7], elapsed, body);
        stored.finished = finished;
        stored.finishedSet = terminal(stored.state);
        stored.resultKept = body != null;
        if (body != null) {
            stored.held = body;
        }
        if (!file.getFileName().toString().equals(stored.macId + "." + stored.op + ".rec")) {
            throw new IOException("receipt");
        }
        return stored;
    }

    private void write(Record stored) throws IOException {
        if (gate != null) {
            gate.beforeWrite(stored.state);
        }
        Path temp = Files.createTempFile(root, ".pending-", ".tmp");
        try {
            Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------"));
            String body = stored.body == null ? "-" : Base64.getEncoder().encodeToString(stored.body);
            String line = String.join("\t", "v1", stored.macId, stored.op, stored.method, stored.digest, stored.state, stored.applied ? "1" : "0", stored.boot, Long.toString(stored.elapsed), Long.toString(stored.finished), body);
            Files.write(temp, line.getBytes(StandardCharsets.US_ASCII));
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            Files.move(temp, path(stored.macId, stored.op), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            directories.sync(root);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static void syncAndroid(Method open, Method fsync, Method close, Path directory, int flags) throws IOException {
        Object fd;
        try {
            fd = open.invoke(null, directory.toString(), flags, 0);
        } catch (ReflectiveOperationException error) {
            throw new IOException("directory sync", error);
        }
        try {
            fsync.invoke(null, fd);
        } catch (ReflectiveOperationException error) {
            throw new IOException("directory sync", error);
        } finally {
            try {
                close.invoke(null, fd);
            } catch (ReflectiveOperationException error) {
                throw new IOException("directory sync", error);
            }
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
        long origin = terminal(stored.state) && stored.finishedSet ? stored.finished : stored.elapsed;
        return Math.max(0, clock.elapsedMillis() - origin);
    }

    private void markFinished(Record stored) {
        if (!stored.finishedSet) {
            stored.finished = clock.elapsedMillis();
            stored.finishedSet = true;
        }
    }

    private void armLocked() {
        if (dueTask != null) {
            dueTask.cancel(false);
            dueTask = null;
        }
        if (closed) {
            return;
        }
        long delay = nextDelayLocked();
        if (delay < 0) {
            return;
        }
        dueTask = sweeper.schedule(this::sweepQuiet, Math.max(1L, delay), TimeUnit.MILLISECONDS);
    }

    private long nextDelayLocked() {
        Long next = null;
        for (Record stored : index.values()) {
            Long deadline = deadline(stored);
            if (deadline == null) {
                continue;
            }
            long wait = deadline - clock.elapsedMillis();
            if (next == null || wait < next) {
                next = wait;
            }
        }
        return next == null ? -1L : next;
    }

    private Long deadline(Record stored) {
        if ("queued".equals(stored.state) && "privileged".equals(lane(stored.method))) {
            return stored.elapsed + PRIVILEGED_QUEUE_MS + 1;
        }
        if (!terminal(stored.state)) {
            return null;
        }
        long origin = stored.finishedSet ? stored.finished : stored.elapsed;
        if (stored.body != null || stored.held != null) {
            return origin + BODY_MS;
        }
        return origin + KEEP_MS;
    }

    private void noteLocked(Record stored) {
        if (listener != null) {
            notes.add(view(stored));
        }
    }

    private void flushNotes() {
        List<Receipt> batch;
        Listener current;
        synchronized (lock) {
            if (notes.isEmpty()) {
                return;
            }
            batch = new ArrayList<Receipt>(notes);
            notes.clear();
            current = listener;
        }
        if (current == null) {
            return;
        }
        for (Receipt receipt : batch) {
            current.changed(receipt);
        }
    }

    private Receipt view(Record stored) {
        byte[] shown = stored.held != null ? stored.held : stored.body;
        return receipt(stored.state, stored.applied, stored.method, stored.digest, shown, stored.resultKept, stored.macId, stored.op);
    }

    private Receipt conflict(Record existing) {
        byte[] shown = existing.held != null ? existing.held : existing.body;
        return receipt("conflict", existing.applied, existing.method, existing.digest, shown, existing.resultKept, existing.macId, existing.op);
    }

    private Receipt busy(String method, String digest, String macId, String op) {
        return receipt("busy", false, method, digest, null, false, macId, op);
    }

    private Receipt missing(String macId, String op) {
        return receipt("not_found", false, "", "", null, false, macId, op);
    }

    private Receipt receipt(String state, boolean applied, String method, String digest, byte[] body, boolean resultKept, String macId, String op) {
        return new Receipt(state, applied, method, digest, body, resultKept, epoch, macId, op);
    }

    private static boolean terminal(String state) {
        return "complete".equals(state) || "failed".equals(state) || "expired".equals(state) || "canceled".equals(state) || "unknown".equals(state);
    }

    private static String key(String macId, String op) {
        return macId + "." + op;
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
        byte[] held;
        boolean resultKept;
        long finished;
        boolean finishedSet;

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
