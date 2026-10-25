package dev.phonestation.adbkeep;

import dev.phonestation.adbkeep.link.LinkReceipts;
import dev.phonestation.adbkeep.link.LocalLink;

import java.lang.reflect.Proxy;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** 8765 上的 /v2/link 提供工具。/mcp 不再执行。终端创建的回执在 LinkReceipts。 */
final class LinkHttpTest {
    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("link-http");
        LocalLink.Device phone = LocalLink.Device.generate();
        LocalLink.Device mac = LocalLink.Device.generate();
        LocalLink.Device other = LocalLink.Device.generate();
        LinkReceipts receipts = new LinkReceipts(home.resolve("receipts"));
        AtomicInteger opens = new AtomicInteger();
        AtomicInteger presence = new AtomicInteger();
        StationHost host = (StationHost) Proxy.newProxyInstance(StationHost.class.getClassLoader(), new Class<?>[] {StationHost.class},
                (proxy, method, values) -> {
                    if ("noteAuthenticated".equals(method.getName())) {
                        presence.incrementAndGet();
                        return null;
                    }
                    if ("linkTerminal".equals(method.getName())) {
                        opens.incrementAndGet();
                        return Json.obj().put("state", "running");
                    }
                    if ("linkWork".equals(method.getName())) {
                        opens.incrementAndGet();
                        return Json.obj().put("state", "ran");
                    }
                    if ("terminalOpen".equals(method.getName()) || "shellStart".equals(method.getName())
                            || "screenOpen".equals(method.getName()) || "captureStart".equals(method.getName())) {
                        throw new AssertionError("operation job");
                    }
                    if ("authorize".equals(method.getName())) {
                        return null;
                    }
                    if (method.getReturnType() == void.class) {
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        LinkPort port = new LinkPort(phone, () -> List.of(new LocalLink.Device(mac.id, mac.spki, null)), receipts,
                new FileOps(home.resolve("files")), host, "test");
        McpHttp http = McpHttp.open(0, "token", new FileOps(home.resolve("files")), host, "test", 5000, port);
        try {
            String op = "0123456789abcdef0123456789abcdef";
            byte[] params = "{\"sessionId\":\"0123456789abcdef0123456789abcdef\",\"columns\":80,\"rows\":24}".getBytes(StandardCharsets.UTF_8);
            try (LocalLink.Session session = LocalLink.dial(mac, phone, "127.0.0.1", http.port())) {
                LocalLink.Result created = session.call(op, "terminal.open", params);
                if (!"complete".equals(created.state) || created.body == null || !new String(created.body, StandardCharsets.UTF_8).contains("running")) {
                    throw new AssertionError("create " + created.state);
                }
                if (presence.get() < 1) {
                    throw new AssertionError("presence");
                }
                LocalLink.Result again = session.call(op, "terminal.open", params);
                if (!"complete".equals(again.state) || opens.get() != 1) {
                    throw new AssertionError("replay " + again.state + " " + opens.get());
                }
                byte[] changed = "{\"sessionId\":\"0123456789abcdef0123456789abcdef\",\"columns\":100,\"rows\":24}".getBytes(StandardCharsets.UTF_8);
                LocalLink.Result conflict = session.call(op, "terminal.open", changed);
                if (!"conflict".equals(conflict.state) || opens.get() != 1) {
                    throw new AssertionError("conflict " + conflict.state);
                }
                LocalLink.Result shell = session.call("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "shell.start",
                        "{\"command\":\"echo hi\"}".getBytes(StandardCharsets.UTF_8));
                LocalLink.Result screen = session.call("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "screen.open",
                        "{\"sessionId\":\"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\"}".getBytes(StandardCharsets.UTF_8));
                LocalLink.Result install = session.call("cccccccccccccccccccccccccccccccc", "install.run",
                        "{\"path\":\"/storage/emulated/0/Download/app.apk\"}".getBytes(StandardCharsets.UTF_8));
                LocalLink.Result shot = session.call("eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee", "screen.capture",
                        "{\"requestId\":\"eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee\"}".getBytes(StandardCharsets.UTF_8));
                if (!"complete".equals(shell.state) || !"complete".equals(screen.state) || !"complete".equals(install.state)
                        || !"complete".equals(shot.state) || opens.get() != 5) {
                    throw new AssertionError("jobs " + shell.state + " " + screen.state + " " + install.state + " " + shot.state + " " + opens.get());
                }
                for (String one : new String[] {op, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"}) {
                    int found = 0;
                    try (java.nio.file.DirectoryStream<Path> files = Files.newDirectoryStream(home.resolve("receipts"), "*.rec")) {
                        for (Path file : files) {
                            if (file.getFileName().toString().contains(one)) {
                                found++;
                            }
                        }
                    }
                    if (found != 1) {
                        throw new AssertionError("receipt " + one + " " + found);
                    }
                }
                if (Files.isDirectory(home.resolve("operation-jobs"))) {
                    throw new AssertionError("operation job");
                }
                byte[] note = "hello phone".getBytes(StandardCharsets.UTF_8);
                session.share("note.txt", note);
                byte[] saved = Files.readAllBytes(home.resolve("files").resolve("Download").resolve("note.txt"));
                if (!java.util.Arrays.equals(saved, note)) {
                    throw new AssertionError("share bytes");
                }
                LocalLink.Result stat = session.call("dddddddddddddddddddddddddddddddd", "file.stat",
                        "{\"path\":\"Download/note.txt\"}".getBytes(StandardCharsets.UTF_8));
                String statText = stat.body == null ? "" : new String(stat.body, StandardCharsets.UTF_8);
                if (!"complete".equals(stat.state) || statText.contains("unknown_tool") || !statText.contains("note.txt")) {
                    throw new AssertionError("file.stat " + stat.state + " " + statText);
                }
                Path firstFile = home.resolve("offer-a.bin");
                Path secondFile = home.resolve("offer-b.bin");
                session.setShareWriter((name, body) -> Files.write(firstFile, body));
                try (LocalLink.Session second = LocalLink.dial(mac, phone, "127.0.0.1", http.port())) {
                    second.setShareWriter((name, body) -> Files.write(secondFile, body));
                    byte[] outbound = "both".getBytes(StandardCharsets.UTF_8);
                    if (!port.offer("fan.bin", outbound)) {
                        throw new AssertionError("fan");
                    }
                    if (!java.util.Arrays.equals(Files.readAllBytes(firstFile), outbound)
                            || !java.util.Arrays.equals(Files.readAllBytes(secondFile), outbound)) {
                        throw new AssertionError("fan bytes");
                    }
                }
                offerBack(phone, mac);
                try {
                    session.share("../note.txt", note);
                    throw new AssertionError("share escape");
                } catch (IllegalArgumentException expected) {
                    if (!"share".equals(expected.getMessage())) {
                        throw expected;
                    }
                }
                if (!InstallPath.command("/storage/emulated/0/Download/app.apk").endsWith("/storage/emulated/0/Download/app.apk")) {
                    throw new AssertionError("install command");
                }
                try {
                    InstallPath.command("/storage/emulated/0/../app.apk");
                    throw new AssertionError("escaped");
                } catch (IllegalArgumentException expected) {
                    if (!"install".equals(expected.getMessage())) { throw expected; }
                }
                if (!"clipboard.get".equals(LinkTools.method("station_clipboard_get"))
                        || !"terminal.open".equals(LinkTools.method("station_terminal_open"))
                        || !"install.run".equals(LinkTools.method("station_install_run"))
                        || !"file.stat".equals(LinkTools.method("station_file_stat"))
                        || !"notify.call".equals(LinkTools.method("station_notify"))
                        || !"torch.call".equals(LinkTools.method("station_torch"))) {
                    throw new AssertionError("method");
                }
                if (!"photo.png".equals(SharePaths.shareLeaf("photo.png", "1"))
                        || !"note.txt".equals(SharePaths.shareLeaf("报告.pdf", "note.txt"))
                        || !"shared.bin".equals(SharePaths.shareLeaf("报告.pdf", "报告"))
                        || !"shared.bin".equals(SharePaths.shareLeaf("..", ".."))
                        || !"b".equals(SharePaths.shareLeaf("a/b", "ignored"))) {
                    throw new AssertionError("share leaf");
                }
                int tools = 0;
                for (String tool : McpProtocol.toolNames()) {
                    if (LinkTools.method(tool) == null) {
                        throw new AssertionError(tool);
                    }
                    tools++;
                }
                if (tools < 18) {
                    throw new AssertionError("tools " + tools);
                }
                int marked = presence.get();
                byte[] ping = "{}".getBytes(StandardCharsets.UTF_8);
                port.localCall("meta.ping", ping);
                if (presence.get() != marked + 1) {
                    throw new AssertionError("local ping " + presence.get());
                }
                marked = presence.get();
                port.remoteCall("meta.ping", ping);
                byte[] remoteStat = port.remoteCall("file.stat",
                        "{\"path\":\"Download/note.txt\"}".getBytes(StandardCharsets.UTF_8));
                McpProtocol.Reply relay = McpProtocol.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}", "Bearer token", "token",
                        new FileOps(home.resolve("files")), host, "test");
                if (presence.get() != marked || remoteStat == null || remoteStat.length == 0 || relay.status != 200) {
                    throw new AssertionError("remote presence " + presence.get() + " " + relay.status);
                }
                marked = presence.get();
                port.localCall("file.stat", "{\"path\":\"Download/note.txt\"}".getBytes(StandardCharsets.UTF_8));
                if (presence.get() != marked + 1) {
                    throw new AssertionError("local stat " + presence.get());
                }
            }
            try {
                LocalLink.dial(other, phone, "127.0.0.1", http.port());
                throw new AssertionError("unknown device");
            } catch (IllegalArgumentException expected) {
                if (!"unknown_device".equals(expected.getMessage())) {
                    throw expected;
                }
            }
            try (Socket origin = new Socket("127.0.0.1", http.port())) {
                origin.setSoTimeout(2000);
                origin.getOutputStream().write(("GET /v2/link HTTP/1.1\r\nHost: 127.0.0.1:" + http.port()
                        + "\r\nOrigin: http://127.0.0.1:" + http.port() + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                        + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                origin.getOutputStream().flush();
                int status = status(origin);
                if (status != 403) {
                    throw new AssertionError("origin " + status);
                }
            }
            try (Socket mcp = new Socket("127.0.0.1", http.port())) {
                mcp.setSoTimeout(2000);
                String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}";
                mcp.getOutputStream().write(("POST /mcp HTTP/1.1\r\nHost: 127.0.0.1:" + http.port()
                        + "\r\nAuthorization: Bearer token\r\nContent-Length: " + body.length() + "\r\n\r\n" + body)
                        .getBytes(StandardCharsets.US_ASCII));
                mcp.getOutputStream().flush();
                if (status(mcp) != 404) {
                    throw new AssertionError("mcp");
                }
            }
            if (!"privileged".equals(LinkReceipts.lane("terminal.open")) || !LinkReceipts.durable("terminal.open")
                    || !LinkReceipts.durable("install.run") || !"privileged".equals(LinkReceipts.lane("install.run"))
                    || LinkReceipts.durable("clipboard.get") || LinkReceipts.durable("meta.ping")) {
                throw new AssertionError("lane");
            }
        } finally {
            http.close();
            port.close();
        }
        System.out.println("link http ok");
    }

    private static void offerBack(LocalLink.Device phone, LocalLink.Device mac) throws Exception {
        Path home = Files.createTempDirectory("share-offer");
        LinkReceipts receipts = new LinkReceipts(home.resolve("receipts"));
        LocalLink.Phone listener = LocalLink.Phone.listen(phone, List.of(new LocalLink.Device(mac.id, mac.spki, null)),
                receipts, () -> new byte[0]);
        LocalLink.Session[] held = new LocalLink.Session[1];
        Thread accept = new Thread(() -> {
            try {
                held[0] = listener.accept();
            } catch (Exception error) {
                held[0] = null;
            }
        });
        accept.start();
        try (LocalLink.Session macSide = LocalLink.dial(mac, phone, "127.0.0.1", listener.port())) {
            macSide.setShareWriter((name, body) -> {
                Files.write(home.resolve(name), body);
            });
            accept.join(5000);
            if (held[0] == null) {
                throw new AssertionError("offer accept");
            }
            byte[] body = "from phone".getBytes(StandardCharsets.UTF_8);
            held[0].offer("back.txt", body);
            byte[] saved = Files.readAllBytes(home.resolve("back.txt"));
            if (!java.util.Arrays.equals(saved, body)) {
                throw new AssertionError("offer bytes");
            }
            held[0].close();
        } finally {
            listener.close();
        }
    }

    private static int status(Socket socket) throws Exception {
        StringBuilder line = new StringBuilder();
        while (true) {
            int value = socket.getInputStream().read();
            if (value < 0 || value == '\n') {
                break;
            }
            if (value != '\r') {
                line.append((char) value);
            }
        }
        String[] parts = line.toString().split(" ");
        return parts.length < 2 ? 0 : Integer.parseInt(parts[1]);
    }
}
