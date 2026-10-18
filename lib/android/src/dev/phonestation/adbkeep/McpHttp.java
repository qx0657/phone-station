package dev.phonestation.adbkeep;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.BufferedInputStream;
import java.io.FilterInputStream;
import java.net.SocketTimeoutException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** 只听 127.0.0.1。工具走 /v2/link，不再提供 /mcp。不换成 Java-WebSocket 服务端。 */
final class McpHttp {
    /** null 表示不是链路升级。true 表示套接字已经交给会话。false 表示拒绝已经写完，调用方关掉套接字。 */
    interface Upgrade {
        Boolean take(Socket socket, InputStream input, String method, String target, String version,
                java.util.Map<String, String> headers) throws IOException;
    }
    static final int HEADER_LIMIT = 65536;
    static final int LINE_LIMIT = 8192;
    static final int READ_TIMEOUT_MS = 20000;
    static final int BODY_LIMIT = 8 * 1024 * 1024;

    private final ServerSocket socket;
    private final Upgrade upgrade;
    private final Thread thread;
    private final Set<Socket> clients = new HashSet<>();
    private final ThreadPoolExecutor requests = new ThreadPoolExecutor(
            4, 4, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<Runnable>(8), job -> {
                Thread worker = new Thread(job, "station-mcp-request");
                worker.setDaemon(true);
                return worker;
            });
    private volatile boolean running = true;

