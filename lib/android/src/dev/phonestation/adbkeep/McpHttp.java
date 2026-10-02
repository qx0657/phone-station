package dev.phonestation.adbkeep;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
            final String version) {
        this.socket = socket;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop(token, files, host, version);
            }
        }, "station-mcp");
        thread.setDaemon(true);
        thread.start();
    }

    static McpHttp open(int port, String token, FileOps files, StationHost host, String version)
            throws IOException {
        ServerSocket server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 8);
        return new McpHttp(server, token, files, host, version);
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

    private void acceptLoop(String token, FileOps files, StationHost host, String version) {
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
            synchronized (clients) {
                clients.add(client);
            }
            try {
                requests.execute(() -> {
                    try {
                        client.setSoTimeout(20_000);
                        serve(client, token, files, host, version);
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
            Socket client, String token, FileOps files, StationHost host, String version)
            throws IOException {
        InputStream input = client.getInputStream();
        String requestLine = readLine(input);
        if (requestLine == null || requestLine.isEmpty()) {
            return;
        }
        String[] parts = requestLine.split(" ", 3);
        String method = parts.length > 0 ? parts[0] : "";
        String target = parts.length > 1 ? parts[1] : "";
        int query = target.indexOf('?');
        if (query >= 0) {
            target = target.substring(0, query);
        }
        String authorization = null;
        int contentLength = -1;
        int headerBytes = requestLine.length();
        while (true) {
            String line = readLine(input);
            if (line == null) {
                return;
            }
            headerBytes += line.length();
            if (headerBytes > 65536) {
                write(client.getOutputStream(), 400, "{\"error\":\"请求头太大\"}");
                return;
            }
            if (line.isEmpty()) {
                break;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = line.substring(0, colon).trim().toLowerCase(Locale.US);
            String value = line.substring(colon + 1).trim();
            if ("authorization".equals(name) && authorization == null) {
                authorization = value;
            } else if ("content-length".equals(name)) {
                try {
                    contentLength = Integer.parseInt(value);
                } catch (NumberFormatException error) {
                    contentLength = -1;
                }
            }
        }
        OutputStream output = client.getOutputStream();
        if (!"POST".equals(method) || !"/mcp".equals(target)) {
            write(output, 405, "{\"error\":\"只接受 POST /mcp\"}");
            return;
        }
        if (contentLength < 0) {
            write(output, 400, "{\"error\":\"需要 Content-Length\"}");
            return;
        }
        if (contentLength > BODY_LIMIT) {
            write(output, 413, "{\"error\":\"请求太大\"}");
            return;
        }
        byte[] body = readExact(input, contentLength);
        McpProtocol.Reply reply = McpProtocol.handle(
                new String(body, StandardCharsets.UTF_8), authorization, token, files, host, version);
        write(output, reply.status, reply.body);
    }

    private static String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            int value = input.read();
            if (value < 0) {
                return out.size() == 0 ? null : out.toString("UTF-8");
            }
            if (value == '\n') {
                return out.toString("UTF-8");
            }
            if (value != '\r') {
                out.write(value);
            }
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
        } else if (status == 405) {
            reason = "Method Not Allowed";
        } else if (status == 413) {
            reason = "Payload Too Large";
        } else {
            reason = "Error";
        }
        String headers = "HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + payload.length + "\r\n"
                + "Connection: close\r\n\r\n";
        output.write(headers.getBytes(StandardCharsets.US_ASCII));
        output.write(payload);
        output.flush();
    }
}
