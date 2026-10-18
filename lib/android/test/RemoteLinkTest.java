package dev.phonestation.adbkeep;

import dev.phonestation.adbkeep.link.LinkInvite;
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
import java.util.concurrent.TimeUnit;

/** 中继 6 测试服务器和 Java 链路库在临时端口上完成 hello、auth、ready。不连接手机或公网。 */
final class RemoteLinkTest {
    public static void main(String[] args) throws Exception {
        if (!"192.0.2.8".equals(LinkRemote.host("https://192.0.2.8:24443/station"))
                || LinkRemote.port("https://192.0.2.8:24443/station") != 24443
                || LinkRemote.host("https://relay.invalid/station") != null
                || LinkRemote.host("https://0.0.0.0:443") != null) {
            throw new AssertionError("endpoint");
        }
        LocalLink.Device sample = LocalLink.Device.generate();
        byte[] pin = new byte[32];
        RemoteLink.literalIPv4("192.0.2.8", 443);
        try {
            RemoteLink.literalIPv4("relay.invalid", 443);
            throw new AssertionError("name");
        } catch (IllegalArgumentException error) {
            if (!"host".equals(error.getMessage())) {
                throw new AssertionError("name");
            }
        }
        try {
            RemoteLink.literalIPv4("0.0.0.0", 1);
            throw new AssertionError("unspecified");
        } catch (IllegalArgumentException error) {
            if (!"host".equals(error.getMessage())) {
                throw new AssertionError("unspecified");
            }
        }
        try {
            RemoteLink.dial(sample, "192.0.2.8", 443, pin, "main", "test");
            throw new AssertionError("host");
        } catch (IllegalArgumentException error) {
            if (!"host".equals(error.getMessage())) {
                throw new AssertionError("host");
            }
        }
        try {
            RemoteLink.dial(sample, "127.0.0.1", 9, pin, "other", "test");
            throw new AssertionError("purpose");
        } catch (IllegalArgumentException error) {
            if (!"purpose".equals(error.getMessage())) {
                throw new AssertionError("purpose");
            }
        }
        Path dir = Files.createTempDirectory("remote-link");
        Process process = null;
        try {
            LocalLink.Device self = LocalLink.Device.generate();
            LocalLink.Device peer = LocalLink.Device.generate();
            LocalLink.Device revoked = LocalLink.Device.generate();
            LocalLink.Device joining = LocalLink.Device.generate();
            Files.writeString(dir.resolve("spki"), Base64.getEncoder().encodeToString(self.spki));
            Files.writeString(dir.resolve("peer.spki"), Base64.getEncoder().encodeToString(peer.spki));
            Files.writeString(dir.resolve("revoked.spki"), Base64.getEncoder().encodeToString(revoked.spki));
            Files.writeString(dir.resolve("join.spki"), Base64.getEncoder().encodeToString(joining.spki));
            process = start(dir);
            ByteArrayOutputStream captured = new ByteArrayOutputStream();
            Thread drain = drain(process, captured);
            String text = waitListen(dir, process, captured, drain);
            String[] lines = text.split("\n", -1);
            if (lines.length < 2 || !lines[0].startsWith("127.0.0.1:")) {
                throw new AssertionError("listen");
            }
            int colon = lines[0].lastIndexOf(':');
            int port = Integer.parseInt(lines[0].substring(colon + 1));
            byte[] serverPin = unhex(lines[1].trim());
            LinkInvite invite = LinkInvite.parse(waitInvite(dir, process, captured, drain));
            String joinHost = LinkRemote.host(invite.endpoint);
            int joinPort = LinkRemote.port(invite.endpoint);
            if (joinHost == null || joinPort < 1) {
                throw new AssertionError("invite");
            }
            RemoteLink.join(joining, joinHost, joinPort, unhex(invite.pin), hexBytes(invite.id, 16), hexBytes(invite.key, 16), "手机");
            RemoteLink.Session main = RemoteLink.dial(self, "127.0.0.1", port, serverPin, "main", "test");
            try {
                RemoteLink.Session media = RemoteLink.dial(self, "127.0.0.1", port, serverPin, "media", "test");
                try {
                    if (!main.ready() || !media.ready()) {
                        throw new AssertionError("ready");
                    }
                } finally {
                    media.close();
                }
            } finally {
                main.close();
            }
            LocalLink.Device other = LocalLink.Device.generate();
            expect("127.0.0.1", port, serverPin, new LocalLink.Device(self.id, self.spki, other.signer), "main", "bad_signature");
            expect("127.0.0.1", port, serverPin, LocalLink.Device.generate(), "main", "unknown_device");
            expect("127.0.0.1", port, serverPin, new LocalLink.Device(revoked.id, revoked.spki, other.signer), "main", "bad_signature");
            expect("127.0.0.1", port, serverPin, revoked, "main", "revoked");
            callPinned(self, peer, port, serverPin, dir.resolve("receipts"));
            callSealed(self, peer, port, serverPin, dir.resolve("seal-receipts"));
            Files.writeString(dir.resolve("done"), "ok\n");
            if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
                drain.join(1000);
                throw new AssertionError(safe(captured.toString(StandardCharsets.UTF_8)));
            }
            drain.join(1000);
        } finally {
            if (process != null) {
                process.destroyForcibly();
            }
            delete(dir);
        }
        System.out.println("remote link ok");
    }

    private static void callPinned(LocalLink.Device mac, LocalLink.Device phone, int port, byte[] pin, Path receiptsDir)
            throws Exception {
        byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
        LinkReceipts receipts = new LinkReceipts(receiptsDir);
        LinkRemote remote = LinkRemote.open(phone, "127.0.0.1", port, pin, mac.id, receipts, (method, params) -> {
            if (!"clipboard.set".equals(method) || !java.util.Arrays.equals(body, params)) {
                throw new IllegalArgumentException("call");
            }
            return body;
        });
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!remote.connected() && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            if (!remote.connected()) {
                throw new AssertionError("remote");
            }
            LocalLink.Session macSession = RemoteLink.connectPinned(mac, "127.0.0.1", port, pin, "main", "test", phone.id, null, (java.util.concurrent.Callable<byte[]>) null);
            try {
                LocalLink.Result result = macSession.call("0123456789abcdef0123456789abcdef", "clipboard.set", body);
                if (!"complete".equals(result.state) || !java.util.Arrays.equals(body, result.body)) {
                    throw new AssertionError("call");
                }
            } finally {
                macSession.close();
            }
        } finally {
            remote.close();
            receipts.close();
        }
    }

    private static void callSealed(LocalLink.Device mac, LocalLink.Device phone, int port, byte[] pin, Path receiptsDir)
            throws Exception {
        byte[] body = "seal-body-9f3a".getBytes(StandardCharsets.UTF_8);
        LinkReceipts receipts = new LinkReceipts(receiptsDir);
        LinkRemote remote = LinkRemote.open(phone, "127.0.0.1", port, pin, mac, true, receipts, (method, params) -> {
            if (!"clipboard.set".equals(method) || !java.util.Arrays.equals(body, params)) {
                throw new IllegalArgumentException("call");
            }
            return body;
        });
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while (!remote.connected() && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            if (!remote.connected()) {
                throw new AssertionError("remote");
            }
            LocalLink.Session macSession = RemoteLink.connectPinnedSealed(mac, "127.0.0.1", port, pin, "main", "test", phone.id, phone.spki);
            try {
                LocalLink.Result result = macSession.call("abcdef0123456789abcdef0123456789", "clipboard.set", body);
                if (!"complete".equals(result.state) || !java.util.Arrays.equals(body, result.body)) {
                    throw new AssertionError("sealed");
                }
            } finally {
                macSession.close();
            }
        } finally {
            remote.close();
            receipts.close();
        }
    }

    private static void expect(String host, int port, byte[] pin, LocalLink.Device device, String purpose, String code)
            throws Exception {
        try {
            RemoteLink.Session session = RemoteLink.dial(device, host, port, pin, purpose, "test");
            session.close();
        } catch (IllegalArgumentException error) {
            if (code.equals(error.getMessage())) {
                return;
            }
        }
        throw new AssertionError(code);
    }

    private static Process start(Path dir) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(
                "go", "test", "-race", "-count=1", "-timeout", "180s", "-run", "^TestRelay6ForJava$", "./internal/v6");
        builder.directory(relayModule().toFile());
        builder.environment().put("PHONE_LINK_REMOTE_DIR", dir.toAbsolutePath().toString());
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

    private static String waitInvite(Path dir, Process process, ByteArrayOutputStream captured, Thread drain) throws Exception {
        Path invite = dir.resolve("invite");
        Path err = dir.resolve("join.err");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40);
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(err)) {
                throw new AssertionError("join");
            }
            if (Files.isRegularFile(invite)) {
                String text = Files.readString(invite);
                if (text.startsWith("ps-invite-v1\n") && text.endsWith("\n")) {
                    return text;
                }
            }
            if (!process.isAlive()) {
                drain.join(1000);
                throw new AssertionError(safe(captured.toString(StandardCharsets.UTF_8)));
            }
            Thread.sleep(20);
        }
        throw new AssertionError("invite");
    }

    private static byte[] hexBytes(String text, int size) {
        if (text == null || text.length() != size * 2 || !text.equals(text.toLowerCase(java.util.Locale.ROOT))) {
            throw new IllegalArgumentException("hex");
        }
        byte[] out = new byte[size];
        for (int i = 0; i < size; i++) {
            int high = Character.digit(text.charAt(i * 2), 16);
            int low = Character.digit(text.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) {
                throw new IllegalArgumentException("hex");
            }
            out[i] = (byte) ((high << 4) | low);
        }
        return out;
    }

    private static String waitListen(Path dir, Process process, ByteArrayOutputStream captured, Thread drain) throws Exception {
        Path listen = dir.resolve("listen");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(listen)) {
                String text = Files.readString(listen);
                if (text.endsWith("\n") && text.indexOf('\n') != text.lastIndexOf('\n')) {
                    return text;
                }
            }
            if (!process.isAlive()) {
                drain.join(1000);
                throw new AssertionError(safe(captured.toString(StandardCharsets.UTF_8)));
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
}
