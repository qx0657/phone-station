package dev.phonestation.adbkeep.link;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** 二进制 WebSocket。McpHttp 解析升级头之后从这里接手，不改成 Java-WebSocket 服务端。 */
final class LocalWire implements AutoCloseable {
    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int LIMIT = LocalLink.FRAME_LIMIT;

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final boolean client;
    private final Object writeLock = new Object();

    private LocalWire(Socket socket, InputStream in, OutputStream out, boolean client) {
        this.socket = socket;
        this.in = in;
        this.out = out;
        this.client = client;
    }

    private LocalWire(Socket socket, boolean client) throws IOException {
        this(socket, socket.getInputStream(), socket.getOutputStream(), client);
    }

    /** 请求头已经由 McpHttp 读完。incoming 必须是读这些头的那条流，不能另开一条把缓冲丢掉。 */
    static LocalWire fromHeaders(Socket socket, InputStream incoming, Map<String, String> headers) throws IOException {
        String key = headers.get("sec-websocket-key");
        byte[] decoded = null;
        if (key != null) {
            try {
                decoded = Base64.getDecoder().decode(key);
            } catch (IllegalArgumentException bad) {
                decoded = null;
            }
        }
        if (!headerHas(headers, "upgrade", "websocket") || !headerHas(headers, "connection", "upgrade")
                || !"13".equals(headers.get("sec-websocket-version")) || decoded == null || decoded.length != 16) {
            writeStatus(socket, 400, "upgrade");
            throw new IOException("upgrade");
        }
        String response = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: "
                + accept(key) + "\r\n\r\n";
        socket.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        socket.setTcpNoDelay(true);
        return new LocalWire(socket, incoming, socket.getOutputStream(), false);
    }

