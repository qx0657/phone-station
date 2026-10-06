package dev.phonestation.adbkeep;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.SystemClock;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.HttpsURLConnection;

/** Maintains the control socket, with a negotiated legacy long-poll fallback. */
final class PhoneRelayClient implements Runnable {
    private static final String TAG = "StationRelay";
    private static final int BODY_LIMIT = 18 * 1024 * 1024;
    private static final long RESULT_WINDOW_MS = 180_000L;
    private static volatile PhoneRelayClient activeClient;
    private final RelayConnection state = new RelayConnection();

    private final Context context;
    private final String version;
    private final String endpoint;
    private final String pin;
    private final String encryptedToken;
    private final String permissionRevision;
    private final java.util.Set<String> terminalIds = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final String internalToken = newToken();
    private final Thread thread;
    private final RelayRetry retry = new RelayRetry();
    private ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback networkCallback;
    private Network defaultNetwork;
    private int networkKind = -1;
    private volatile HttpsURLConnection inFlight;
    private volatile RelaySocket control;
    private volatile RemoteEvents events;

    static void event(String topic) {
        PhoneRelayClient client = activeClient;
        RemoteEvents events = client == null ? null : client.events;
        if (events != null) { events.changed(topic); }
    }
    private long legacyUntil;

    static boolean connected() {
        PhoneRelayClient client = activeClient;
        return client != null && !client.stopped.get() && client.state.connected(SystemClock.elapsedRealtime());
    }

    static boolean checking() {
        PhoneRelayClient client = activeClient;
        return client != null && !client.stopped.get() && client.state.checking(SystemClock.elapsedRealtime());
    }

    static String connectionLabel(Context context) {
        if (!StationFeatures.master(context)) { return "已暂停"; }
        if (!RemoteStore.configured(context)) { return "未配置"; }
        if (!RemoteStore.enabled(context)) { return "已关闭"; }
        PhoneRelayClient client = activeClient;
        return client == null || client.stopped.get() ? "连接中" : client.state.label(SystemClock.elapsedRealtime());
    }

    static long expiresIn() {
        PhoneRelayClient client = activeClient;
        return client == null || client.stopped.get() ? -1L : client.state.expiresIn(SystemClock.elapsedRealtime());
    }

    PhoneRelayClient(Context context, String version) {
        this.context = context.getApplicationContext();
        this.version = version;
        endpoint = RemoteStore.endpoint(context);
        pin = RemoteStore.pin(context);
        encryptedToken = RemoteStore.encryptedToken(context);
        permissionRevision = RemoteStore.permissionRevision(context);
        thread = new Thread(this, "phone-station-relay");
        thread.setDaemon(true);
    }

    boolean matchesProfile() {
        return endpoint.equals(RemoteStore.endpoint(context))
                && pin.equals(RemoteStore.pin(context))
                && encryptedToken.equals(RemoteStore.encryptedToken(context))
                && permissionRevision.equals(RemoteStore.permissionRevision(context));
    }

    static void revokeActive() {
        PhoneRelayClient client = activeClient;
        if (client != null) { client.stop(); }
    }

    boolean stopped() { return stopped.get(); }

    private void authorize(String tool) {
        StationFeatures.require(context, tool);
        if (stopped.get() || !RemoteStore.enabled(context) || !matchesProfile()) {
            throw new FileFailure("远程连接或权限已撤销，尚未执行");
        }
        RemoteStore.policy(context).require(tool);
    }

    private void trackTerminal(String id) {
        authorize("station_terminal_open");
        if (terminalIds.size() >= OperationJobs.RECORD_LIMIT && !terminalIds.contains(id)) {
            throw new FileFailure("本次远程连接的终端编号已满，尚未执行");
        }
        terminalIds.add(id);
    }

    void start() {
        activeClient = this;
        watchNetwork();
        thread.start();
    }

    synchronized void stop() {
        if (stopped.getAndSet(true)) { return; }
        ShizukuScreen.close();
        ShizukuTerminal.closeSessions(context, terminalIds);
        retry.changed();
        RelaySocket socket = control;
        if (socket != null) { socket.close(); }
        if (networkCallback != null) {
            connectivity.unregisterNetworkCallback(networkCallback);
            networkCallback = null;
        }
        setConnected(false);
        HttpsURLConnection connection = inFlight;
        if (connection != null) {
            connection.disconnect();
        }
        thread.interrupt();
    }

