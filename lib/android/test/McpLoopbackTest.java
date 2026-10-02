package dev.phonestation.adbkeep;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

final class McpLoopbackTest {
    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("station-mcp-http");
        final boolean[] stayed = new boolean[] {false};
        final int[] shellCalls = new int[] {0};
        CountDownLatch shellEntered = new CountDownLatch(1);
        CountDownLatch shellRelease = new CountDownLatch(1);
        StationHost host = new StationHost() {
            @Override
            public Json status() {
                return Json.obj().put("batteryPercent", 80);
            }

            @Override
            public Json stayAwake(boolean on) {
                stayed[0] = on;
                return Json.obj().put("stayAwake", on);
            }

            @Override
            public Json notify(String title, String text, boolean stack, String agent, String sound) {
                return Json.obj().put("posted", true).put("title", title)
                        .put("stack", stack).put("soundArg", sound == null ? "" : sound);
            }

            @Override
            public Json clipboard(String text) {
                return Json.obj().put("copied", true).put("characters", text.length());
            }
            public Json clipboardGet() { return Json.obj().put("kind", "text").put("text", "phone"); }
            public Json clipboardState() { return Json.obj().put("shared", false).put("automatic", true); }
            public Json clipboardConfigure(Json args) { return args; }
            public Json clipboardExchange(Json args) { return Json.obj().put("phoneVersion", "v1").put("appliedMac", false); }

            @Override
            public Json open(String path) {
                return Json.obj().put("opened", true).put("path", path);
            }

            @Override
            public Json shellStatus() {
                return Json.obj().put("available", false).put("state", "denied");
            }

            @Override
            public Json shellExecute(ShellRequest request) {
                shellCalls[0]++;
                if ("wait".equals(request.command)) {
                    shellEntered.countDown();
                    try {
                        if (!shellRelease.await(5, TimeUnit.SECONDS)) {
                            throw new FileFailure("test shell was not released");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new FileFailure("test shell interrupted");
                    }
                    return Json.obj().put("exitCode", 0).put("stdout", "done");
                }
                throw new FileFailure("在手机工位的权限页允许使用 Shizuku");
            }
        };
        McpHttp http = McpHttp.open(0, "TOKEN", new FileOps(home), host, "6");
        try {
            String[] denied = post(http.port(), "NOPE", initialize());
            expect("401", denied[0]);

            String[] note = post(http.port(), "TOKEN",
                    "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
            expect("202", note[0]);

            String[] hello = post(http.port(), "TOKEN", initialize());
            expect("200", hello[0]);
            expect(true, hello[1].contains("手机工位"));
            expect(true, hello[1].contains("2025-03-26"));

            String[] tools = post(http.port(), "TOKEN",
                    "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
            expect(true, tools[1].contains("station_file_delete_directory"));
            expect(true, tools[1].contains("station_device_status"));
            expect(true, tools[1].contains("station_storage_summary"));
            expect(true, tools[1].contains("station_stay_awake"));
            expect(true, tools[1].contains("station_notify"));
            expect(true, tools[1].contains("station_clipboard_set"));
            expect(true, tools[1].contains("station_clipboard_get"));
            expect(true, tools[1].contains("station_clipboard_state"));
            expect(true, tools[1].contains("station_clipboard_configure"));
            expect(true, tools[1].contains("station_clipboard_exchange"));
            expect(true, tools[1].contains("station_file_open"));
            Json listed = Json.parse(tools[1]).get("result").get("tools");
            for (Json tool : listed.array()) {
                if ("station_shell_exec".equals(tool.get("name").string())) {
                    expect(false, tool.get("annotations").get("readOnlyHint").boolValue());
                    expect(true, tool.get("annotations").get("destructiveHint").boolValue());
                }
            }
            expect(true, tools[1].contains("station_shell_exec"));
            expect(true, tools[1].contains("station_shell_status"));
            expect(true, tools[1].contains("destructiveHint"));

            String[] wrote = call(http.port(), "station_file_write_text",
                    "{\"path\":\"note.txt\",\"text\":\"手机\\n\"}");
            expect(false, wrote[1].contains("isError"));
            String[] read = call(http.port(), "station_file_read_text", "{\"path\":\"note.txt\"}");
            expect(true, read[1].contains("手机"));
            String[] missing = call(http.port(), "station_file_delete", "{\"path\":\"nope.txt\"}");
            expect(true, missing[1].contains("isError"));
            expect(true, missing[1].contains("没有这个文件"));
            call(http.port(), "station_file_delete", "{\"path\":\"note.txt\"}");

            String[] awake = call(http.port(), "station_stay_awake", "{\"on\":true}");
            expect(true, awake[1].contains("stayAwake"));
            expect(true, stayed[0]);
            String[] clip = call(http.port(), "station_clipboard_set", "{\"text\":\"\"}");
            expect(true, clip[1].contains("isError"));
            expect(true, call(http.port(), "station_clipboard_get", "{}")[1].contains("phone"));
            expect(true, call(http.port(), "station_clipboard_state", "{}")[1].contains("automatic"));
            expect(true, call(http.port(), "station_clipboard_configure", "{\"shared\":true}")[1].contains("shared"));
            expect(true, call(http.port(), "station_clipboard_exchange", "{}")[1].contains("phoneVersion"));
            String[] reminded = call(http.port(), "station_notify",
                    "{\"title\":\"标题\",\"text\":\"内容\",\"agent\":\"Grok\"}");
            expect(true, reminded[1].contains("标题"));
            String[] customSound = call(http.port(), "station_notify",
                    "{\"title\":\"标题\",\"text\":\"内容\",\"mode\":\"stack\",\"sound\":\"/system/media/Bell.ogg\"}");
            expect(true, customSound[1].contains("/system/media/Bell.ogg"));
            expect(true, customSound[1].contains("stack"));
            String[] status = call(http.port(), "station_device_status", "{}");
            expect(true, status[1].contains("batteryPercent"));

            String[] shellStatus = call(http.port(), "station_shell_status", "{}");
            expect(true, shellStatus[1].contains("denied"));
            String[] shellDenied = call(http.port(), "station_shell_exec", "{\"command\":\"id\"}");
            expect(true, shellDenied[1].contains("isError"));
            expect(true, shellDenied[1].contains("允许使用 Shizuku"));
            expect(1, shellCalls[0]);
            String[] invalidShell = call(http.port(), "station_shell_exec",
                    "{\"command\":\"id\",\"timeoutMs\":60001}");
            expect(true, invalidShell[1].contains("isError"));
            expect(1, shellCalls[0]);
            String[] deniedShell = post(http.port(), "NOPE",
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                            + "\"params\":{\"name\":\"station_shell_exec\",\"arguments\":{\"command\":\"id\"}}}");
            expect("401", deniedShell[0]);
            expect(1, shellCalls[0]);

            CompletableFuture<String[]> busyShell = CompletableFuture.supplyAsync(() -> {
                try {
                    return call(http.port(), "station_shell_exec", "{\"command\":\"wait\"}");
                } catch (Exception error) {
                    throw new RuntimeException(error);
                }
            });
            try {
                expect(true, shellEntered.await(2, TimeUnit.SECONDS));
                long pingStarted = System.nanoTime();
                String[] ping = post(http.port(), "TOKEN", "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"ping\"}");
                expect("200", ping[0]);
                expect(true, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - pingStarted) < 500);
            } finally {
                shellRelease.countDown();
            }
            expect(true, busyShell.get(2, TimeUnit.SECONDS)[1].contains("done"));

            String[] bad = post(http.port(), "TOKEN", "{");
            expect(true, bad[1].contains("-32700"));
            System.out.println("McpLoopbackTest ok");
        } finally {
            http.close();
            Files.walkFileTree(home, new java.nio.file.SimpleFileVisitor<Path>() {
                @Override
                public java.nio.file.FileVisitResult visitFile(
                        Path file, java.nio.file.attribute.BasicFileAttributes attrs) throws java.io.IOException {
                    Files.delete(file);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult postVisitDirectory(Path dir, java.io.IOException error)
                        throws java.io.IOException {
                    Files.delete(dir);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        }
    }

    private static String initialize() {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
                + "\"protocolVersion\":\"2025-03-26\",\"capabilities\":{},"
                + "\"clientInfo\":{\"name\":\"phone\",\"version\":\"0\"}}}";
    }

    private static String[] call(int port, String name, String arguments) throws Exception {
        String json = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{"
                + "\"name\":\"" + name + "\",\"arguments\":" + arguments + "}}";
        return post(port, "TOKEN", json);
    }

    private static String[] post(int port, String token, String json) throws Exception {
        Socket socket = new Socket("127.0.0.1", port);
        socket.setSoTimeout(5000);
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        String request = "POST /mcp HTTP/1.1\r\n"
                + "Host: 127.0.0.1\r\n"
                + "Content-Type: application/json\r\n"
                + "Accept: application/json, text/event-stream\r\n"
                + "Authorization: Bearer " + token + "\r\n"
                + "Content-Length: " + payload.length + "\r\n"
                + "Connection: close\r\n\r\n";
        OutputStream out = socket.getOutputStream();
        out.write(request.getBytes(StandardCharsets.US_ASCII));
        out.write(payload);
        out.flush();
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        InputStream in = socket.getInputStream();
        byte[] buffer = new byte[4096];
        int n;
        while ((n = in.read(buffer)) >= 0) {
            all.write(buffer, 0, n);
        }
        socket.close();
        String text = all.toString("UTF-8");
        int split = text.indexOf("\r\n\r\n");
        if (split < 0) {
            throw new AssertionError(text);
        }
        String head = text.substring(0, split);
        int firstSpace = head.indexOf(' ');
        int secondSpace = head.indexOf(' ', firstSpace + 1);
        return new String[] {head.substring(firstSpace + 1, secondSpace), text.substring(split + 4)};
    }

    private static void expect(String want, String got) {
        if (!want.equals(got)) {
            throw new AssertionError("want [" + want + "] got [" + got + "]");
        }
    }

    private static void expect(boolean want, boolean got) {
        if (want != got) {
            throw new AssertionError("want " + want + " got " + got);
        }
    }

    private static void expect(int want, int got) {
        if (want != got) {
            throw new AssertionError("want " + want + " got " + got);
        }
    }
}
