package dev.phonestation.adbkeep;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;

final class RemotePolicyTest {
    public static void main(String[] args) throws Exception {
        RemotePolicy none = new RemotePolicy(Collections.emptySet());
        try { none.require("station_shell_exec"); throw new AssertionError(); }
        catch (FileFailure denied) {
            expect(denied.getMessage().contains("Shell 与安装") && denied.getMessage().contains("权限与检查 → 远程访问范围"));
        }
        expect(none.allows("station_device_status") && none.allows("station_screen_close") && none.allows("station_terminal_close"));
        for (String tool : new String[] {"station_shell_exec", "station_shell_start", "station_terminal_open", "station_terminal_input",
                "station_screen_open", "station_screen_capture", "station_file_write_text", "station_file_read_text",
                "station_clipboard_get", "station_clipboard_configure", "station_notification_poll", "station_stay_awake"}) {
            expect(!none.allows(tool));
        }
        RemotePolicy shell = policy("shell"), screen = policy("screen"), write = policy("files.write");
        expect(shell.allows("station_terminal_input") && !shell.allows("station_screen_open"));
        expect(screen.allows("station_screen_open") && !screen.allows("station_shell_start"));
        expect(write.allows("station_file_delete") && !write.allows("station_file_open"));
        mustDeny(() -> screen.requireJob(Json.obj().put("kind", "shell").put("result", "secret command output")));
        mustDeny(() -> screen.requireJob(Json.obj().put("kind", "terminal")));
        mustDeny(() -> shell.requireJob(Json.obj().put("kind", "screen.open")));
        mustDeny(() -> shell.requireJob(Json.obj().put("kind", "future")));
        shell.requireJob(Json.obj().put("kind", "shell"));
        screen.requireJob(Json.obj().put("kind", "screen.open"));

        Path root = Files.createTempDirectory("remote-permissions");
        FileOps files = new FileOps(root);
        String list = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";
        Json all = Json.parse(handle(files, null, list).body).get("result").get("tools");
        RemotePolicy full = new RemotePolicy(new HashSet<>(Arrays.asList(RemotePolicy.SCOPES)));
        for (Json tool : all.array()) { expect(full.allows(tool.get("name").string())); }
        expect(!full.allows("station_file_future") && !full.allows("station_remote_grant"));
        Json limited = Json.parse(handle(files, host(none), list).body).get("result").get("tools");
        for (Json tool : limited.array()) { expect(none.allows(tool.get("name").string())); }
        expect(limited.array().size() == 6); // four status tools and two ways to end a session.

        String mutation = call(2, "station_file_write_text", Json.obj().put("path", "blocked.txt").put("text", "private"));
        expect(handle(files, host(none), mutation).body.contains("isError"));
        expect(!Files.exists(root.resolve("blocked.txt")));
        // A permitted mutation followed by a forbidden command must do nothing.
        String attack = call(3, "station_shell_exec", Json.obj().put("command", "untrusted command"));
        expect(handle(files, host(write), "[" + mutation + "," + attack + "]").status == 403);
        expect(!Files.exists(root.resolve("blocked.txt")));
        // Escaped field aliases cannot change what the permission check sees.
        String duplicate = mutation.replace("\"station_file_write_text\"", "\"station_device_status\",\"na\\u006de\":\"station_shell_exec\"");
        expect(handle(files, host(none), duplicate).body.contains("-32700"));
        expect(handle(files, host(write), mutation).status == 200);
        expect(Files.readString(root.resolve("blocked.txt")).equals("private"));
        Files.delete(root.resolve("blocked.txt"));
        // A request waiting for the file transaction lock must use the current
        // authorization when it finally acquires the lock.
        java.util.concurrent.atomic.AtomicReference<RemotePolicy> current = new java.util.concurrent.atomic.AtomicReference<>(write);
        StationHost changing = (StationHost) Proxy.newProxyInstance(StationHost.class.getClassLoader(), new Class<?>[] {StationHost.class}, (proxy, method, values) -> {
            if (method.getName().equals("authorize")) { current.get().require((String) values[0]); return null; }
            throw new AssertionError("unexpected host dispatch");
        });
        java.util.concurrent.atomic.AtomicReference<McpProtocol.Reply> waitingReply = new java.util.concurrent.atomic.AtomicReference<>();
        Thread waiting = new Thread(() -> waitingReply.set(handle(files, changing, mutation)));
        synchronized (FileOps.TRANSACTIONS) {
            waiting.start();
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
            while (waiting.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) { Thread.sleep(1); }
            expect(waiting.getState() == Thread.State.BLOCKED);
            current.set(none);
        }
        waiting.join(2000);
        expect(!waiting.isAlive() && waitingReply.get().body.contains("isError"));
        expect(!Files.exists(root.resolve("blocked.txt")));
        // Local adb tools retain their existing authorization and file checks.
        handle(files, null, mutation);
        expect(Files.exists(root.resolve("blocked.txt")));
        Files.delete(root.resolve("blocked.txt")); Files.delete(root);
        System.out.println("RemotePolicyTest ok");
    }
    private static RemotePolicy policy(String scope) { return new RemotePolicy(Collections.singleton(scope)); }
    private static StationHost host(RemotePolicy policy) {
        return (StationHost) Proxy.newProxyInstance(StationHost.class.getClassLoader(), new Class<?>[] {StationHost.class}, (proxy, method, args) -> {
            if (method.getName().equals("authorize")) { policy.require((String) args[0]); return null; }
            throw new AssertionError("Unauthorized host operation dispatched: " + method.getName());
        });
    }
    private static String call(int id, String name, Json arguments) {
        return Json.obj().put("jsonrpc", "2.0").put("id", id).put("method", "tools/call")
                .put("params", Json.obj().put("name", name).put("arguments", arguments)).emit();
    }
    private static McpProtocol.Reply handle(FileOps files, StationHost host, String body) {
        return McpProtocol.handle(body, "Bearer test", "test", files, host, "test");
    }
    private static void mustDeny(Runnable action) {
        try { action.run(); throw new AssertionError("permission bypass"); } catch (FileFailure expected) {}
    }
    private static void expect(boolean value) { if (!value) { throw new AssertionError(); } }
}
