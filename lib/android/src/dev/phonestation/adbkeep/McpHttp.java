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

/** 只听 127.0.0.1。一次连接处理一个 POST /mcp。 */
final class McpHttp {
    static final int HEADER_LIMIT = 65536;
    static final int LINE_LIMIT = 8192;
    static final int READ_TIMEOUT_MS = 20000;
    static final int BODY_LIMIT = 8 * 1024 * 1024;

    private final ServerSocket socket;
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
            final String version, final int timeoutMs) {
        this.socket = socket;
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
        return open(port, token, files, host, version, READ_TIMEOUT_MS);
    }

    static McpHttp open(int port, String token, FileOps files, StationHost host, String version, int timeoutMs)
            throws IOException {
        ServerSocket server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 8);
        return new McpHttp(server, token, files, host, version, timeoutMs);
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
                    try {
                        try {
                            serve(client, token, files, host, version, deadline);
                        } catch (HttpFailure invalid) {
                            write(client.getOutputStream(), invalid.status, "{\"error\":\"请求头无效或过大\"}");
                        } catch (SocketTimeoutException slow) {
                            write(client.getOutputStream(), 408, "{\"error\":\"请求读取超时\"}");
                        }
                    } catch (IOException ignored) {
                        // 对端提前断开时这一次请求作废。
                    } finally {
                        release(client);
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

    private static void serve(
            Socket client, String token, FileOps files, StationHost host, String version, long deadline)
            throws IOException {
        InputStream input = new BufferedInputStream(new DeadlineInput(client, deadline), 4096);
        String requestLine = readLine(input, LINE_LIMIT);
        if (requestLine == null || requestLine.isEmpty()) {
            return;
        }
        String[] parts = requestLine.split(" ", 3);
        String method = parts.length > 0 ? parts[0] : "";
        String target = parts.length > 1 ? parts[1] : "";
        if (parts.length != 3 || (!"HTTP/1.1".equals(parts[2]) && !"HTTP/1.0".equals(parts[2]))) { throw new HttpFailure(400); }
        int query = target.indexOf('?');
        if (query >= 0) {
            target = target.substring(0, query);
        }
        String hostHeader = null;
        String origin = null;
        String authorization = null;
        int contentLength = -1;
        int headerBytes = requestLine.length() + 2;
        while (true) {
            String line = readLine(input, Math.min(LINE_LIMIT, HEADER_LIMIT - headerBytes));
            if (line == null) {
                return;
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
            }
        }
        OutputStream output = client.getOutputStream();
        if (!LoopbackBoundary.allowed(hostHeader, origin)) {
            write(output, 403, "{\"error\":\"Host 或 Origin 不允许\"}"); return;
        }
        if (!"/mcp".equals(target)) { write(output, 404, "{\"error\":\"没有这个入口\"}"); return; }
        if (!McpProtocol.authorized(authorization, token)) {
            write(output, 401, "{\"error\":\"需要 Authorization: Bearer\"}");
            return;
        }
        if (!"POST".equals(method)) { write(output, 405, "{\"error\":\"只接受 POST /mcp\"}"); return; }
        if (contentLength < 0) {
            write(output, 400, "{\"error\":\"需要 Content-Length\"}");
            return;
        }
        if (contentLength > BODY_LIMIT) {
            write(output, 413, "{\"error\":\"请求太大\"}");
            return;
        }
        byte[] body = readExact(input, contentLength);
        String text;
        try { text = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(body)).toString(); }
        catch (java.nio.charset.CharacterCodingException invalid) { write(output, 400, "{\"error\":\"请求必须使用 UTF-8\"}"); return; }
        McpProtocol.Reply reply = McpProtocol.handle(text, authorization, token, files, host, version);
        write(output, reply.status, reply.body);
    }

    private static final class HttpFailure extends IOException {
        final int status;
        HttpFailure(int status) { this.status = status; }
    }

    /** 绝对期限包括队列等待；持续滴入字节也不能延长期限。 */
    private static final class DeadlineInput extends FilterInputStream {
        final Socket client;
        final long deadline;
        DeadlineInput(Socket client, long deadline) throws IOException {
            super(client.getInputStream());
            this.client = client;
            this.deadline = deadline;
        }
        private void remaining() throws IOException {
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
