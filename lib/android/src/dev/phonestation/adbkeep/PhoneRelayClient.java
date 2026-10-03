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
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/** Long-polls the configured relay and runs requests through the existing MCP dispatcher. */
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
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final String internalToken = newToken();
    private final Thread thread;
    private final RelayRetry retry = new RelayRetry();
    private ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback networkCallback;
    private Network defaultNetwork;
    private int networkKind = -1;
    private volatile HttpsURLConnection inFlight;

    static boolean connected() {
        PhoneRelayClient client = activeClient;
        return client != null && !client.stopped.get() && client.state.connected(SystemClock.elapsedRealtime());
    }

    static boolean checking() {
        PhoneRelayClient client = activeClient;
        return client != null && !client.stopped.get() && client.state.checking(SystemClock.elapsedRealtime());
    }

    static String connectionLabel(Context context) {
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
        thread = new Thread(this, "phone-station-relay");
        thread.setDaemon(true);
    }

    boolean matchesProfile() {
        return endpoint.equals(RemoteStore.endpoint(context))
                && pin.equals(RemoteStore.pin(context))
                && encryptedToken.equals(RemoteStore.encryptedToken(context));
    }

    void start() {
        activeClient = this;
        watchNetwork();
        thread.start();
    }

    void stop() {
        stopped.set(true);
        retry.changed();
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
            try {
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
                deliver(operation.string(), next.get("payload").emit());
            } catch (Exception error) {
                setWaiting(error instanceof RelayHttpFailure && (((RelayHttpFailure) error).status == 401
                        || ((RelayHttpFailure) error).status == 403)
                        ? "认证失败 · 请检查令牌" : "连接失败 · 重试中");
                if (!stopped.get()) {
                    Log.w(TAG, "relay connection failed: " + error.getClass().getSimpleName());
                    retry.pause(backoff, revision);
                    backoff = revision == retry.revision() ? Math.min(backoff * 2L, 30_000L) : 1_000L;
                }
            }
        }
        setConnected(false);
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
        state.confirmed(now);
        if (previous.equals(state.label(now))) { return; }
        // 旧客户端退出时不能清掉新客户端的状态。通知始终重读当前客户端。
        StationNotifications.connectionChanged();
    }

    private void setWaiting(String phase) {
        String previous = state.label(SystemClock.elapsedRealtime());
        state.waiting(phase);
        if (!previous.equals(phase)) { StationNotifications.connectionChanged(); }
    }

    private void deliver(String operationID, String payload) throws Exception {
        FileOps files = FileOps.device();
        files.setMediaNotice(new MediaScan(context));
        McpProtocol.Reply reply = McpProtocol.handle(
                payload, "Bearer " + internalToken, internalToken,
                files, new StationBridge(context, files), version);
        Json response = Json.obj().put("status", reply.status);
        if (!reply.body.isEmpty()) {
            response.put("body", Json.parse(reply.body));
        }
        String result = Json.obj()
                .put("operationId", operationID)
                .put("response", response)
                .emit();

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
            connection.setSSLSocketFactory(pinnedContext(pin).getSocketFactory());
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
                if (revision == retry.revision()) { setConnected(true); }
            }
            return reply;
        } finally {
            inFlight = null;
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static SSLContext pinnedContext(final String expectedPin) throws IOException {
        try {
            X509TrustManager manager = new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType)
                        throws CertificateException {
                    throw new CertificateException("client certificate is not expected");
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType)
                        throws CertificateException {
                    if (chain == null || chain.length == 0) {
                        throw new CertificateException("relay certificate is missing");
                    }
                    try {
                        byte[] digest = MessageDigest.getInstance("SHA-256")
                                .digest(chain[0].getPublicKey().getEncoded());
                        StringBuilder hex = new StringBuilder(digest.length * 2);
                        for (byte item : digest) {
                            hex.append(String.format(Locale.US, "%02x", item & 0xff));
                        }
                        if (!MessageDigest.isEqual(
                                hex.toString().getBytes(StandardCharsets.US_ASCII),
                                expectedPin.getBytes(StandardCharsets.US_ASCII))) {
                            throw new CertificateException("relay certificate pin did not match");
                        }
                    } catch (CertificateException error) {
                        throw error;
                    } catch (Exception error) {
                        throw new CertificateException("cannot verify relay certificate", error);
                    }
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            };
            SSLContext ssl = SSLContext.getInstance("TLS");
            ssl.init(null, new TrustManager[] {manager}, new SecureRandom());
            return ssl;
        } catch (Exception error) {
            throw new IOException("cannot configure relay TLS", error);
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
