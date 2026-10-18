package dev.phonestation.adbkeep;

import dev.phonestation.adbkeep.link.LinkReceipts;
import dev.phonestation.adbkeep.link.LocalLink;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** 把已经读完升级头的连接交给本地链路。会话随 McpHttp 关掉。 */
final class LinkPort implements McpHttp.Upgrade {
    private final LocalLink.Device self;
    private final Supplier<List<LocalLink.Device>> allow;
    private final LinkReceipts receipts;
    private final FileOps files;
    private final StationHost host;
    private final String version;
    private final Path sealDir;
    private final List<LocalLink.Session> sessions = new ArrayList<>();
    private final AtomicBoolean joining = new AtomicBoolean();
    private volatile BiConsumer<String, String> joined;
    private volatile Runnable remoteWatch;
    private volatile LinkRemote remote;

    LinkPort(LocalLink.Device self, Supplier<List<LocalLink.Device>> allow, LinkReceipts receipts, FileOps files,
            StationHost host, String version) {
        this(self, allow, receipts, files, host, version, null);
    }

    LinkPort(LocalLink.Device self, Supplier<List<LocalLink.Device>> allow, LinkReceipts receipts, FileOps files,
            StationHost host, String version, Path sealDir) {
        if (self == null || self.signer == null || allow == null || receipts == null) {
            throw new IllegalArgumentException("link");
        }
        this.self = self;
        this.allow = allow;
        this.receipts = receipts;
        this.files = files;
        this.host = host;
        this.version = version;
        this.sealDir = sealDir;
        receipts.ensureEvents(null);
        LinkLiveEvents.attach(receipts.events());
    }

    @Override
    public Boolean take(Socket socket, InputStream input, String method, String target, String version,
            Map<String, String> headers) throws IOException {
        if (!"GET".equals(method) || target == null || !(target.equals("/v2/link") || target.startsWith("/v2/link?"))) {
            return null;
        }
        if (!"HTTP/1.1".equals(version)) {
            reject(socket, 400, "version");
            return false;
        }
        try {
            LocalLink.admit(method, target, headers.get("host"), headers.containsKey("origin"));
        } catch (IllegalArgumentException rejected) {
            int status = 400;
            if ("method".equals(rejected.getMessage())) {
                status = 405;
            } else if ("path".equals(rejected.getMessage())) {
                status = 404;
            } else if ("host".equals(rejected.getMessage()) || "origin".equals(rejected.getMessage())) {
                status = 403;
            }
            reject(socket, status, rejected.getMessage());
            return false;
        }
        try {
            List<LocalLink.Device> granted = allow.get();
            LocalLink.Session session = LocalLink.acceptHttp(socket, input, headers, self,
                    granted == null ? List.of() : granted, receipts, this::call, files::shareDrop, this::relayInvite);
            synchronized (sessions) {
                sessions.add(session);
            }
            if (host != null) {
                host.noteAuthenticated();
            }
            return true;
        } catch (Exception error) {
            return false;
        }
    }

    void closePeer(byte[] id) {
        if (id == null || id.length != 16) {
            return;
        }
        List<LocalLink.Session> found = new ArrayList<>();
        synchronized (sessions) {
            for (LocalLink.Session session : sessions) {
                if (java.util.Arrays.equals(session.peer(), id)) {
                    found.add(session);
                }
            }
            sessions.removeAll(found);
        }
        for (LocalLink.Session session : found) {
            session.close();
        }
    }

