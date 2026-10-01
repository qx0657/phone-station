package dev.phonestation.adbkeep;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

final class McpLoopbackTest {
    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("station-mcp-http");
        final boolean[] stayed = new boolean[] {false};
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
            public Json notify(String title, String text, boolean stack, String agent) {
                return Json.obj().put("posted", true).put("title", title);
            }

            @Override
            public Json clipboard(String text) {
                return Json.obj().put("copied", true).put("characters", text.length());
            }

            @Override
            public Json open(String path) {
                return Json.obj().put("opened", true).put("path", path);
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
            expect(true, tools[1].contains("station_file_open"));
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
            String[] reminded = call(http.port(), "station_notify",
                    "{\"title\":\"标题\",\"text\":\"内容\",\"agent\":\"Grok\"}");
            expect(true, reminded[1].contains("标题"));
            String[] status = call(http.port(), "station_device_status", "{}");
            expect(true, status[1].contains("batteryPercent"));

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
}
