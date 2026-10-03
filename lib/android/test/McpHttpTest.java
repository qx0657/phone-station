package dev.phonestation.adbkeep;

import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

final class McpHttpTest {
    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("mcp-http-limits");
        McpHttp http = McpHttp.open(0, "token", new FileOps(home), null, "test", 500);
        try {
            expect(401, request(http.port(), "POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: 8388608\r\n\r\n"));
            expect(431, request(http.port(), "POST /mcp HTTP/1.1\r\nX-Large: " + "a".repeat(9000)));
            expect(431, request(http.port(), "a".repeat(9000)));
            expect(431, request(http.port(), "POST /mcp HTTP/1.1\r\n" + ("X: " + "b".repeat(7000) + "\r\n").repeat(10)));
            expect(400, request(http.port(), "POST /mcp HTTP/1.1\r\nContent-Length: 1\r\nContent-Length: 2\r\n\r\n"));
            expect(400, request(http.port(), "POST /mcp HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n"));
            String headers = "Host: 127.0.0.1:8765\r\nAuthorization: Bearer token\r\n";
            expect(403, request(http.port(), "POST /mcp HTTP/1.1\r\n" + headers + "Origin: https://evil.example\r\nContent-Length: 0\r\n\r\n"));
            expect(403, request(http.port(), "GET /mcp HTTP/1.1\r\nHost: evil.example\r\nAuthorization: Bearer token\r\n\r\n"));
            expect(405, request(http.port(), "GET /mcp HTTP/1.1\r\n" + headers + "Origin: http://localhost:8765\r\n\r\n"));
            expect(403, request(http.port(), "GET /mcp HTTP/1.1\r\n" + headers + "Origin: http://localhost:1234\r\n\r\n"));
            expect(400, request(http.port(), "GET /mcp HTTP/1.1\r\n" + headers + "Origin: http://localhost:8765\r\nOrigin: http://localhost:8765\r\n\r\n"));
            expect(400, request(http.port(), "GET /mcp HTTP/1.1\r\nHost : localhost\r\nAuthorization: Bearer token\r\n\r\n"));
            expect(405, request(http.port(), "GET /mcp HTTP/1.1\r\nHost:\tlocalhost\r\nAuthorization:\tBearer token\r\n\r\n"));
            try (Socket invalidUtf8 = new Socket("127.0.0.1", http.port())) {
                invalidUtf8.setSoTimeout(2000);
                invalidUtf8.getOutputStream().write(("POST /mcp HTTP/1.1\r\n" + headers + "Content-Length: 2\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                invalidUtf8.getOutputStream().write(new byte[] {(byte) 0xc3, 0x28});
                invalidUtf8.getOutputStream().flush();
                expect(400, status(invalidUtf8));
            }
            for (String bad : new String[] {"", "evil.example", "127.0.0.1.evil.example", "localhost:0", "localhost:65536", "user@localhost:8765"}) {
                if (LoopbackBoundary.allowed(bad, null)) { throw new AssertionError("bad Host: " + bad); }
            }
            if (!LoopbackBoundary.allowed("localhost:18766", "http://127.0.0.1:18766")) { throw new AssertionError("adb forward"); }
            try (Socket socket = new Socket("127.0.0.1", http.port())) {
                socket.setSoTimeout(2000);
                socket.getOutputStream().write(("GET /mcp HTTP/1.1\r\n" + headers + "\r\n").getBytes(StandardCharsets.US_ASCII));
                java.io.BufferedReader input = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                String line; boolean allow = false;
                while ((line = input.readLine()) != null && !line.isEmpty()) { if (line.equals("Allow: POST")) { allow = true; } }
                if (!allow) { throw new AssertionError("missing Allow"); }
            }
            try (Socket slow = new Socket("127.0.0.1", http.port())) {
                slow.setSoTimeout(2000);
                long start = System.nanoTime();
                for (int i = 0; i < 4; i++) {
                    slow.getOutputStream().write('P');
                    slow.getOutputStream().flush();
                    Thread.sleep(120);
                }
                expect(408, status(slow));
                if ((System.nanoTime() - start) / 1_000_000 > 1500) { throw new AssertionError("deadline extended"); }
            }
            System.out.println("McpHttpTest ok");
        } finally { http.close(); Files.delete(home); }
    }
    private static int request(int port, String request) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(2000);
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            return status(socket);
        }
    }
    private static int status(Socket socket) throws Exception {
        String line = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII)).readLine();
        if (line == null) { throw new AssertionError("missing response"); }
        return Integer.parseInt(line.split(" ")[1]);
    }
    private static void expect(int want, int got) { if (want != got) { throw new AssertionError(want + " != " + got); } }
}
