package dev.phonestation.adbkeep;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

final class FileOpsTest {
    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("station-mcp");
        try {
            FileOps ops = new FileOps(home, "secret");
            Files.writeString(home.resolve("race.txt"), "a");
            String stale = ops.stat("race.txt").get("targetVersion").string();
            java.nio.file.attribute.FileTime time = Files.getLastModifiedTime(home.resolve("race.txt"));
            Files.writeString(home.resolve("race.txt"), "b");
            Files.setLastModifiedTime(home.resolve("race.txt"), time);
            mustThrow(() -> ops.appendText("race.txt", "!", stale), "已变化");
            FileOps otherChannel = new FileOps(home);
            for (int trial = 0; trial < 30; trial++) {
                String current = ops.stat("race.txt").get("targetVersion").string();
                String request = Json.obj().put("jsonrpc", "2.0").put("id", 1).put("method", "tools/call")
                        .put("params", Json.obj().put("name", "station_file_append_text").put("arguments",
                                Json.obj().put("path", "race.txt").put("text", "!").put("targetVersion", current))).emit();
                java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
                java.util.concurrent.CompletableFuture<Boolean> a = append(start, request, ops);
                java.util.concurrent.CompletableFuture<Boolean> b = append(start, request, otherChannel);
                start.countDown();
                expect(1, (a.get() ? 1 : 0) + (b.get() ? 1 : 0));
            }
            ops.writeText("note.txt", "手机\r\n", null);
            Json page = ops.readText("note.txt", 1, 10);
            expect("手机\r\n", page.get("text").string());
            expect("UTF-8", page.get("encoding").string());
            final String version = page.get("targetVersion").string();
            mustThrow(() -> ops.writeText("note.txt", "x", null), "targetVersion");
            mustThrow(() -> ops.writeText("note.txt", "x", version + ":stale"), "已变化");
            ops.writeText("note.txt", "abc", version);
            Json replaced = ops.replaceText(
                    "note.txt", "b", "bbb", ops.stat("note.txt").get("targetVersion").string());
            expect(1, replaced.get("replacements").longValue());
            expect("abbbc", ops.readText("note.txt", 1, 10).get("text").string());
            ops.appendText("note.txt", "!", replaced.get("targetVersion").string());
            expect("abbbc!", ops.readText("note.txt", 1, 10).get("text").string());

            byte[] gbk = new byte[] {(byte) 0xd6, (byte) 0xd0};
            Files.write(home.resolve("gb.txt"), gbk);
            expect("中", ops.readText("gb.txt", 1, 5).get("text").string());
            String gbkVersion = ops.stat("gb.txt").get("targetVersion").string();
            ops.appendText("gb.txt", "!", gbkVersion);
            expect("中!", ops.readText("gb.txt", 1, 5).get("text").string());

            ops.writeBytes("bin.dat", "0000", null);
            Json patches = Json.arr().add(Json.obj().put("offset", 1).put("hex", "ff"));
            ops.patchBytes("bin.dat", patches.array(), ops.stat("bin.dat").get("targetVersion").string());
            expect("00FF", ops.readBytes("bin.dat", 0, 4).get("hex").string());
            mustThrow(() -> {
                Json overlap = Json.arr()
                        .add(Json.obj().put("offset", 0).put("hex", "aa"))
                        .add(Json.obj().put("offset", 0).put("hex", "bb"));
                ops.patchBytes("bin.dat", overlap.array(), ops.stat("bin.dat").get("targetVersion").string());
            }, "重叠");
            mustThrow(() -> ops.truncateBytes("bin.dat", 9, ops.stat("bin.dat").get("targetVersion").string()), "撑大");
            ops.truncateBytes("bin.dat", 1, ops.stat("bin.dat").get("targetVersion").string());
            expect(1, ops.stat("bin.dat").get("size").longValue());

            ops.createDirectory("dir/sub");
            Json again = ops.createDirectory("dir/sub");
            expect(false, again.get("created").boolValue());
            ops.writeText("dir/sub/a.txt", "a", null);
            ops.copy("dir/sub/a.txt", "dir/b.txt");
            ops.move("dir/b.txt", "dir/c.txt");
            expect("a", ops.readText("dir/c.txt", 1, 1).get("text").string());
            mustThrow(() -> ops.delete("dir"), "这是目录");
            mustThrow(() -> ops.deleteDirectory("dir", false), "不是空的");
            ops.deleteDirectory("dir", true);
            mustThrow(() -> ops.stat("dir/c.txt"), "没有这个文件");
            mustThrow(() -> ops.deleteDirectory("", true), "不能删除");
            mustThrow(() -> ops.writeText("secret/a", "x", null), "不开放");
            mustThrow(() -> ops.readText("../outside", 1, 1), "不在内部存储");

            Files.write(home.resolve("outside.txt"), "no".getBytes(StandardCharsets.UTF_8));
            Files.createSymbolicLink(home.resolve("link"), home.resolve("outside.txt"));
            mustThrow(() -> ops.readText("link", 1, 1), "链接");
            Files.createSymbolicLink(home.resolve("escape"), Path.of("/etc/passwd"));
            mustThrow(() -> ops.readText("escape", 1, 1), "链接");

            ops.writeText("find-me.txt", "one\ntwo target\nthree\n", null);
            Json found = ops.search("", "find-me", 10);
            expect(true, found.get("matches").array().get(0).string().endsWith("find-me.txt"));
            Json hits = ops.searchText("find-me.txt", "target", 10, 1);
            expect(2, hits.get("matches").array().get(0).get("line").longValue());
            expect("two target", hits.get("matches").array().get(0).get("lineText").string());

            mustThrow(() -> ops.writeBytes("odd.dat", "abc", null), "偶数");

            final java.util.List<String> scanned = new java.util.ArrayList<String>();
            ops.setMediaNotice(new FileOps.MediaNotice() {
                @Override
                public void changed(java.util.List<String> paths) {
                    scanned.addAll(paths);
                }
            });
            Json wrote = ops.writeText("scan.txt", "z", null);
            expect(true, wrote.get("mediaScan").boolValue());
            expect(true, scanned.get(0).endsWith("scan.txt"));
            Json policy = ops.policy();
            expect("截图", policy.get("places").array().get(0).get("name").string());
            expect(true, policy.get("handoff").get("inbox").string().endsWith("inbox"));
            expect(true, policy.get("handoff").get("outbox").string().endsWith("outbox"));
            ops.createDirectory("Download");
            ops.writeText("Download/a.txt", "abcd", null);
            Json summary = ops.summary();
            boolean download = false;
            for (int i = 0; i < summary.get("places").array().size(); i++) {
                Json place = summary.get("places").array().get(i);
                if ("下载".equals(place.get("name").string())) {
                    expect(true, place.get("exists").boolValue());
                    expect(true, place.get("bytes").longValue() >= 4);
                    download = true;
                }
            }
            expect(true, download);
            expect(true, summary.get("freeBytes").longValue() >= 0);
            System.out.println("FileOpsTest ok");
        } finally {
            deleteTree(home);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<Path>() {
            @Override
            public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs)
                    throws IOException {
                Files.delete(file);
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult postVisitDirectory(Path dir, IOException error)
                    throws IOException {
                Files.delete(dir);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }

    private static java.util.concurrent.CompletableFuture<Boolean> append(
            java.util.concurrent.CountDownLatch start, String request, FileOps files) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try { start.await(); } catch (InterruptedException e) { throw new RuntimeException(e); }
            String body = McpProtocol.handle(request, "Bearer token", "token", files, null, "test").body;
            return !body.contains("isError");
        });
    }

    private static void expect(String want, String got) {
        if (!want.equals(got)) {
            throw new AssertionError("want [" + want + "] got [" + got + "]");
        }
    }

    private static void expect(long want, long got) {
        if (want != got) {
            throw new AssertionError("want " + want + " got " + got);
        }
    }

    private static void expect(boolean want, boolean got) {
        if (want != got) {
            throw new AssertionError("want " + want + " got " + got);
        }
    }

    private static void mustThrow(Runnable action, String part) {
        try {
            action.run();
        } catch (FileFailure error) {
            if (error.getMessage().contains(part)) {
                return;
            }
            throw new AssertionError(error.getMessage());
        }
        throw new AssertionError("expected " + part);
    }
}
