package dev.phonestation.adbkeep;

import dev.phonestation.adbkeep.link.LinkReceipts;
import dev.phonestation.adbkeep.link.LocalLink;
import dev.phonestation.adbkeep.link.RemoteLink;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Comparator;
import dev.phonestation.adbkeep.link.LinkEvents;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 同一台 Mac 的本地会话和远程会话共用一个 LinkReceipts。
 * 一边 call，另一边按原编号 query。不连接正在运行的手机服务或公网中继。
 */
final class CrossLinkTest {
    private static final String METHOD = "clipboard.set";
    private static final String LOCAL_OP = "0123456789abcdef0123456789abcdef";
    private static final String REMOTE_OP = "fedcba9876543210fedcba9876543210";
    private static final String SLOW_OP = "00112233445566778899aabbccddeeff";

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("cross-link");
        Path relayDir = Files.createTempDirectory("cross-relay");
        Process process = null;
        try {
            LocalLink.Device phoneKey = LocalLink.Device.generate();
            LocalLink.Device macKey = LocalLink.Device.generate();
            Files.writeString(relayDir.resolve("phone.spki"), Base64.getEncoder().encodeToString(phoneKey.spki));
            Files.writeString(relayDir.resolve("mac.spki"), Base64.getEncoder().encodeToString(macKey.spki));
            process = start(relayDir);
            ByteArrayOutputStream captured = new ByteArrayOutputStream();
            Thread drain = drain(process, captured);
            String text = waitListen(relayDir);
            String[] lines = text.split("\n", -1);
            if (lines.length < 2 || !lines[0].startsWith("127.0.0.1:")) {
                throw new AssertionError("listen");
            }
            int colon = lines[0].lastIndexOf(':');
            int port = Integer.parseInt(lines[0].substring(colon + 1));
            byte[] pin = unhex(lines[1].trim());
            cross("127.0.0.1", port, pin, root, phoneKey, macKey);
            Files.writeString(relayDir.resolve("done"), "ok\n");
            if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
                drain.join(1000);
                throw new AssertionError(safe(captured.toString(StandardCharsets.UTF_8)));
            }
            drain.join(1000);
        } finally {
            if (process != null) {
                process.destroyForcibly();
            }
            delete(root);
            delete(relayDir);
        }
        System.out.println("cross link ok");
    }

    private static void cross(String host, int port, byte[] pin, Path root, LocalLink.Device phoneKey, LocalLink.Device macKey)
            throws Exception {
        AtomicInteger runs = new AtomicInteger();
        AtomicBoolean slow = new AtomicBoolean();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Manual eventsClock = new Manual();
        Callable<byte[]> work = () -> {
            runs.incrementAndGet();
            if (slow.get()) {
                started.countDown();
                if (!release.await(4, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("release");
                }
            }
            return "saved".getBytes(StandardCharsets.UTF_8);
        };
        byte[] same = "same".getBytes(StandardCharsets.UTF_8);
        byte[] other = "other".getBytes(StandardCharsets.UTF_8);
        try (LinkReceipts receipts = new LinkReceipts(root, clock())) {
            receipts.ensureEvents(eventsClock);
            LocalLink.Phone phone = LocalLink.Phone.listen(phoneKey, List.of(macKey), receipts, work);
            LocalLink.Session remotePhone = null;
            LocalLink.Session remoteMac = null;
            try {
                remotePhone = RemoteLink.connect(phoneKey, host, port, pin, "main", "test", macKey.id, receipts, work);
                remoteMac = RemoteLink.connect(macKey, host, port, pin, "main", "test", phoneKey.id, null, null);
                Held local = hold(phone, macKey, phoneKey);
                try {
                    expect(local.mac.call(LOCAL_OP, METHOD, same), "complete", "saved", 1, runs);
                    expect(remoteMac.query(LOCAL_OP), "complete", "saved", 1, runs);
                    expect(remoteMac.call(LOCAL_OP, METHOD, same), "complete", "saved", 1, runs);
                    expect(remoteMac.call(LOCAL_OP, METHOD, other), "conflict", null, 1, runs);
                } finally {
                    local.close();
                }
                expect(remoteMac.query(LOCAL_OP), "complete", "saved", 1, runs);

                Held again = hold(phone, macKey, phoneKey);
                try {
                    expect(remoteMac.call(REMOTE_OP, METHOD, same), "complete", "saved", 2, runs);
                    expect(again.mac.query(REMOTE_OP), "complete", "saved", 2, runs);
                    expect(again.mac.call(REMOTE_OP, METHOD, same), "complete", "saved", 2, runs);
                    expect(again.mac.call(REMOTE_OP, METHOD, other), "conflict", null, 2, runs);
                    remoteMac.close();
                    remotePhone.close();
                    remoteMac = null;
                    remotePhone = null;
                    expect(again.mac.query(REMOTE_OP), "complete", "saved", 2, runs);
                    running(host, port, pin, receipts, phoneKey, macKey, again.mac, work, slow, started, release, runs);
                } finally {
                    again.close();
                }
            } finally {
                if (remoteMac != null) {
                    remoteMac.close();
                }
                if (remotePhone != null) {
                    remotePhone.close();
                }
                phone.close();
            }
        }
    }

    /** 远程 call 还在 running 时，本地订阅收到 ops，不先 query。结束后断开远程仍能按原编号 query。 */
    private static void running(String host, int port, byte[] pin, LinkReceipts receipts, LocalLink.Device phoneKey,
            LocalLink.Device macKey, LocalLink.Session local, Callable<byte[]> work, AtomicBoolean slow,
            CountDownLatch started, CountDownLatch release, AtomicInteger runs) throws Exception {
        receipts.events().deliver(LinkEvents.COALESCE_MS);
        LocalLink.Session remotePhone = RemoteLink.connect(phoneKey, host, port, pin, "main", "test", macKey.id, receipts, work);
        LocalLink.Session remoteMac = null;
        try {
            remoteMac = RemoteLink.connect(macKey, host, port, pin, "main", "test", phoneKey.id, null, null);
            LocalLink.Subscribed subscribed = local.subscribe(List.of("ops"), Map.of());
            if (subscribed.topics.size() != 1 || !"ops".equals(subscribed.topics.get(0)) || !subscribed.rejected.isEmpty()) {
                throw new AssertionError("subscribed");
            }
            slow.set(true);
            AtomicReference<LocalLink.Result> result = new AtomicReference<>();
            AtomicReference<Exception> error = new AtomicReference<>();
            LocalLink.Session callerSession = remoteMac;
            Thread caller = new Thread(() -> {
                try {
                    result.set(callerSession.call(SLOW_OP, METHOD, "same".getBytes(StandardCharsets.UTF_8)));
                } catch (Exception failure) {
                    error.set(failure);
                }
            });
            caller.start();
            if (!started.await(3, TimeUnit.SECONDS)) {
                throw new AssertionError("started");
            }
            if (receipts.events().deliver(LinkEvents.COALESCE_MS) < 1) {
                throw new AssertionError("deliver");
            }
            LocalLink.Event event = local.nextEvent(2000);
            if (event == null || !"event".equals(event.type) || !"ops".equals(event.topic)
                    || !SLOW_OP.equals(event.op) || !"running".equals(event.state)) {
                throw new AssertionError("ops");
            }
            release.countDown();
            caller.join(5000);
            if (error.get() != null) {
                throw error.get();
            }
            expect(result.get(), "complete", "saved", 3, runs);
        } finally {
            if (remoteMac != null) {
                remoteMac.close();
            }
            remotePhone.close();
        }
        expect(local.query(SLOW_OP), "complete", "saved", 3, runs);
    }

    private static void expect(LocalLink.Result result, String state, String body, int runs, AtomicInteger actual) {
        String text = result.body == null ? null : new String(result.body, StandardCharsets.UTF_8);
        if (!state.equals(result.state) || (body != null && !body.equals(text)) || actual.get() != runs) {
            throw new AssertionError(state);
        }
    }

    private static Held hold(LocalLink.Phone phone, LocalLink.Device macKey, LocalLink.Device phoneKey) throws Exception {
        AtomicReference<LocalLink.Session> remote = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                remote.set(phone.accept());
            } catch (Exception failure) {
                error.set(failure);
            }
        });
        thread.start();
        LocalLink.Session mac = LocalLink.dial(macKey, phoneKey, "127.0.0.1", phone.port());
        thread.join(5000);
        if (error.get() != null) {
            mac.close();
            throw error.get();
        }
        if (remote.get() == null) {
            mac.close();
            throw new AssertionError("accept");
        }
        return new Held(mac, remote.get());
    }

    private static Process start(Path dir) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(
                "go", "test", "-race", "-count=1", "-timeout", "180s", "-run", "^TestRelay6ForCross$", "./internal/v6");
        builder.directory(relayModule().toFile());
        builder.environment().put("PHONE_LINK_CROSS_DIR", dir.toAbsolutePath().toString());
        builder.redirectErrorStream(true);
        return builder.start();
    }

    private static Thread drain(Process process, ByteArrayOutputStream captured) {
        Thread thread = new Thread(() -> {
            try (InputStream in = process.getInputStream()) {
                byte[] buf = new byte[1024];
                while (captured.size() < 65536) {
                    int n = in.read(buf);
                    if (n < 0) {
                        return;
                    }
                    captured.write(buf, 0, n);
                }
                while (in.read(buf) >= 0) {
                    // 多出来的测试输出丢掉，避免管道堵住。
                }
            } catch (IOException ignored) {
                // 进程结束时读到关闭即可。
            }
        });
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static String waitListen(Path dir) throws Exception {
        Path listen = dir.resolve("listen");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(listen)) {
                String text = Files.readString(listen);
                if (text.endsWith("\n") && text.indexOf('\n') != text.lastIndexOf('\n')) {
                    return text;
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("listen");
    }

    private static Path relayModule() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null) {
            if (Files.isRegularFile(dir.resolve("server/relay/go.mod"))) {
                return dir.resolve("server/relay");
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("relay");
    }

    private static String safe(String text) {
        StringBuilder out = new StringBuilder("relay\n");
        for (String line : text.split("\n")) {
            if (out.length() > 1500) {
                break;
            }
            out.append(line.replaceAll("[0-9a-fA-F]{8,}", "...")).append('\n');
        }
        return out.toString();
    }

    private static byte[] unhex(String text) {
        if (text.length() != 64 || !text.equals(text.toLowerCase(java.util.Locale.ROOT))) {
            throw new IllegalArgumentException("pin");
        }
        byte[] out = new byte[32];
        for (int i = 0; i < out.length; i++) {
            int high = Character.digit(text.charAt(i * 2), 16);
            int low = Character.digit(text.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) {
                throw new IllegalArgumentException("pin");
            }
            out[i] = (byte) ((high << 4) | low);
        }
        return out;
    }

    private static final class Manual implements LinkEvents.Clock {
        public long elapsedMillis() {
            return 0;
        }
    }

    private static LinkReceipts.Clock clock() {
        return new LinkReceipts.Clock() {
            public long elapsedMillis() {
                return 1;
            }

            public String bootId() {
                return "cross-link";
            }
        };
    }

    private static void delete(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 临时目录里只有公钥摘要和监听地址。
                }
            });
        }
    }

    private static final class Held {
        final LocalLink.Session mac;
        final LocalLink.Session phone;

        Held(LocalLink.Session mac, LocalLink.Session phone) {
            this.mac = mac;
            this.phone = phone;
        }

        void close() {
            mac.close();
            phone.close();
        }
    }
}
