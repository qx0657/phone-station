package dev.phonestation.adbkeep;

import dev.phonestation.adbkeep.link.LinkReceipts;
import dev.phonestation.adbkeep.link.LocalLink;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** 本地链路在测试进程里完成 hello、auth、ready。不连接正在运行的手机服务。 */
final class LocalLinkTest {
    public static void main(String[] args) throws Exception {
        if (!"ws://127.0.0.1:8765/v2/link".equals(LocalLink.ADDRESS)) {
            throw new AssertionError("address");
        }
        Path root = Files.createTempDirectory("local-link");
        try {
            readyAndReplay(root);
            badSignature();
            unknownMac();
            upgradeRejected();
            disconnectEnds();
        } finally {
            delete(root);
        }
        System.out.println("local link ok");
    }

    private static void readyAndReplay(Path root) throws Exception {
        LocalLink.Device phoneKey = LocalLink.Device.generate();
        LocalLink.Device macKey = LocalLink.Device.generate();
        AtomicInteger runs = new AtomicInteger();
        try (LinkReceipts receipts = new LinkReceipts(root, clock())) {
            LocalLink.Phone phone = LocalLink.Phone.listen(phoneKey, List.of(macKey), receipts, () -> {
                runs.incrementAndGet();
                return "saved".getBytes(StandardCharsets.UTF_8);
            });
            try {
                Held held = hold(phone, macKey, phoneKey);
                try {
                    if (held.mac.engine() == null || held.mac.mux() == null || held.phone.engine() == null || held.phone.mux() == null) {
                        throw new AssertionError("engine");
                    }
                    if (!held.mac.ready() || !held.phone.ready()) {
                        throw new AssertionError("ready");
                    }
                    byte[] params = "same".getBytes(StandardCharsets.UTF_8);
                    String op = "0123456789abcdef0123456789abcdef";
                    LocalLink.Result first = held.mac.call(op, "clipboard.set", params);
                    LocalLink.Result second = held.mac.call(op, "clipboard.set", params);
                    if (runs.get() != 1 || !"complete".equals(first.state) || !"complete".equals(second.state)
                            || !"saved".equals(text(first.body)) || !"saved".equals(text(second.body))) {
                        throw new AssertionError("replay");
                    }
                } finally {
                    held.close();
                }
            } finally {
                phone.close();
            }
        }
    }

    private static void badSignature() throws Exception {
        LocalLink.Device phoneKey = LocalLink.Device.generate();
        LocalLink.Device macKey = LocalLink.Device.generate();
        LocalLink.Device other = LocalLink.Device.generate();
        LocalLink.Device liar = new LocalLink.Device(macKey.id, macKey.spki, other.signer);
        try (LocalLink.Phone phone = LocalLink.Phone.listen(phoneKey, List.of(macKey), null, null)) {
            expect(phone, liar, phoneKey, "bad_signature");
        }
    }

    private static void unknownMac() throws Exception {
        LocalLink.Device phoneKey = LocalLink.Device.generate();
        LocalLink.Device macKey = LocalLink.Device.generate();
        LocalLink.Device stranger = LocalLink.Device.generate();
        try (LocalLink.Phone phone = LocalLink.Phone.listen(phoneKey, List.of(macKey), null, null)) {
            expect(phone, stranger, phoneKey, "unknown_device");
        }
    }

    private static void upgradeRejected() throws Exception {
        LocalLink.Device phoneKey = LocalLink.Device.generate();
        LocalLink.Device macKey = LocalLink.Device.generate();
        try (LocalLink.Phone phone = LocalLink.Phone.listen(phoneKey, List.of(macKey), null, null)) {
            AtomicReference<Exception> error = new AtomicReference<>();
            Thread thread = new Thread(() -> {
                try {
                    phone.accept();
                } catch (Exception failure) {
                    error.set(failure);
                }
            });
            thread.start();
            String status = raw(phone.port(), "192.0.2.8", false);
            thread.join(5000);
            if (!"403".equals(status) || error.get() == null || !"host".equals(error.get().getMessage())) {
                throw new AssertionError("host");
            }
            error.set(null);
            thread = new Thread(() -> {
                try {
                    phone.accept();
                } catch (Exception failure) {
                    error.set(failure);
                }
            });
            thread.start();
            status = raw(phone.port(), "127.0.0.1", true);
            thread.join(5000);
            if (!"403".equals(status) || error.get() == null || !"origin".equals(error.get().getMessage())) {
                throw new AssertionError("origin");
            }
        }
    }

    private static void disconnectEnds() throws Exception {
        LocalLink.Device phoneKey = LocalLink.Device.generate();
        LocalLink.Device macKey = LocalLink.Device.generate();
        try (LocalLink.Phone phone = LocalLink.Phone.listen(phoneKey, List.of(macKey), null, null)) {
            Held held = hold(phone, macKey, phoneKey);
            held.mac.close();
            long deadline = System.nanoTime() + 2_000_000_000L;
            while (!held.phone.ended() && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            if (!held.mac.ended() || !held.phone.ended()) {
                throw new AssertionError("ended");
            }
            try {
                held.mac.call("0123456789abcdef0123456789abcdef", "clipboard.set", "x".getBytes(StandardCharsets.UTF_8));
                throw new AssertionError("call");
            } catch (IllegalStateException ended) {
                if (!"ended".equals(ended.getMessage())) {
                    throw ended;
                }
            } finally {
                held.phone.close();
            }
        }
    }

    private static void expect(LocalLink.Phone phone, LocalLink.Device mac, LocalLink.Device phoneKey, String code) throws Exception {
        AtomicReference<Exception> phoneError = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                SessionClose.close(phone.accept());
            } catch (Exception failure) {
                phoneError.set(failure);
            }
        });
        thread.start();
        Exception macError = null;
        try {
            SessionClose.close(LocalLink.dial(mac, phoneKey, "127.0.0.1", phone.port()));
        } catch (Exception failure) {
            macError = failure;
        }
        thread.join(5000);
        if (macError == null || !code.equals(macError.getMessage()) || phoneError.get() == null || !code.equals(phoneError.get().getMessage())) {
            throw new AssertionError(code);
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

    private static String raw(int port, String host, boolean origin) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 2000);
            socket.setSoTimeout(2000);
            String request = "GET /v2/link HTTP/1.1\r\nHost: " + host + "\r\n";
            if (origin) {
                request += "Origin: https://example.test\r\n";
            }
            request += "Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            byte[] buf = new byte[80];
            int n = socket.getInputStream().read(buf);
            if (n < 12) {
                throw new AssertionError("status");
            }
            return new String(buf, 0, n, StandardCharsets.US_ASCII).split(" ", 3)[1];
        }
    }

    private static LinkReceipts.Clock clock() {
        return new LinkReceipts.Clock() {
            public long elapsedMillis() {
                return 1;
            }

            public String bootId() {
                return "local-link";
            }
        };
    }

    private static String text(byte[] body) {
        return body == null ? "" : new String(body, StandardCharsets.UTF_8);
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
                    // 临时目录留在系统临时盘里也没有密钥。
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

    private static final class SessionClose {
        static void close(LocalLink.Session session) {
            if (session != null) {
                session.close();
            }
        }
    }
}