    @Override
    public void run() {
        long backoff = 1_000L;
        while (!stopped.get()) {
            long revision = retry.revision();
            long attemptStarted = SystemClock.elapsedRealtime();
            try {
                if (SystemClock.elapsedRealtime() >= legacyUntil && supportsControl()) {
                    runControl(revision);
                    continue;
                }
                // A short negotiated idle reply also bounds handover when the
                // old cellular network stays alive and disconnect cannot wake a read.
                Json next = post("/v1/phone/poll", "{\"waitMs\":2000}", 5_000);
                synchronized (retry) {
                    if (revision == retry.revision()) { setConnected(true); }
                }
                backoff = 1_000L;
                Json operation = next.get("operationId");
                if (operation == null || operation.isNull()) {
                    continue;
                }
                if (stopped.get()) { break; }
                deliver(operation.string(), next.get("payload").emit(), null);
            } catch (Exception error) {
                setWaiting(error instanceof RelayHttpFailure && (((RelayHttpFailure) error).status == 401
                        || ((RelayHttpFailure) error).status == 403)
                        ? "认证失败 · 请检查令牌" : "连接失败 · 重试中");
                if (!stopped.get()) {
                    Log.w(TAG, "relay connection failed: " + error.getClass().getSimpleName());
                    if (SystemClock.elapsedRealtime() - attemptStarted >= 20_000L) { backoff = 1_000L; }
                    retry.pause(backoff, revision);
                    backoff = revision == retry.revision() ? Math.min(backoff * 2L, 30_000L) : 1_000L;
                }
            }
        }
        setConnected(false);
    }

    private boolean supportsControl() throws IOException {
        try {
            Json info = post("/v1/phone/capabilities", "{}", 5_000);
            if (!"control-v1".equals(info.get("controlProtocol").string())) { throw new IOException("unsupported control protocol"); }
            return true;
        } catch (RelayHttpFailure failure) {
            if (failure.status != 404) { throw failure; }
            legacyUntil = SystemClock.elapsedRealtime() + 300_000L;
            return false;
        }
    }

    private void runControl(long revision) throws Exception {
        RelaySocket socket = new RelaySocket(endpoint, RemoteStore.decryptToken(encryptedToken),
                RelayTls.context(pin).getSocketFactory(), () -> {
                    synchronized (retry) {
                        if (!stopped.get() && revision == retry.revision()) { setConnected(true); }
                    }
                }, () -> {
                    synchronized (retry) {
                        if (!stopped.get() && revision == retry.revision()) { setWaiting("连接失败 · 重试中"); }
                    }
                });
        control = socket;
        try {
            if (stopped.get() || revision != retry.revision()) { return; }
            socket.open();
            events = new RemoteEvents(context, socket, this::authorize, () -> activeClient == this);
            while (!stopped.get() && revision == retry.revision()) {
                Json next = socket.next();
                if (stopped.get() || revision != retry.revision()) { break; }
                if ("subscribe".equals(next.get("type").string())) { events.update(next); }
                else { deliver(next.get("operationId").string(), next.get("payload").emit(), socket); }
            }
        } finally {
            if (events != null) { events.close(); events = null; }
            socket.close();
            if (control == socket) { control = null; }
        }
    }

    private void watchNetwork() {
        connectivity = context.getSystemService(ConnectivityManager.class);
        if (connectivity == null) { return; }
        defaultNetwork = connectivity.getActiveNetwork();
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                if (!network.equals(defaultNetwork)) {
                    defaultNetwork = network;
                    networkKind = -1;
                    networkChanged();
                }
            }

