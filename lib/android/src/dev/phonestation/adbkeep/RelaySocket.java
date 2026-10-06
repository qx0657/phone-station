package dev.phonestation.adbkeep;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLSocketFactory;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.handshake.ServerHandshake;

/** One bounded control session. The reader remains responsive while a tool executes. */
final class RelaySocket implements AutoCloseable {
    static final int LIMIT = 18 * 1024 * 1024;
    static final long FRESH_MS = 45_000L;
    private final WebSocketClient client;
    private final Runnable confirmed;
    private final Runnable disconnected;
    private final ScheduledExecutorService watchdog;
    private boolean ready;
    private boolean closed;
    private long receivedAt = System.nanoTime();
    private Json operation;
    private Json subscription;
    private String eventID = "";
    private final java.util.Set<String> dirtyTopics = new java.util.HashSet<>();
    private boolean flushing;
    private String awaitingResult;
    private Integer resultStatus;

    RelaySocket(String endpoint, String token, SSLSocketFactory tls, Runnable confirmed, Runnable disconnected) {
        URI uri = URI.create(endpoint.replaceFirst("^https://", "wss://") + "/v1/phone/control");
        if (!"wss".equals(uri.getScheme()) || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("invalid control endpoint");
        }
        this.confirmed = confirmed;
        this.disconnected = disconnected;
        client = new WebSocketClient(uri, new Draft_6455(Collections.emptyList(), LIMIT),
                Collections.singletonMap("Authorization", "Bearer " + token), 5_000) {
            @Override public void onOpen(ServerHandshake handshake) { /* Wait for protocol ready. */ }
            @Override public void onMessage(String text) { receive(text); }
            @Override public void onMessage(ByteBuffer bytes) { RelaySocket.this.close(); }
            @Override public void onClose(int code, String reason, boolean remote) { RelaySocket.this.close(); }
            @Override public void onError(Exception error) { RelaySocket.this.close(); }
        };
        client.setSocketFactory(tls);
        client.setDaemon(true);
        client.setTcpNoDelay(true);
        // The relay sends application heartbeats. Do not add a second ping timer.
        client.setConnectionLostTimeout(0);
        watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "phone-relay-watchdog"); thread.setDaemon(true); return thread;
        });
    }

    void open() throws IOException, InterruptedException {
        synchronized (this) { requireOpen(); }
        watchdog.scheduleAtFixedRate(() -> {
            boolean expired;
            synchronized (this) {
                expired = System.nanoTime() - receivedAt > TimeUnit.MILLISECONDS.toNanos(ready ? FRESH_MS : 10_000L);
            }
            if (expired) { close(); }
        }, 5, 5, TimeUnit.SECONDS);
        client.connect();
        synchronized (this) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!ready) { await(deadline); }
        }
    }

    synchronized boolean isReady() { return ready && !closed; }

    synchronized Json next() throws IOException, InterruptedException {
        while (operation == null && subscription == null) {
            requireOpen();
            wait();
        }
        requireOpen();
        Json next;
        if (subscription != null) { next = subscription; subscription = null; }
        else { next = operation; operation = null; }
        return next;
    }

    synchronized int result(String value) throws IOException, InterruptedException {
        requireOpen();
        Json frame = Json.parse(value).put("type", "result");
        awaitingResult = frame.get("operationId").string();
        resultStatus = null;
        try {
            send(frame.emit());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (resultStatus == null) { await(deadline); }
            return resultStatus;
        } finally { awaitingResult = null; resultStatus = null; }
    }

    synchronized void subscribed(String id, boolean clipboard, boolean notifications) throws IOException {
        requireOpen();
        if (!id.equals(eventID)) { dirtyTopics.clear(); }
        eventID = id;
        send(Json.obj().put("type", "subscribed").put("subscriptionId", id)
                .put("clipboard", clipboard).put("notifications", notifications).emit());
    }

    synchronized void event(String id, String topic) {
        if (closed || !id.equals(eventID) || id.isEmpty()) { return; }
        dirtyTopics.add(topic);
        if (!flushing) { flushing = true; watchdog.schedule(this::flushEvents, 50, TimeUnit.MILLISECONDS); }
    }

    private void flushEvents() {
        boolean failed = false;
        synchronized (this) {
            if (closed) { return; }
            if (client.hasBufferedData()) { watchdog.schedule(this::flushEvents, 100, TimeUnit.MILLISECONDS); return; }
            try {
                for (String topic : dirtyTopics) {
                    send(Json.obj().put("type", "event").put("subscriptionId", eventID).put("topic", topic).emit());
                }
                dirtyTopics.clear(); flushing = false;
            } catch (IOException failure) { failed = true; }
        }
        if (failed) { close(); }
    }

    private void receive(String text) {
        try {
            synchronized (this) {
                requireOpen();
                if (text.getBytes(StandardCharsets.UTF_8).length > LIMIT) { throw new IOException("oversized control message"); }
                Json frame = Json.parse(text);
                String type = frame.get("type").string();
                if (!ready && !"ready".equals(type)) { throw new IOException("missing control handshake"); }
                switch (type) {
                    case "ready":
                        if (ready || !"control-v1".equals(frame.get("protocol").string())) { throw new IOException("invalid control handshake"); }
                        ready = true;
                        send("{\"type\":\"heartbeat\"}");
                        break;
                    case "heartbeat":
                        send("{\"type\":\"heartbeat\"}");
                        break;
                    case "subscribe":
                        subscription = frame; // Coalesce renewals while an operation is running.
                        break;
                    case "operation":
                        String id = frame.get("operationId").string();
                        if (operation != null || id.isEmpty() || id.length() > 128 || frame.get("payload") == null) {
                            throw new IOException("invalid control operation");
                        }
                        operation = frame;
                        break;
                    case "result":
                        if (awaitingResult == null || !awaitingResult.equals(frame.get("operationId").string()) || resultStatus != null) {
                            throw new IOException("unexpected result confirmation");
                        }
                        int status = Math.toIntExact(frame.get("status").longValue());
                        if (status < 200 || status > 599) { throw new IOException("invalid result confirmation"); }
                        resultStatus = status;
                        break;
                    default: throw new IOException("unknown control message");
                }
                receivedAt = System.nanoTime();
                notifyAll();
            }
            confirmed.run();
        } catch (Exception invalid) { close(); }
    }

    private void send(String text) throws IOException {
        if (text.getBytes(StandardCharsets.UTF_8).length > LIMIT) { throw new IOException("oversized result"); }
        try { client.send(text); }
        catch (RuntimeException failure) { throw new IOException("control write failed", failure); }
    }

    private void await(long deadline) throws IOException, InterruptedException {
        requireOpen();
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) { throw new IOException("control response timed out"); }
        TimeUnit.NANOSECONDS.timedWait(this, remaining);
        requireOpen();
    }

    private void requireOpen() throws IOException {
        if (closed) { throw new IOException("control connection closed"); }
    }

    @Override public void close() {
        synchronized (this) {
            if (closed) { return; }
            closed = true;
            operation = null;
            notifyAll();
        }
        watchdog.shutdownNow();
        // Abort immediately, including a network handover or a half-open TLS handshake.
        try { if (client.getSocket() != null) { client.getSocket().close(); } } catch (IOException ignored) { }
        client.closeConnection(1001, "control ended");
        disconnected.run();
    }
}
