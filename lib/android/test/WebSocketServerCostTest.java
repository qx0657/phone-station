package dev.phonestation.adbkeep;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** 只在测试进程里对比 Java-WebSocket 服务端和现有 McpHttp，不替换 McpHttp。 */
final class WebSocketServerCostTest {
    public static void main(String[] args) throws Exception {
        int processors = Runtime.getRuntime().availableProcessors();
        List<String> baseline = names();
        long beforeMcp = heap();
        Path home = Files.createTempDirectory("ws-server-cost");
        McpHttp http = McpHttp.open(0, "token", new FileOps(home), null, "test", 500);
        long mcpHeap;
        try {
            Thread.sleep(50);
            List<String> idle = added(baseline, names());
            mcpHeap = heap() - beforeMcp;
            if (idle.size() != 1 || !"station-mcp".equals(idle.get(0))) {
                throw new AssertionError("mcp idle " + idle);
            }
            try (Socket socket = new Socket("127.0.0.1", http.port())) {
                socket.setSoTimeout(2000);
                socket.getOutputStream().write(("POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: 0\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                socket.getInputStream().read();
            }
            Thread.sleep(50);
            List<String> busy = added(baseline, names());
            if (countExact(busy, "station-mcp") != 1 || countExact(busy, "station-mcp-request") != 1) {
                throw new AssertionError("mcp busy " + busy);
            }
        } finally {
            http.close();
            Files.deleteIfExists(home);
        }
        Thread.sleep(100);
        List<String> afterMcp = names();
        long beforeWs = heap();
        CountDownLatch started = new CountDownLatch(1);
        WebSocketServer server = new WebSocketServer(new InetSocketAddress("127.0.0.1", 0)) {
            @Override public void onOpen(WebSocket conn, ClientHandshake handshake) {}
            @Override public void onClose(WebSocket conn, int code, String reason, boolean remote) {}
            @Override public void onMessage(WebSocket conn, String message) {}
            @Override public void onError(WebSocket conn, Exception ex) {}
            @Override public void onStart() { started.countDown(); }
        };
        server.setDaemon(true);
        server.start();
        try {
            if (!started.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("websocket server did not start");
            }
            Thread.sleep(50);
            List<String> idle = added(afterMcp, names());
            long wsHeap = heap() - beforeWs;
            int selectors = countPrefix(idle, "WebSocketSelector-");
            int workers = countPrefix(idle, "WebSocketWorker-");
            int checkers = countExact(idle, "connectionLostChecker-1");
            if (selectors != 1 || workers != processors || checkers != 1 || idle.size() != processors + 2) {
                throw new AssertionError("ws idle selectors=" + selectors + " workers=" + workers
                        + " checkers=" + checkers + " processors=" + processors + " " + idle);
            }
            try (Socket socket = new Socket("127.0.0.1", server.getPort())) {
                socket.setSoTimeout(2000);
                socket.getOutputStream().write(("GET / HTTP/1.1\r\nHost: 127.0.0.1:" + server.getPort()
                        + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                        + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                byte[] response = socket.getInputStream().readNBytes(12);
                if (response.length < 12 || response[9] != '1' || response[10] != '0' || response[11] != '1') {
                    throw new AssertionError("upgrade " + new String(response, StandardCharsets.US_ASCII));
                }
                Thread.sleep(50);
                List<String> connected = added(afterMcp, names());
                if (connected.size() != idle.size()) {
                    throw new AssertionError("ws connected " + connected);
                }
            }
            System.out.println("WebSocketServerCostTest processors=" + processors
                    + " mcpIdleThreads=1 mcpAfterRequestThreads=2 mcpHeapBytes=" + mcpHeap
                    + " wsIdleThreads=" + idle.size() + " wsHeapBytes=" + wsHeap);
        } finally {
            server.stop(1);
        }
    }

    private static List<String> names() {
        List<String> found = new ArrayList<>();
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            found.add(thread.getName());
        }
        Collections.sort(found);
        return found;
    }

    private static List<String> added(List<String> before, List<String> after) {
        List<String> extra = new ArrayList<>(after);
        for (String name : before) {
            extra.remove(name);
        }
        return extra;
    }

    private static int countExact(List<String> names, String want) {
        int count = 0;
        for (String name : names) {
            if (want.equals(name)) {
                count++;
            }
        }
        return count;
    }

    private static int countPrefix(List<String> names, String prefix) {
        int count = 0;
        for (String name : names) {
            if (name.startsWith(prefix)) {
                count++;
            }
        }
        return count;
    }

    private static long heap() throws InterruptedException {
        for (int i = 0; i < 3; i++) {
            System.gc();
            Thread.sleep(30);
        }
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }
}
