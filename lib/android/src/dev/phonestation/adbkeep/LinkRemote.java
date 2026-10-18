package dev.phonestation.adbkeep;

import dev.phonestation.adbkeep.link.LinkReceipts;
import dev.phonestation.adbkeep.link.LocalLink;
import dev.phonestation.adbkeep.link.RemoteLink;

import java.net.URI;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

/**
 * 手机连中继 6 的主链路。只对字面 IPv4 拨号。授权列表不是恰好一台 Mac 时不拨。
 * 失败只结束这一次会话，本地 MCP 继续。
 */
final class LinkRemote implements AutoCloseable {
    private final LocalLink.Device self;
    private final String host;
    private final int port;
    private final byte[] pin;
    private final Supplier<List<LocalLink.Device>> allow;
    private final LinkReceipts receipts;
    private final LocalLink.Call call;
    private final Path sealDir;
    private final LocalLink.Device sealPeer;
    private final Runnable onSession;
    private final Thread thread;
    private volatile boolean stopped;
    private volatile LocalLink.Session session;
    private boolean announced;

    private LinkRemote(LocalLink.Device self, String host, int port, byte[] pin, Supplier<List<LocalLink.Device>> allow,
            LinkReceipts receipts, LocalLink.Call call, Path sealDir, LocalLink.Device sealPeer, Runnable onSession) {
        this.self = self;
        this.host = host;
        this.port = port;
        this.pin = pin;
        this.allow = allow;
        this.receipts = receipts;
        this.call = call;
        this.sealDir = sealDir;
        this.sealPeer = sealPeer;
        this.onSession = onSession;
        thread = new Thread(this::loop, "station-link-remote");
        thread.setDaemon(true);
    }

    /** 地址不是字面 IPv4 时返回 null，不打开套接字。 */
    static String host(String endpoint) {
        Endpoint parsed = endpoint(endpoint);
        return parsed == null ? null : parsed.host;
    }

    static int port(String endpoint) {
        Endpoint parsed = endpoint(endpoint);
        return parsed == null ? -1 : parsed.port;
    }

    static LinkRemote open(LocalLink.Device self, String host, int port, byte[] pin, byte[] peer, LinkReceipts receipts,
            LocalLink.Call call) {
        if (peer == null || peer.length != 16) {
            throw new IllegalArgumentException("peer");
        }
        return open(self, host, port, pin, () -> List.of(new LocalLink.Device(peer, new byte[] {1}, null)), receipts, call, null, null, null);
    }

    static LinkRemote open(LocalLink.Device self, String host, int port, byte[] pin, LocalLink.Device peer, boolean seal,
            LinkReceipts receipts, LocalLink.Call call) {
        if (peer == null || peer.id == null || peer.id.length != 16 || peer.spki == null) {
            throw new IllegalArgumentException("peer");
        }
        return open(self, host, port, pin, () -> List.of(peer), receipts, call, null, seal ? peer : null, null);
    }

    static LinkRemote open(LocalLink.Device self, String endpoint, byte[] pin, Supplier<List<LocalLink.Device>> allow,
            LinkReceipts receipts, LocalLink.Call call) {
        Endpoint parsed = endpoint(endpoint);
        if (parsed == null || pin == null || pin.length != 32 || self == null || allow == null || receipts == null || call == null) {
            return null;
        }
        return open(self, parsed.host, parsed.port, pin, allow, receipts, call, null, null, null);
    }

    static LinkRemote open(LocalLink.Device self, String endpoint, byte[] pin, Supplier<List<LocalLink.Device>> allow,
            LinkReceipts receipts, LocalLink.Call call, Path sealDir) {
        return open(self, endpoint, pin, allow, receipts, call, sealDir, null);
    }