    private McpHttp(
            ServerSocket socket,
            final String token,
            final FileOps files,
            final StationHost host,
            final String version, final int timeoutMs, Upgrade upgrade) {
        this.socket = socket;
        this.upgrade = upgrade;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop(token, files, host, version, timeoutMs);
            }
        }, "station-mcp");
        thread.setDaemon(true);
        thread.start();
    }

    static McpHttp open(int port, String token, FileOps files, StationHost host, String version)
            throws IOException {
        return open(port, token, files, host, version, READ_TIMEOUT_MS, null);
    }

    static McpHttp open(int port, String token, FileOps files, StationHost host, String version, int timeoutMs)
            throws IOException {
        return open(port, token, files, host, version, timeoutMs, null);
    }

    static McpHttp open(int port, String token, FileOps files, StationHost host, String version, Upgrade upgrade)
            throws IOException {
        return open(port, token, files, host, version, READ_TIMEOUT_MS, upgrade);
    }

    static McpHttp open(int port, String token, FileOps files, StationHost host, String version, int timeoutMs,
            Upgrade upgrade) throws IOException {
        ServerSocket server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 8);
        return new McpHttp(server, token, files, host, version, timeoutMs, upgrade);
    }

    int port() {
        return socket.getLocalPort();
    }

    void close() {
        running = false;
        try {
            socket.close();
        } catch (IOException ignored) {
            // 关掉监听即可把 accept 解出来。
        }
        requests.shutdownNow();
        synchronized (clients) {
            for (Socket client : clients) {
                try {
                    client.close();
                } catch (IOException ignored) {}
            }
            clients.clear();
        }
        try {
            thread.join(1000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void acceptLoop(String token, FileOps files, StationHost host, String version, int timeoutMs) {
        while (running) {
            Socket client;
            try {
                client = socket.accept();
            } catch (IOException error) {
                if (running) {
                    continue;
                }
                return;
            }
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            synchronized (clients) {
                clients.add(client);
            }
            try {
                requests.execute(() -> {
                    boolean handed = false;
                    try {
                        try {
                            handed = serve(client, token, files, host, version, deadline, upgrade);
                        } catch (HttpFailure invalid) {
                            write(client.getOutputStream(), invalid.status, "{\"error\":\"请求头无效或过大\"}");
                        } catch (SocketTimeoutException slow) {
                            write(client.getOutputStream(), 408, "{\"error\":\"请求读取超时\"}");
                        }
                    } catch (IOException ignored) {
                        // 对端提前断开时这一次请求作废。
                    } finally {
                        if (!handed) {
                            release(client);
                        }
                    }
                });
            } catch (RejectedExecutionException busy) {
                release(client);
            }
        }
    }

    private void release(Socket client) {
        synchronized (clients) {
            clients.remove(client);
        }
        try {
            client.close();
        } catch (IOException ignored) {}
    }

    private static boolean serve(
            Socket client, String token, FileOps files, StationHost host, String version, long deadline, Upgrade upgrade)
            throws IOException {
        DeadlineInput raw = new DeadlineInput(client, deadline);
        String requestLine = readLine(raw, LINE_LIMIT);
        if (requestLine == null || requestLine.isEmpty()) {
            return false;
        }
        String[] parts = requestLine.split(" ", 3);
        String method = parts.length > 0 ? parts[0] : "";
        String target = parts.length > 1 ? parts[1] : "";
        if (parts.length != 3 || (!"HTTP/1.1".equals(parts[2]) && !"HTTP/1.0".equals(parts[2]))) { throw new HttpFailure(400); }
        String httpVersion = parts[2];
        boolean link = upgrade != null && "GET".equals(method) && ("/v2/link".equals(target) || target.startsWith("/v2/link?"));
        InputStream input = link ? raw : new BufferedInputStream(raw, 4096);
        int query = target.indexOf('?');
        String path = query >= 0 ? target.substring(0, query) : target;
        String hostHeader = null;
        String origin = null;
        String authorization = null;
        int contentLength = -1;
        java.util.Map<String, String> websocket = new java.util.HashMap<>();
        int headerBytes = requestLine.length() + 2;
        while (true) {
            String line = readLine(input, Math.min(LINE_LIMIT, HEADER_LIMIT - headerBytes));
            if (line == null) {
                return false;
            }
            headerBytes += line.length() + 2;
            if (headerBytes > HEADER_LIMIT) { throw new HttpFailure(431); }
            if (line.isEmpty()) {
                break;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) { throw new HttpFailure(400); }
            String name = line.substring(0, colon);
            if (!name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) { throw new HttpFailure(400); }
            name = name.toLowerCase(Locale.US);
            String value = line.substring(colon + 1).trim();
            if ("host".equals(name)) {
                if (hostHeader != null) { throw new HttpFailure(400); }
                hostHeader = value;
            } else if ("origin".equals(name)) {
                if (origin != null) { throw new HttpFailure(400); }
                origin = value;
            } else if ("authorization".equals(name)) {
                if (authorization != null) { throw new HttpFailure(400); }
                authorization = value;
            } else if ("content-length".equals(name)) {
                if (contentLength != -1 || !value.matches("[0-9]+")) { throw new HttpFailure(400); }
                try { contentLength = Integer.parseInt(value); }
                catch (NumberFormatException error) { throw new HttpFailure(413); }
            } else if ("transfer-encoding".equals(name)) {
                throw new HttpFailure(400);
            } else if ("upgrade".equals(name) || "connection".equals(name) || "sec-websocket-key".equals(name)
                    || "sec-websocket-version".equals(name)) {
                if (websocket.containsKey(name)) { throw new HttpFailure(400); }
                websocket.put(name, value);
            }
        }
        if (link) {
            raw.release();
            java.util.Map<String, String> headers = new java.util.HashMap<>(websocket);
            if (hostHeader != null) { headers.put("host", hostHeader); }
            if (origin != null) { headers.put("origin", origin); }
            Boolean taken = upgrade.take(client, input, method, target, httpVersion, headers);
            if (taken != null) {
                return taken;
            }
        }
        target = path;
        OutputStream output = client.getOutputStream();
        if (!LoopbackBoundary.allowed(hostHeader, origin)) {
            write(output, 403, "{\"error\":\"Host 或 Origin 不允许\"}"); return false;
        }
        if ("/v2/pair".equals(target)) {
            if (!"POST".equals(method)) { write(output, 405, "{\"error\":\"pair\"}"); return false; }
            if (contentLength < 1) { write(output, 400, "{\"error\":\"pair\"}"); return false; }
            if (contentLength > 2048) { write(output, 413, "{\"error\":\"pair\"}"); return false; }
            byte[] pairBody = readExact(input, contentLength);
            try {
                write(output, 200, LinkPairing.offer(pairBody));
            } catch (IllegalArgumentException | IllegalStateException error) {
                write(output, error instanceof IllegalStateException ? 404 : 400, "{\"error\":\"pair\"}");
            }
            return false;
        }
        write(output, 404, "{\"error\":\"没有这个入口\"}");
        return false;
    }

    private static final class HttpFailure extends IOException {
        final int status;
        HttpFailure(int status) { this.status = status; }
    }

    /** 绝对期限包括队列等待；持续滴入字节也不能延长期限。 */
    private static final class DeadlineInput extends FilterInputStream {
        final Socket client;
        final long deadline;
        private boolean released;
        DeadlineInput(Socket client, long deadline) throws IOException {
            super(client.getInputStream());
            this.client = client;
            this.deadline = deadline;
        }
        void release() throws IOException {
            released = true;
            client.setSoTimeout(0);
        }
        private void remaining() throws IOException {
            if (released) { return; }
            long nanos = deadline - System.nanoTime();
            if (nanos <= 0) { throw new SocketTimeoutException(); }
            client.setSoTimeout((int) Math.max(1, TimeUnit.NANOSECONDS.toMillis(nanos)));
        }
        @Override public int read() throws IOException { remaining(); return in.read(); }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            remaining();
            return in.read(b, off, len);
        }
    }

    private static String readLine(InputStream input, int limit) throws IOException {
        if (limit < 2) { throw new HttpFailure(431); }
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(256, limit));
        while (true) {
            int value = input.read();
            if (value < 0) {
                if (out.size() == 0) { return null; }
                throw new HttpFailure(400);
            }
            if (value == '\r') {
                if (input.read() != '\n') { throw new HttpFailure(400); }
                return out.toString("US-ASCII");
            }
            if ((value < 32 && value != '\t') || value > 126) { throw new HttpFailure(400); }
            if (out.size() >= limit - 2) { throw new HttpFailure(431); }
            out.write(value);
        }
    }

    private static byte[] readExact(InputStream input, int length) throws IOException {
        byte[] data = new byte[length];
        int got = 0;
        while (got < length) {
            int n = input.read(data, got, length - got);
            if (n < 0) {
                throw new IOException("请求不完整");
            }
            got += n;
        }
        return data;
    }

    private static void write(OutputStream output, int status, String body) throws IOException {
        byte[] payload = body == null || body.isEmpty()
                ? new byte[0]
                : body.getBytes(StandardCharsets.UTF_8);
        String reason;
        if (status == 200) {
            reason = "OK";
        } else if (status == 202) {
            reason = "Accepted";
        } else if (status == 400) {
            reason = "Bad Request";
        } else if (status == 401) {
            reason = "Unauthorized";
        } else if (status == 403) {
            reason = "Forbidden";
        } else if (status == 404) {
            reason = "Not Found";
        } else if (status == 405) {
            reason = "Method Not Allowed";
        } else if (status == 408) {
            reason = "Request Timeout";
        } else if (status == 431) {
            reason = "Request Header Fields Too Large";
        } else if (status == 413) {
            reason = "Payload Too Large";
        } else {
            reason = "Error";
        }
        String headers = "HTTP/1.1 " + status + " " + reason + "\r\n"
                + (status == 405 ? "Allow: POST\r\n" : "")
                + "Cache-Control: no-store\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + payload.length + "\r\n"
                + "Connection: close\r\n\r\n";
        output.write(headers.getBytes(StandardCharsets.US_ASCII));
        output.write(payload);
        output.flush();
    }
}
