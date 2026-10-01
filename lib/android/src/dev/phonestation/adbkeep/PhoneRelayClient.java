package dev.phonestation.adbkeep;

import android.content.Context;
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

/** Long-polls the Shenzhen relay and runs requests through the existing MCP dispatcher. */
final class PhoneRelayClient implements Runnable {
    private static final String TAG = "StationRelay";
    private static final int BODY_LIMIT = 18 * 1024 * 1024;
    private static final long RESULT_WINDOW_MS = 180_000L;
    private static volatile boolean connected;

    private final Context context;
    private final String version;
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final String internalToken = newToken();
    private final Thread thread;
    private volatile HttpsURLConnection inFlight;

    static boolean connected() {
        return connected;
    }

    PhoneRelayClient(Context context, String version) {
        this.context = context.getApplicationContext();
        this.version = version;
        thread = new Thread(this, "phone-station-relay");
        thread.setDaemon(true);
    }

    void start() {
        thread.start();
    }

    void stop() {
        stopped.set(true);
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
            try {
                Json next = post("/v1/phone/poll", "{}", 35_000);
                connected = true;
                backoff = 1_000L;
                Json operation = next.get("operationId");
                if (operation == null || operation.isNull()) {
                    continue;
                }
                deliver(operation.string(), next.get("payload").emit());
            } catch (Exception error) {
                connected = false;
                if (!stopped.get()) {
                    Log.w(TAG, "relay connection failed: " + error.getClass().getSimpleName());
                    pause(backoff);
                    backoff = Math.min(backoff * 2L, 30_000L);
                }
            }
        }
        connected = false;
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
                pause(backoff);
                backoff = Math.min(backoff * 2L, 30_000L);
            } catch (IOException unavailable) {
                if (stopped.get()) {
                    return;
                }
                pause(backoff);
                backoff = Math.min(backoff * 2L, 30_000L);
            }
        }
    }

    private Json post(String path, String request, int readTimeoutMs) throws IOException {
        String endpoint = RemoteStore.endpoint(context);
        String pin = RemoteStore.pin(context);
        final String token;
        try {
            token = RemoteStore.token(context);
        } catch (Exception error) {
            throw new IOException("remote credential unavailable", error);
        }
        HttpsURLConnection connection = null;
        try {
            connection = (HttpsURLConnection) new URL(endpoint + path).openConnection();
            inFlight = connection;
            connection.setSSLSocketFactory(pinnedContext(pin).getSocketFactory());
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(readTimeoutMs);
            connection.setDoOutput(true);
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("Content-Type", "application/json");
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
            return Json.parse(new String(response, StandardCharsets.UTF_8));
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

    private static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
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
