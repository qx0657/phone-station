package dev.phonestation.adbkeep;

import java.nio.file.Files;
import java.nio.file.Path;

final class McpProtocolTest {
    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("mcp-protocol");
        FileOps files = new FileOps(home);
        for (String malformed : new String[] {
                "{}", "null", "1", "[]", "{\"id\":1,\"method\":\"ping\"}",
                "{\"jsonrpc\":\"1.0\",\"id\":1,\"method\":\"ping\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":null,\"method\":\"ping\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":true,\"method\":\"ping\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":{},\"method\":\"ping\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":1.5,\"method\":\"ping\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":7}",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{},\"error\":{}}"}) {
            expect(reply(files, malformed).body.contains("-32600"));
        }
        expect(reply(files, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"params\":null}").body.contains("-32602"));
        expect(reply(files, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"params\":[]}").body.contains("-32602"));
        expect(reply(files, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"method\":\"tools/call\"}").body.contains("-32700"));
        String ping = "{\"jsonrpc\":\"2.0\",\"id\":\"read\",\"method\":\"ping\"}";
        String notification = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}";
        Json batch = Json.parse(reply(files, "[" + ping + "," + notification + ",null]").body);
        expect(batch.array().size() == 2);
        expect(batch.array().get(0).get("id").string().equals("read"));
        expect(batch.array().get(1).get("error").get("code").longValue() == -32600);
        expect(reply(files, "[" + notification + "," + notification + "]").status == 202);
        expect(reply(files, "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}").status == 202);
        String mutation = "{\"jsonrpc\":\"2.0\",\"id\":\"read\",\"method\":\"tools/call\",\"params\":{\"name\":\"station_file_write_text\",\"arguments\":{\"path\":\"bad.txt\",\"text\":\"bad\"}}}";
        expect(reply(files, "[" + ping + "," + mutation + "]").body.contains("-32600"));
        expect(reply(files, "[" + ping.replace("\"read\"", "0") + "," + ping.replace("\"read\"", "-0") + "]").body.contains("-32600"));
        expect(!Files.exists(home.resolve("bad.txt")));
        expect(reply(files, mutation.replace("\"id\":\"read\"", "\"id\":null")).body.contains("-32600"));
        expect(!Files.exists(home.resolve("bad.txt")));
        expect(reply(files, mutation.replace("\"id\":\"read\",", "")).status == 202);
        expect(!Files.exists(home.resolve("bad.txt")));
        expect(reply(files, "[" + (ping + ",").repeat(64) + ping + "]").body.contains("-32600"));
        Files.delete(home);
        System.out.println("McpProtocolTest ok");
    }
    private static McpProtocol.Reply reply(FileOps files, String body) {
        return McpProtocol.handle(body, "Bearer token", "token", files, null, "test");
    }
    private static void expect(boolean value) { if (!value) { throw new AssertionError(); } }
}