    boolean offer(String name, byte[] body) {
        List<LocalLink.Session> targets = new ArrayList<>();
        synchronized (sessions) {
            for (int i = sessions.size() - 1; i >= 0; i--) {
                LocalLink.Session candidate = sessions.get(i);
                if (!candidate.ended()) {
                    targets.add(candidate);
                }
            }
        }
        LinkRemote running = remote;
        if (running != null) {
            LocalLink.Session live = running.liveSession();
            if (live != null && !live.ended()) {
                targets.add(live);
            }
        }
        if (targets.isEmpty()) {
            return false;
        }
        if (targets.size() == 1) {
            return offerOne(targets.get(0), name, body);
        }
        AtomicInteger sent = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(targets.size());
        for (LocalLink.Session session : targets) {
            Thread thread = new Thread(() -> {
                try {
                    if (offerOne(session, name, body)) {
                        sent.incrementAndGet();
                    }
                } finally {
                    done.countDown();
                }
            }, "station-share");
            thread.setDaemon(true);
            thread.start();
        }
        try {
            done.await(6, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
        return sent.get() > 0;
    }

    private static boolean offerOne(LocalLink.Session session, String name, byte[] body) {
        try {
            session.offer(name, body);
            return true;
        } catch (Exception error) {
            return false;
        }
    }

    byte[] phoneSpki() {
        return self.spki.clone();
    }

    void setJoined(BiConsumer<String, String> handler) {
        joined = handler;
    }

    void watchRemote(Runnable callback) {
        remoteWatch = callback;
    }

    boolean hasRemote() {
        return remote != null;
    }

    boolean remoteOnline() {
        LinkRemote running = remote;
        return running != null && running.connected();
    }

    LinkRemote openRemote(String endpoint, byte[] pin) {
        if (remote != null) {
            return remote;
        }
        remote = LinkRemote.open(self, endpoint, pin, allow, receipts, this::call, sealDir, remoteWatch);
        return remote;
    }

    void replaceRemote(String endpoint, byte[] pin) {
        LinkRemote running = remote;
        remote = null;
        if (running != null) {
            running.close();
        }
        remote = LinkRemote.open(self, endpoint, pin, allow, receipts, this::call, sealDir, remoteWatch);
    }

    private void relayInvite(String text) {
        if (text == null || !joining.compareAndSet(false, true)) {
            return;
        }
        Thread thread = new Thread(() -> {
            try {
                acceptInvite(text);
            } catch (Exception error) {
                joining.set(false);
            }
        }, "station-link-join");
        thread.setDaemon(true);
        thread.start();
    }

    private void acceptInvite(String text) throws Exception {
        dev.phonestation.adbkeep.link.LinkInvite invite = dev.phonestation.adbkeep.link.LinkInvite.parse(text);
        if (!"phone".equals(invite.kind)) {
            throw new IllegalArgumentException("invite");
        }
        byte[] spki = Base64.getDecoder().decode(invite.spki);
        boolean known = false;
        List<LocalLink.Device> granted = allow.get();
        if (granted != null) {
            for (LocalLink.Device device : granted) {
                if (device != null && device.spki != null && Arrays.equals(device.spki, spki)) {
                    known = true;
                    break;
                }
            }
        }
        if (!known) {
            throw new IllegalArgumentException("invite");
        }
        String host = LinkRemote.host(invite.endpoint);
        int port = LinkRemote.port(invite.endpoint);
        if (host == null || port < 1) {
            throw new IllegalArgumentException("host");
        }
        dev.phonestation.adbkeep.link.RemoteLink.join(self, host, port, unhex(invite.pin), unhex(invite.id), unhex(invite.key), "手机");
        BiConsumer<String, String> saved = joined;
        if (saved != null) {
            saved.accept(invite.endpoint, invite.pin);
        }
    }

    private static byte[] unhex(String text) {
        if (text == null || text.length() % 2 != 0) {
            throw new IllegalArgumentException("invite");
        }
        byte[] out = new byte[text.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int high = Character.digit(text.charAt(i * 2), 16);
            int low = Character.digit(text.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) {
                throw new IllegalArgumentException("invite");
            }
            out[i] = (byte) ((high << 4) | low);
        }
        return out;
    }

    private byte[] call(String method, byte[] params) {
        if (host != null) {
            host.noteAuthenticated();
        }
        if (method != null && (method.startsWith("terminal.") || method.startsWith("shell.")
                || method.startsWith("screen.") || method.startsWith("install."))) {
            try {
                Json result = method.startsWith("terminal.") ? host.linkTerminal(method, params) : host.linkWork(method, params);
                return McpProtocol.toolOk(result).emit().getBytes(StandardCharsets.UTF_8);
            } catch (FileFailure | IllegalArgumentException error) {
                String message = error.getMessage();
                return McpProtocol.toolError(message == null || message.isEmpty() ? "没有这个方法" : message)
                        .emit().getBytes(StandardCharsets.UTF_8);
            }
        }
        return LinkTools.exec(method, params, files, host, version);
    }

    void close() {
        LinkLiveEvents.detach(receipts.events());
        LinkRemote running = remote;
        remote = null;
        if (running != null) {
            running.close();
        }
        List<LocalLink.Session> open;
        synchronized (sessions) {
            open = new ArrayList<>(sessions);
            sessions.clear();
        }
        for (LocalLink.Session session : open) {
            session.close();
        }
        receipts.close();
    }

    private static void reject(Socket socket, int status, String reason) throws IOException {
        String text = "HTTP/1.1 " + status + " " + reason + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
        socket.getOutputStream().write(text.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
    }
}