    static LocalWire dial(String host, int port) throws IOException {
        Socket socket = new Socket();
        try {
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 5000);
            socket.setSoTimeout(5000);
            return client(socket, host, port);
        } catch (IOException | RuntimeException error) {
            socket.close();
            throw error;
        }
    }

    /** 已经连上的套接字做客户端升级。调用方负责只把回环地址交进来。 */
    static LocalWire client(Socket socket, String host, int port) throws IOException {
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(5000);
        byte[] key = new byte[16];
        new SecureRandom().nextBytes(key);
        String nonce = Base64.getEncoder().encodeToString(key);
        String request = "GET " + LocalLink.PATH + " HTTP/1.1\r\n"
                + "Host: " + host + ":" + port + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + nonce + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n\r\n";
        socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        Head head = readHead(socket.getInputStream());
        if (!head.start.startsWith("HTTP/1.1 101 ")) {
            socket.close();
            throw new IOException(statusCode(head.start));
        }
        if (!headerHas(head.headers, "upgrade", "websocket") || !headerHas(head.headers, "connection", "upgrade")
                || !accept(nonce).equals(head.headers.get("sec-websocket-accept"))) {
            socket.close();
            throw new IOException("upgrade");
        }
        return new LocalWire(socket, true);
    }

    static LocalWire accept(Socket socket) throws IOException {
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(5000);
        Head head = readHead(socket.getInputStream());
        String[] parts = head.start.split(" ", 3);
        if (parts.length != 3 || !"HTTP/1.1".equals(parts[2])) {
            writeStatus(socket, 400, "request");
            throw new IOException("request");
        }
        boolean origin = head.headers.containsKey("origin");
        try {
            LocalLink.admit(parts[0], parts[1], head.headers.get("host"), origin);
        } catch (IllegalArgumentException rejected) {
            int status = 400;
            if ("method".equals(rejected.getMessage())) {
                status = 405;
            } else if ("path".equals(rejected.getMessage())) {
                status = 404;
            } else if ("host".equals(rejected.getMessage()) || "origin".equals(rejected.getMessage())) {
                status = 403;
            }
            writeStatus(socket, status, rejected.getMessage());
            throw rejected;
        }
        String key = head.headers.get("sec-websocket-key");
        byte[] decoded = null;
        if (key != null) {
            try {
                decoded = Base64.getDecoder().decode(key);
            } catch (IllegalArgumentException bad) {
                decoded = null;
            }
        }
        if (!headerHas(head.headers, "upgrade", "websocket") || !headerHas(head.headers, "connection", "upgrade")
                || !"13".equals(head.headers.get("sec-websocket-version")) || decoded == null || decoded.length != 16) {
            writeStatus(socket, 400, "upgrade");
            throw new IOException("upgrade");
        }
        String response = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: "
                + accept(key) + "\r\n\r\n";
        socket.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));
        return new LocalWire(socket, false);
    }

    void await(int millis) throws IOException {
        socket.setSoTimeout(millis);
    }

    void relax() throws IOException {
        socket.setSoTimeout(0);
    }

    byte[] readData() throws IOException {
        while (true) {
            int first = readByte();
            int second = readByte();
            if ((first & 0x70) != 0 || (first & 0x80) == 0) {
                throw new IOException("frame");
            }
            int opcode = first & 0x0f;
            boolean masked = (second & 0x80) != 0;
            if (client == masked) {
                throw new IOException("mask");
            }
            int length = second & 0x7f;
            if (length == 127) {
                throw new IOException("length");
            }
            if (length == 126) {
                length = (readByte() << 8) | readByte();
            }
            if (length < 0 || length > LIMIT) {
                throw new IOException("length");
            }
            byte[] mask = masked ? readFully(4) : null;
            byte[] payload = readFully(length);
            if (masked) {
                for (int i = 0; i < payload.length; i++) {
                    payload[i] ^= mask[i % 4];
                }
            }
            if (opcode == 0x2) {
                return payload;
            }
            if (opcode == 0x8) {
                throw new EOFException("close");
            }
            if (opcode != 0x9 && opcode != 0xA) {
                throw new IOException("opcode");
            }
        }
    }

    void writeData(byte[] payload) throws IOException {
        if (payload.length > LIMIT) {
            throw new IOException("length");
        }
        ByteArrayOutputStream raw = new ByteArrayOutputStream(payload.length + 8);
        raw.write(0x82);
        int lengthByte = payload.length < 126 ? payload.length : 126;
        if (client) {
            lengthByte |= 0x80;
        }
        raw.write(lengthByte);
        if (payload.length >= 126) {
            raw.write((payload.length >> 8) & 0xff);
            raw.write(payload.length & 0xff);
        }
        byte[] body = payload;
        if (client) {
            byte[] mask = new byte[4];
            new SecureRandom().nextBytes(mask);
            raw.write(mask);
            body = payload.clone();
            for (int i = 0; i < body.length; i++) {
                body[i] ^= mask[i % 4];
            }
        }
        raw.write(body);
        synchronized (writeLock) {
            out.write(raw.toByteArray());
            out.flush();
        }
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // 关掉连接即可让对端读到结束。
        }
    }

    private int readByte() throws IOException {
        int value = in.read();
        if (value < 0) {
            throw new EOFException("closed");
        }
        return value;
    }

    private byte[] readFully(int length) throws IOException {
        byte[] out = new byte[length];
        int offset = 0;
        while (offset < length) {
            int n = in.read(out, offset, length - offset);
            if (n < 0) {
                throw new EOFException("closed");
            }
            offset += n;
        }
        return out;
    }

    private static void writeStatus(Socket socket, int status, String reason) throws IOException {
        String text = "HTTP/1.1 " + status + " " + reason + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
        socket.getOutputStream().write(text.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
    }

    private static String accept(String key) {
        try {
            byte[] sum = MessageDigest.getInstance("SHA-1").digest((key + GUID).getBytes(StandardCharsets.US_ASCII));
            return Base64.getEncoder().encodeToString(sum);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private static Head readHead(InputStream in) throws IOException {
        int[] remain = counter(8192);
        String start = readLine(in, remain);
        Map<String, String> headers = new LinkedHashMap<>();
        while (true) {
            String line = readLine(in, remain);
            if (line.isEmpty()) {
                return new Head(start, headers);
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                throw new IOException("header");
            }
            String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            if (headers.containsKey(name)) {
                throw new IOException("header");
            }
            headers.put(name, line.substring(colon + 1).trim());
        }
    }

    private static int[] counter(int remain) {
        return new int[] {remain};
    }

    private static String readLine(InputStream in, int[] remain) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (true) {
            if (remain[0] <= 0) {
                throw new IOException("header");
            }
            int value = in.read();
            if (value < 0) {
                throw new EOFException("closed");
            }
            remain[0]--;
            line.write(value);
            if (value == '\n') {
                break;
            }
            if (line.size() > 8192) {
                throw new IOException("header");
            }
        }
        byte[] raw = line.toByteArray();
        int end = raw.length;
        if (end > 0 && raw[end - 1] == '\n') {
            end--;
        }
        if (end > 0 && raw[end - 1] == '\r') {
            end--;
        }
        return new String(raw, 0, end, StandardCharsets.US_ASCII);
    }

    private static boolean headerHas(Map<String, String> headers, String name, String want) {
        String value = headers.get(name);
        if (value == null) {
            return false;
        }
        for (String part : value.toLowerCase(Locale.ROOT).split(",")) {
            if (part.trim().equals(want)) {
                return true;
            }
        }
        return false;
    }

    private static String statusCode(String line) {
        String[] parts = line.split(" ", 3);
        return parts.length < 2 ? "status" : parts[1];
    }

    private static final class Head {
        final String start;
        final Map<String, String> headers;

        Head(String start, Map<String, String> headers) {
            this.start = start;
            this.headers = headers;
        }
    }
}