    /** onSession 在会话变为在线或离线时调用。界面刷新失败不会拆掉这条链路。 */
    static LinkRemote open(LocalLink.Device self, String endpoint, byte[] pin, Supplier<List<LocalLink.Device>> allow,
            LinkReceipts receipts, LocalLink.Call call, Path sealDir, Runnable onSession) {
        Endpoint parsed = endpoint(endpoint);
        if (parsed == null || pin == null || pin.length != 32 || self == null || allow == null || receipts == null || call == null) {
            return null;
        }
        return open(self, parsed.host, parsed.port, pin, allow, receipts, call, sealDir, null, onSession);
    }

    private static LinkRemote open(LocalLink.Device self, String host, int port, byte[] pin,
            Supplier<List<LocalLink.Device>> allow, LinkReceipts receipts, LocalLink.Call call, Path sealDir,
            LocalLink.Device sealPeer, Runnable onSession) {
        LinkRemote remote = new LinkRemote(self, host, port, pin, allow, receipts, call, sealDir, sealPeer, onSession);
        remote.thread.start();
        return remote;
    }

    boolean connected() {
        LocalLink.Session current = session;
        return current != null && current.ready() && !current.ended();
    }

    LocalLink.Session liveSession() {
        LocalLink.Session current = session;
        if (current == null || !current.ready() || current.ended()) {
            return null;
        }
        return current;
    }

    @Override
    public void close() {
        stopped = true;
        LocalLink.Session current = session;
        if (current != null) {
            current.close();
        }
        thread.interrupt();
    }

    private void loop() {
        while (!stopped) {
            LocalLink.Device granted = oneDevice();
            if (granted == null) {
                if (!pause()) {
                    return;
                }
                continue;
            }
            int seal = sealPeer != null ? 1 : LinkSealStore.mode(sealDir, granted.id);
            if (seal < 0 || (seal == 1 && (granted.spki == null || granted.spki.length < 2 || (sealPeer != null && !Arrays.equals(granted.id, sealPeer.id))))) {
                if (!pause()) {
                    return;
                }
                continue;
            }
            LocalLink.Session opened = null;
            try {
                if (seal == 1) {
                    byte[] peerSpki = sealPeer != null ? sealPeer.spki : granted.spki;
                    opened = RemoteLink.connectPinnedSealed(self, host, port, pin, "main", "phone", granted.id, peerSpki, receipts, call);
                } else {
                    opened = RemoteLink.connectPinned(self, host, port, pin, "main", "phone", granted.id, receipts, call);
                }
                session = opened;
                noteSession(true);
                while (!stopped && !opened.ended()) {
                    if (!pause()) {
                        return;
                    }
                }
            } catch (Exception error) {
                if (stopped) {
                    return;
                }
                if (!pause()) {
                    return;
                }
            } finally {
                session = null;
                noteSession(false);
                if (opened != null) {
                    opened.close();
                }
            }
        }
    }

    private LocalLink.Device oneDevice() {
        List<LocalLink.Device> granted;
        try {
            granted = allow.get();
        } catch (RuntimeException error) {
            return null;
        }
        if (granted == null || granted.size() != 1 || granted.get(0) == null || granted.get(0).id == null || granted.get(0).id.length != 16) {
            return null;
        }
        return granted.get(0);
    }

    private void noteSession(boolean online) {
        if (announced == online) {
            return;
        }
        announced = online;
        if (onSession == null) {
            return;
        }
        try {
            onSession.run();
        } catch (RuntimeException error) {
            // 界面刷新失败不拆会话。
        }
    }

    private boolean pause() {
        try {
            Thread.sleep(200);
            return !stopped;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static Endpoint endpoint(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            URI uri = new URI(value.contains("://") ? value : "https://" + value);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null) {
                return null;
            }
            int port = uri.getPort();
            if (port < 0) {
                port = 443;
            }
            RemoteLink.literalIPv4(uri.getHost(), port);
            return new Endpoint(uri.getHost(), port);
        } catch (Exception error) {
            return null;
        }
    }

    private static final class Endpoint {
        private final String host;
        private final int port;

        private Endpoint(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }
}