            @Override
            public void onLost(Network network) {
                // An old Wi-Fi loss must not invalidate an already available new default.
                if (network.equals(defaultNetwork)) {
                    defaultNetwork = null;
                    networkKind = -1;
                    networkChanged();
                }
            }

            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                if (!network.equals(defaultNetwork)) { return; }
                // A VPN can keep the same Network while its underlying Wi-Fi becomes cellular.
                int kind = (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? 1 : 0)
                        | (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? 2 : 0)
                        | (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ? 4 : 0);
                boolean changed = networkKind != -1 && networkKind != kind;
                networkKind = kind;
                if (changed) { networkChanged(); }
            }
        };
        connectivity.registerDefaultNetworkCallback(networkCallback);
    }

    private void networkChanged() {
        if (stopped.get()) { return; }
        synchronized (retry) {
            retry.changed();
            setWaiting("连接中");
        }
        StationNotifications.connectionChanged();
        RelaySocket socket = control;
        if (socket != null) { socket.close(); }
        HttpsURLConnection connection = inFlight;
        if (connection != null) {
            // Never block ConnectivityManager's callback thread on socket cleanup.
            Thread cancel = new Thread(connection::disconnect, "phone-relay-handover");
            cancel.setDaemon(true);
            cancel.start();
        }
    }

    private void setConnected(boolean value) {
        if (!value || stopped.get()) { setWaiting("连接失败 · 重试中"); return; }
        long now = SystemClock.elapsedRealtime();
        String previous = state.label(now);
        RelaySocket socket = control;
        state.confirmed(now, socket != null && socket.isReady() ? RelaySocket.FRESH_MS : RelayConnection.FRESH_MS);
        if (previous.equals(state.label(now))) { return; }
        // 旧客户端退出时不能清掉新客户端的状态。通知始终重读当前客户端。
        StationNotifications.connectionChanged();
    }

    private void setWaiting(String phase) {
        String previous = state.label(SystemClock.elapsedRealtime());
        state.waiting(phase);
        if (!previous.equals(phase)) { StationNotifications.connectionChanged(); }
    }

    private void deliver(String operationID, String payload, RelaySocket socket) throws Exception {
        FileOps files = FileOps.device();
        files.setMediaNotice(new MediaScan(context));
        McpProtocol.Reply reply = McpProtocol.handle(
                payload, "Bearer " + internalToken, internalToken,
                files, new StationBridge(context, files, this::authorize, this::trackTerminal), version);
        Json response = Json.obj().put("status", reply.status);
        if (!reply.body.isEmpty()) {
            response.put("body", Json.parse(reply.body));
        }
        String result = Json.obj()
                .put("operationId", operationID)
                .put("response", response)
                .emit();

        if (socket != null) {
            try {
                int status = socket.result(result);
                if (status == 200 || status == 404 || status == 409 || status == 410) { return; }
            } catch (IOException unavailable) {
                // Only the original result may be retried; the dispatcher is never re-entered.
            }
            socket.close();
        }
        long deadline = System.currentTimeMillis() + RESULT_WINDOW_MS;
        long backoff = 1_000L;
        boolean attempted = false;
        while ((!stopped.get() || !attempted) && System.currentTimeMillis() < deadline) {
            attempted = true;
            long revision = retry.revision();
            try {
                post("/v1/phone/result", result, 15_000);
                return;
            } catch (RelayHttpFailure rejected) {
                if (rejected.status == 404 || rejected.status == 409 || rejected.status == 410) {
                    Log.w(TAG, "relay no longer has operation " + operationID);
                    return;
                }
                if (stopped.get()) {
                    return;
                }
                setConnected(false);
                retry.pause(backoff, revision);
                backoff = revision == retry.revision() ? Math.min(backoff * 2L, 30_000L) : 1_000L;
            } catch (IOException unavailable) {
                if (stopped.get()) {
                    return;
                }
                setConnected(false);
                retry.pause(backoff, revision);
                backoff = revision == retry.revision() ? Math.min(backoff * 2L, 30_000L) : 1_000L;
            }
        }
    }

    private Json post(String path, String request, int readTimeoutMs) throws IOException {
        long revision = retry.revision();
        final String token;
        try {
            token = RemoteStore.decryptToken(encryptedToken);
        } catch (Exception error) {
            throw new IOException("remote credential unavailable", error);
        }
        HttpsURLConnection connection = null;
        try {
            connection = (HttpsURLConnection) new URL(endpoint + path).openConnection();
            inFlight = connection;
            if (revision != retry.revision()) { throw new IOException("network changed before request"); }
            connection.setSSLSocketFactory(RelayTls.context(pin).getSocketFactory());
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(5_000);
            connection.setReadTimeout(readTimeoutMs);
            connection.setDoOutput(true);
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Connection", "close");
            byte[] bytes = request.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(bytes);
            }
            int status = connection.getResponseCode();
            InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            byte[] response = readBounded(input, BODY_LIMIT);
            if (status < 200 || status >= 300) {
                throw new RelayHttpFailure(status);
            }
            Json reply = Json.parse(new String(response, StandardCharsets.UTF_8));
            synchronized (retry) {
                if (revision == retry.revision() && !path.equals("/v1/phone/capabilities")) { setConnected(true); }
            }
            return reply;
        } finally {
            inFlight = null;
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static byte[] readBounded(InputStream input, int limit) throws IOException {
        if (input == null) {
            return new byte[0];
        }
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int n;
            while ((n = in.read(buffer)) >= 0) {
                total += n;
                if (total > limit) {
                    throw new IOException("relay response exceeds size limit");
                }
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static String newToken() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        return FileOps.toHex(bytes);
    }

    private static final class RelayHttpFailure extends IOException {
        final int status;

        RelayHttpFailure(int status) {
            this.status = status;
        }
    }
}
