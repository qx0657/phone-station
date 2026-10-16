package dev.phonestation.adbkeep;

import dev.phonestation.adbkeep.link.LinkReceipts;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** 回执先落盘，重启转换，冲突和同编号只执行一次。 */
final class LinkReceiptTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("link-receipts");
        try {
            Manual clock = new Manual();
            try (LinkReceipts store = new LinkReceipts(root, clock)) {
                writeBeforeRun(store);
                replayAndConflict(store);
                concurrentInsert(store);
                readOnlySkipsDisk(store, root);
                ackDropsBody(store);
            }
            try (LinkReceipts store = new LinkReceipts(root.resolve("restart"), clock)) {
                restart(store, root.resolve("restart"), clock);
            }
            try (LinkReceipts store = new LinkReceipts(root.resolve("busy"), clock)) {
                busy(store);
            }
            try (LinkReceipts store = new LinkReceipts(root.resolve("expire"), clock)) {
                expireQueued(store, clock);
            }
            Manual later = new Manual();
            try (LinkReceipts store = new LinkReceipts(root.resolve("later"), later)) {
                bodyExpires(store, later, root.resolve("later"));
                cancelQueued(store);
                privilegedCap(store);
            }
            oversized(root.resolve("oversize"));
            notApplied(root.resolve("not-applied"));
            directorySync(root.resolve("sync"));
            writeFailure(root.resolve("write-fail"));
            capAndIndex(root.resolve("cap"));
            readOnlyOccupiesSlot(root.resolve("readonly-slot"));
            privilegedExpiresWithoutWaiting(root.resolve("sweep"));
            finishedAge(root.resolve("finished-age"));
            corruptReceipt(root.resolve("corrupt"));
            replacedEpoch(root.resolve("bad-epoch"));
        } finally {
            delete(root);
        }
        System.out.println("link receipts ok");
    }

    private static void writeBeforeRun(LinkReceipts store) throws Exception {
        String mac = id(1);
        String op = id(2);
        CountDownLatch done = new CountDownLatch(1);
        AtomicInteger seenRunning = new AtomicInteger();
        store.submit(mac, op, "clipboard.set", "one".getBytes(), () -> {
            if ("running".equals(store.query(mac, op).state)) {
                seenRunning.incrementAndGet();
            }
            done.countDown();
            return "saved".getBytes();
        });
        if (!done.await(5, TimeUnit.SECONDS) || seenRunning.get() != 1) {
            throw new AssertionError("write before run");
        }
        LinkReceipts.Receipt doneReceipt = awaitState(store, mac, op, "complete");
        if (!"saved".equals(new String(doneReceipt.body)) || !doneReceipt.applied) {
            throw new AssertionError("result");
        }
    }

    private static void replayAndConflict(LinkReceipts store) throws Exception {
        String mac = id(3);
        String op = id(4);
        AtomicInteger runs = new AtomicInteger();
        store.submit(mac, op, "file.write", "same".getBytes(), () -> {
            runs.incrementAndGet();
            return "wrote".getBytes();
        });
        awaitState(store, mac, op, "complete");
        LinkReceipts.Receipt replay = store.submit(mac, op, "file.write", "same".getBytes(), () -> {
            runs.incrementAndGet();
            return "again".getBytes();
        });
        LinkReceipts.Receipt conflict = store.submit(mac, op, "file.write", "other".getBytes(), () -> {
            runs.incrementAndGet();
            return "no".getBytes();
        });
        if (runs.get() != 1 || !"complete".equals(replay.state) || !"conflict".equals(conflict.state)) {
            throw new AssertionError("conflict " + runs.get() + " " + replay.state + " " + conflict.state);
        }
    }

    private static void concurrentInsert(LinkReceipts store) throws Exception {
        String mac = id(5);
        String op = id(6);
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger runs = new AtomicInteger();
        Thread[] threads = new Thread[2];
        Throwable[] errors = new Throwable[2];
        for (int i = 0; i < 2; i++) {
            threads[i] = new Thread(() -> {
                try {
                    barrier.await(5, TimeUnit.SECONDS);
                    store.submit(mac, op, "clipboard.set", "once".getBytes(), () -> {
                        runs.incrementAndGet();
                        return "ok".getBytes();
                    });
                } catch (Throwable error) {
                    errors[0] = error;
                }
            });
            threads[i].start();
        }
        for (Thread thread : threads) {
            thread.join(5000);
        }
        awaitState(store, mac, op, "complete");
        if (errors[0] != null || runs.get() != 1) {
            throw new AssertionError("concurrent " + runs.get());
        }
    }

    private static void readOnlySkipsDisk(LinkReceipts store, Path root) throws Exception {
        int before = count(root, ".rec");
        LinkReceipts.Receipt receipt = store.submit(id(7), id(8), "file.stat", "p".getBytes(), () -> "stat".getBytes());
        if (!"complete".equals(receipt.state) || count(root, ".rec") != before) {
            throw new AssertionError("read only");
        }
    }

    private static void ackDropsBody(LinkReceipts store) throws Exception {
        String mac = id(9);
        String op = id(10);
        store.submit(mac, op, "alert.notify", "n".getBytes(), () -> "note".getBytes());
        awaitState(store, mac, op, "complete");
        if (store.ack(mac, op).body != null || store.query(mac, op).body != null || !"complete".equals(store.query(mac, op).state)) {
            throw new AssertionError("ack");
        }
    }

    private static void restart(LinkReceipts store, Path root, Manual clock) throws Exception {
        String mac = id(11);
        CountDownLatch entered = new CountDownLatch(1);
        AtomicInteger runs = new AtomicInteger();
        store.submit(mac, id(12), "shell.run", "run".getBytes(), () -> {
            runs.incrementAndGet();
            entered.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException ignored) {
                return new byte[0];
            }
            return "done".getBytes();
        });
        if (!entered.await(5, TimeUnit.SECONDS)) {
            throw new AssertionError("running");
        }
        store.submit(mac, id(13), "shell.run", "wait".getBytes(), () -> {
            runs.incrementAndGet();
            return "queued".getBytes();
        });
        if (!"queued".equals(store.query(mac, id(13)).state)) {
            throw new AssertionError("queue");
        }
        String epoch = store.epoch();
        store.close();
        try (LinkReceipts again = new LinkReceipts(root, clock)) {
            LinkReceipts.Receipt running = again.query(mac, id(12));
            LinkReceipts.Receipt queued = again.query(mac, id(13));
            if (!epoch.equals(again.epoch()) || !"unknown".equals(running.state) || !running.applied
                    || !"expired".equals(queued.state) || queued.applied || runs.get() != 1) {
                throw new AssertionError("restart " + running.state + " " + queued.state + " " + runs.get());
            }
        }
    }

    private static void busy(LinkReceipts store) throws Exception {
        String mac = id(14);
        CountDownLatch entered = new CountDownLatch(4);
        for (int i = 0; i < 4; i++) {
            String op = id(20 + i);
            store.submit(mac, op, "clipboard.set", op.getBytes(), () -> {
                entered.countDown();
                new CountDownLatch(1).await();
                return "held".getBytes();
            });
        }
        if (!entered.await(5, TimeUnit.SECONDS)) {
            throw new AssertionError("slots");
        }
        for (int i = 0; i < 4; i++) {
            String op = id(30 + i);
            if (!"queued".equals(store.submit(mac, op, "clipboard.set", op.getBytes(), () -> "later".getBytes()).state)) {
                throw new AssertionError("queued");
            }
        }
        LinkReceipts.Receipt overflow = store.submit(mac, id(40), "clipboard.set", "more".getBytes(), () -> "no".getBytes());
        if (!"busy".equals(overflow.state) || store.query(mac, id(40)).state.equals("queued")) {
            throw new AssertionError("busy");
        }
    }

    private static void expireQueued(LinkReceipts store, Manual clock) throws Exception {
        String mac = id(15);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger runs = new AtomicInteger();
        store.submit(mac, id(16), "shell.run", "first".getBytes(), () -> {
            runs.incrementAndGet();
            entered.countDown();
            release.await();
            return "first".getBytes();
        });
        if (!entered.await(5, TimeUnit.SECONDS)) {
            throw new AssertionError("privileged");
        }
        store.submit(mac, id(17), "shell.run", "second".getBytes(), () -> {
            runs.incrementAndGet();
            return "second".getBytes();
        });
        clock.now = LinkReceipts.PRIVILEGED_QUEUE_MS + 1;
        release.countDown();
        LinkReceipts.Receipt expired = awaitState(store, mac, id(17), "expired");
        LinkReceipts.Receipt first = awaitState(store, mac, id(16), "complete");
        if (expired.applied || !first.applied || runs.get() != 1) {
            throw new AssertionError("expire " + expired.state + " " + runs.get());
        }
    }

    private static void bodyExpires(LinkReceipts store, Manual clock, Path root) throws Exception {
        String mac = id(18);
        String op = id(19);
        store.submit(mac, op, "clipboard.set", "keep".getBytes(), () -> "body".getBytes());
        awaitState(store, mac, op, "complete");
        clock.now = LinkReceipts.BODY_MS;
        LinkReceipts.Receipt expired = store.query(mac, op);
        String text = Files.readString(root.resolve(mac + "." + op + ".rec"));
        if (!text.endsWith("\t-") || expired.body != null || expired.resultKept || !"complete".equals(expired.state)) {
            throw new AssertionError("body age");
        }
    }

    private static void oversized(Path root) throws Exception {
        try (LinkReceipts store = new LinkReceipts(root, new Manual())) {
            String mac = id(60);
            String op = id(61);
            store.submit(mac, op, "clipboard.set", "over".getBytes(), () -> new byte[LinkReceipts.BODY_LIMIT + 1]);
            LinkReceipts.Receipt receipt = awaitState(store, mac, op, "complete");
            if (receipt.body == null || receipt.body.length != LinkReceipts.BODY_LIMIT + 1 || receipt.resultKept || !receipt.applied) {
                throw new AssertionError("oversized");
            }
        }
        try (LinkReceipts again = new LinkReceipts(root, new Manual())) {
            LinkReceipts.Receipt later = again.query(id(60), id(61));
            if (later.body != null || later.resultKept || !"complete".equals(later.state)) {
                throw new AssertionError("oversized disk");
            }
        }
    }

    private static void notApplied(Path root) throws Exception {
        try (LinkReceipts store = new LinkReceipts(root, new Manual())) {
            store.submit(id(62), id(63), "clipboard.set", "skip".getBytes(), () -> {
                throw new LinkReceipts.NotApplied();
            });
            LinkReceipts.Receipt skipped = awaitState(store, id(62), id(63), "failed");
            if (skipped.applied || skipped.resultKept) {
                throw new AssertionError("not applied");
            }
            store.submit(id(64), id(65), "clipboard.set", "started".getBytes(), () -> {
                throw new IllegalStateException("started");
            });
            LinkReceipts.Receipt started = awaitState(store, id(64), id(65), "failed");
            if (!started.applied || started.resultKept) {
                throw new AssertionError("started");
            }
        }
    }

    private static void directorySync(Path root) throws Exception {
        AtomicInteger syncs = new AtomicInteger();
        try (LinkReceipts store = new LinkReceipts(root, new Manual(), LinkReceipts.MAX_RECEIPTS,
                LinkReceipts.BODY_BUDGET, ignored -> syncs.incrementAndGet(), null)) {
            int opened = syncs.get();
            if (opened < 1) {
                throw new AssertionError("epoch");
            }
            store.submit(id(66), id(67), "clipboard.set", "sync".getBytes(), () -> "ok".getBytes());
            awaitState(store, id(66), id(67), "complete");
            if (syncs.get() < opened + 3) {
                throw new AssertionError(syncs.get());
            }
        }
        AtomicInteger attempts = new AtomicInteger();
        Path failed = root.resolve("failed");
        try (LinkReceipts store = new LinkReceipts(failed, new Manual(), LinkReceipts.MAX_RECEIPTS,
                LinkReceipts.BODY_BUDGET, ignored -> {
                    if (attempts.incrementAndGet() > 1) {
                        throw new IOException("directory");
                    }
                }, null)) {
            AtomicInteger runs = new AtomicInteger();
            LinkReceipts.Receipt receipt = store.submit(id(66), id(68), "clipboard.set", "sync".getBytes(), () -> {
                runs.incrementAndGet();
                return "ok".getBytes();
            });
            if (!"busy".equals(receipt.state) || runs.get() != 0 || count(failed, ".rec") != 0) {
                throw new AssertionError("sync orphan " + receipt.state);
            }
        }
    }

    private static void writeFailure(Path root) throws Exception {
        AtomicReference<String> fail = new AtomicReference<String>("queued");
        AtomicInteger runs = new AtomicInteger();
        String mac = id(72);
        try (LinkReceipts store = new LinkReceipts(root, new Manual(), LinkReceipts.MAX_RECEIPTS,
                LinkReceipts.BODY_BUDGET, ignored -> { }, state -> {
                    if (state.equals(fail.get())) {
                        throw new IOException(state);
                    }
                })) {
            LinkReceipts.Receipt queued = store.submit(id(68), id(69), "clipboard.set", "q".getBytes(), () -> {
                runs.incrementAndGet();
                return "q".getBytes();
            });
            if (!"busy".equals(queued.state) || runs.get() != 0 || count(root, ".rec") != 0) {
                throw new AssertionError("queued write " + queued.state);
            }
            fail.set("running");
            store.submit(id(70), id(71), "clipboard.set", "r".getBytes(), () -> {
                runs.incrementAndGet();
                return "r".getBytes();
            });
            LinkReceipts.Receipt running = awaitState(store, id(70), id(71), "expired");
            if (running.applied || runs.get() != 0) {
                throw new AssertionError("running write");
            }
            fail.set("complete");
            store.submit(mac, id(73), "clipboard.set", "c".getBytes(), () -> "kept".getBytes());
            LinkReceipts.Receipt kept = awaitState(store, mac, id(73), "complete");
            if (!kept.applied || !kept.resultKept || !"kept".equals(new String(kept.body))) {
                throw new AssertionError("memory");
            }
            String[] parts = Files.readString(root.resolve(mac + "." + id(73) + ".rec")).split("\t", -1);
            if (!"running".equals(parts[5])) {
                throw new AssertionError(parts[5]);
            }
            CountDownLatch submitted = new CountDownLatch(LinkReceipts.IN_FLIGHT);
            CountDownLatch hold = new CountDownLatch(1);
            AtomicInteger accepted = new AtomicInteger();
            AtomicReference<Throwable> error = new AtomicReference<Throwable>();
            for (int index = 0; index < LinkReceipts.IN_FLIGHT; index++) {
                int number = index;
                Thread thread = new Thread(() -> {
                    try {
                        LinkReceipts.Receipt receipt = store.submit(mac, id(80 + number), "clipboard.set", ("d" + number).getBytes(), () -> {
                            hold.await();
                            return "x".getBytes();
                        });
                        if ("queued".equals(receipt.state) || "running".equals(receipt.state)) {
                            accepted.incrementAndGet();
                        }
                    } catch (Throwable failure) {
                        error.set(failure);
                    } finally {
                        submitted.countDown();
                    }
                });
                thread.start();
            }
            if (!submitted.await(5, TimeUnit.SECONDS) || accepted.get() != LinkReceipts.IN_FLIGHT || error.get() != null) {
                hold.countDown();
                throw new AssertionError("slot " + accepted.get());
            }
            hold.countDown();
            for (int index = 0; index < LinkReceipts.IN_FLIGHT; index++) {
                awaitState(store, mac, id(80 + index), "complete");
            }
        }
        try (LinkReceipts restarted = new LinkReceipts(root, new Manual())) {
            LinkReceipts.Receipt unknown = restarted.query(mac, id(73));
            if (!"unknown".equals(unknown.state) || !unknown.applied) {
                throw new AssertionError(unknown.state);
            }
        }
    }

    private static void capAndIndex(Path root) throws Exception {
        if (LinkReceipts.MAX_RECEIPTS != 4096) {
            throw new AssertionError(LinkReceipts.MAX_RECEIPTS);
        }
        Manual clock = new Manual();
        String mac = id(90);
        try (LinkReceipts store = new LinkReceipts(root.resolve("count"), clock, 2)) {
            CountDownLatch entered = new CountDownLatch(2);
            CountDownLatch release = new CountDownLatch(1);
            for (int index = 0; index < 2; index++) {
                String op = id(91 + index);
                store.submit(mac, op, "clipboard.set", op.getBytes(), () -> {
                    entered.countDown();
                    release.await();
                    return "held".getBytes();
                });
            }
            if (!entered.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("cap hold");
            }
            clock.now = LinkReceipts.EVICT_MS;
            if (!"busy".equals(store.submit(mac, id(93), "clipboard.set", "more".getBytes(), () -> "no".getBytes()).state)) {
                throw new AssertionError("young cap");
            }
            release.countDown();
            awaitState(store, mac, id(91), "complete");
            awaitState(store, mac, id(92), "complete");
            clock.now = LinkReceipts.EVICT_MS + LinkReceipts.EVICT_MS;
            store.submit(mac, id(94), "clipboard.set", "next".getBytes(), () -> "next".getBytes());
            LinkReceipts.Receipt kept = awaitState(store, mac, id(94), "complete");
            if (!"not_found".equals(store.query(mac, id(91)).state) || !"not_found".equals(store.query(mac, id(92)).state) || kept.body == null) {
                throw new AssertionError("evict");
            }
            Files.delete(root.resolve("count").resolve(mac + "." + id(94) + ".rec"));
            LinkReceipts.Receipt indexed = store.query(mac, id(94));
            if (indexed.body == null || !"next".equals(new String(indexed.body))) {
                throw new AssertionError("index");
            }
        }
        Manual ages = new Manual();
        try (LinkReceipts store = new LinkReceipts(root.resolve("budget"), ages, LinkReceipts.MAX_RECEIPTS, 10, ignored -> { }, null)) {
            store.submit(id(95), id(96), "clipboard.set", "old".getBytes(), () -> "12345".getBytes());
            awaitState(store, id(95), id(96), "complete");
            ages.now = 5;
            store.submit(id(97), id(98), "clipboard.set", "new".getBytes(), () -> "12345678".getBytes());
            awaitState(store, id(97), id(98), "complete");
            LinkReceipts.Receipt older = store.query(id(95), id(96));
            LinkReceipts.Receipt newer = store.query(id(97), id(98));
            if (older.body != null || older.resultKept || newer.body == null || newer.body.length != 8) {
                throw new AssertionError("budget");
            }
        }
    }

    private static void readOnlyOccupiesSlot(Path root) throws Exception {
        try (LinkReceipts store = new LinkReceipts(root, new Manual())) {
            String mac = id(100);
            AtomicInteger inside = new AtomicInteger();
            CountDownLatch release = new CountDownLatch(1);
            AtomicReference<Throwable> error = new AtomicReference<Throwable>();
            Thread[] threads = new Thread[LinkReceipts.IN_FLIGHT];
            for (int index = 0; index < threads.length; index++) {
                int number = index;
                threads[index] = new Thread(() -> {
                    try {
                        store.submit(mac, id(110 + number), "device.status", "s".getBytes(), () -> {
                            inside.incrementAndGet();
                            release.await();
                            return "ok".getBytes();
                        });
                    } catch (Throwable failure) {
                        error.set(failure);
                    }
                });
                threads[index].start();
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (inside.get() < 4 && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            if (inside.get() != 4) {
                release.countDown();
                throw new AssertionError("lane " + inside.get());
            }
            Thread.sleep(200);
            AtomicReference<LinkReceipts.Receipt> ninth = new AtomicReference<LinkReceipts.Receipt>();
            Thread extra = new Thread(() -> {
                try {
                    ninth.set(store.submit(mac, id(130), "device.status", "n".getBytes(), () -> {
                        inside.incrementAndGet();
                        release.await();
                        return "no".getBytes();
                    }));
                } catch (Throwable failure) {
                    error.set(failure);
                }
            });
            extra.start();
            long busyDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (ninth.get() == null && error.get() == null && System.nanoTime() < busyDeadline) {
                Thread.sleep(10);
            }
            if (count(root, ".rec") != 0 || ninth.get() == null || !"busy".equals(ninth.get().state) || inside.get() != 4
                    || !"not_found".equals(store.query(mac, id(131)).state) || !"not_found".equals(store.cancel(mac, id(132)).state)) {
                release.countDown();
                throw new AssertionError("readonly slot");
            }
            release.countDown();
            extra.join(5000);
            for (Thread thread : threads) {
                thread.join(5000);
            }
            if (error.get() != null) {
                throw new AssertionError(error.get());
            }
            String fileMac = id(140);
            AtomicInteger fileInside = new AtomicInteger();
            CountDownLatch fileRelease = new CountDownLatch(1);
            CountDownLatch fileEntered = new CountDownLatch(2);
            for (int index = 0; index < 2; index++) {
                int number = index;
                new Thread(() -> {
                    try {
                        store.submit(fileMac, id(141 + number), "file.stat", "p".getBytes(), () -> {
                            fileInside.incrementAndGet();
                            fileEntered.countDown();
                            fileRelease.await();
                            return "stat".getBytes();
                        });
                    } catch (Throwable failure) {
                        error.set(failure);
                    }
                }).start();
            }
            if (!fileEntered.await(5, TimeUnit.SECONDS)) {
                fileRelease.countDown();
                throw new AssertionError("file lane");
            }
            AtomicInteger third = new AtomicInteger();
            Thread waiting = new Thread(() -> {
                try {
                    store.submit(fileMac, id(143), "file.stat", "p".getBytes(), () -> {
                        third.incrementAndGet();
                        fileRelease.await();
                        return "stat".getBytes();
                    });
                } catch (Throwable failure) {
                    error.set(failure);
                }
            });
            waiting.start();
            Thread.sleep(200);
            if (third.get() != 0 || fileInside.get() != 2) {
                fileRelease.countDown();
                throw new AssertionError("file lane entered");
            }
            fileRelease.countDown();
            waiting.join(5000);
            if (error.get() != null || third.get() != 1) {
                throw new AssertionError("file lane done");
            }
        }
    }

    private static void privilegedExpiresWithoutWaiting(Path root) throws Exception {
        Manual clock = new Manual();
        try (LinkReceipts store = new LinkReceipts(root, clock)) {
            String mac = id(150);
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger runs = new AtomicInteger();
            store.submit(mac, id(151), "shell.run", "first".getBytes(), () -> {
                runs.incrementAndGet();
                entered.countDown();
                release.await();
                return "first".getBytes();
            });
            if (!entered.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("holder");
            }
            store.submit(mac, id(152), "shell.run", "second".getBytes(), () -> {
                runs.incrementAndGet();
                return "second".getBytes();
            });
            clock.now = LinkReceipts.PRIVILEGED_QUEUE_MS + 1;
            LinkReceipts.Receipt waiting = store.query(mac, id(152));
            if (!"expired".equals(waiting.state) || runs.get() != 1) {
                release.countDown();
                throw new AssertionError("sweep " + runs.get());
            }
            release.countDown();
            LinkReceipts.Receipt first = awaitState(store, mac, id(151), "complete");
            LinkReceipts.Receipt second = store.query(mac, id(152));
            if (!first.applied || second.applied || !"expired".equals(second.state) || runs.get() != 1) {
                throw new AssertionError("expire without waiting");
            }
        }
    }

    private static void finishedAge(Path root) throws Exception {
        Manual clock = new Manual();
        try (LinkReceipts store = new LinkReceipts(root, clock)) {
            String mac = id(160);
            String op = id(161);
            store.submit(mac, op, "clipboard.set", "age".getBytes(), () -> {
                clock.now = LinkReceipts.BODY_MS;
                return "kept".getBytes();
            });
            LinkReceipts.Receipt fresh = awaitState(store, mac, op, "complete");
            if (fresh.body == null || !"kept".equals(new String(fresh.body, java.nio.charset.StandardCharsets.UTF_8))) {
                throw new AssertionError("finished age kept");
            }
            clock.now = LinkReceipts.BODY_MS + LinkReceipts.BODY_MS;
            LinkReceipts.Receipt dropped = store.query(mac, op);
            if (dropped.body != null || !"complete".equals(dropped.state)) {
                throw new AssertionError("finished age dropped");
            }
        }
    }

    private static void corruptReceipt(Path root) throws Exception {
        Files.createDirectories(root);
        String mac = id(162);
        String op = id(163);
        Files.writeString(root.resolve(mac + "." + op + ".rec"), "not-a-receipt");
        Files.writeString(root.resolve(".pending-left.tmp"), "temp");
        try (LinkReceipts store = new LinkReceipts(root, new Manual())) {
            LinkReceipts.Receipt unknown = store.query(mac, op);
            if (!"unknown".equals(unknown.state) || unknown.body != null) {
                throw new AssertionError("corrupt " + unknown.state);
            }
            if (Files.exists(root.resolve(".pending-left.tmp"))) {
                throw new AssertionError("temp");
            }
            if (!Files.isRegularFile(root.resolve("corrupt").resolve(mac + "." + op + ".rec"))) {
                throw new AssertionError("quarantine");
            }
        }
        try (LinkReceipts again = new LinkReceipts(root, new Manual())) {
            if (!"unknown".equals(again.query(mac, op).state)) {
                throw new AssertionError("corrupt restart");
            }
        }
    }

    private static void replacedEpoch(Path root) throws Exception {
        Files.createDirectories(root);
        Files.writeString(root.resolve("epoch"), "not-hex");
        String mac = id(164);
        String op = id(165);
        String line = String.join("\t", "v1", mac, op, "clipboard.set", "ab", "complete", "1", "boot-1", "0", "0", "YQ==");
        Files.writeString(root.resolve(mac + "." + op + ".rec"), line);
        try (LinkReceipts store = new LinkReceipts(root, new Manual())) {
            if (store.epoch().length() != 32 || "not-hex".equals(store.epoch())) {
                throw new AssertionError("epoch replaced");
            }
            LinkReceipts.Receipt kept = store.query(mac, op);
            if (!"complete".equals(kept.state) || kept.body == null || kept.body.length != 1) {
                throw new AssertionError("epoch receipt");
            }
        }
    }

    private static void cancelQueued(LinkReceipts store) throws Exception {
        String mac = id(41);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger runs = new AtomicInteger();
        store.submit(mac, id(42), "shell.run", "hold".getBytes(), () -> {
            runs.incrementAndGet();
            entered.countDown();
            release.await();
            return "held".getBytes();
        });
        if (!entered.await(5, TimeUnit.SECONDS)) {
            throw new AssertionError("cancel hold");
        }
        store.submit(mac, id(43), "shell.run", "wait".getBytes(), () -> {
            runs.incrementAndGet();
            return "ran".getBytes();
        });
        if (!"canceled".equals(store.cancel(mac, id(43)).state)) {
            throw new AssertionError("cancel");
        }
        release.countDown();
        awaitState(store, mac, id(42), "complete");
        if (!"canceled".equals(store.query(mac, id(43)).state) || runs.get() != 1) {
            throw new AssertionError("cancel ran");
        }
    }

    private static void privilegedCap(LinkReceipts store) throws Exception {
        String mac = id(44);
        CountDownLatch entered = new CountDownLatch(1);
        store.submit(mac, id(45), "install.run", "hold".getBytes(), () -> {
            entered.countDown();
            new CountDownLatch(1).await();
            return "held".getBytes();
        });
        if (!entered.await(5, TimeUnit.SECONDS)) {
            throw new AssertionError("cap hold");
        }
        for (int i = 0; i < 4; i++) {
            if (!"queued".equals(store.submit(mac, id(46 + i), "install.run", "q".getBytes(), () -> "q".getBytes()).state)) {
                throw new AssertionError("cap queue");
            }
        }
        if (!"busy".equals(store.submit(mac, id(51), "install.run", "x".getBytes(), () -> "x".getBytes()).state)
                || !"not_found".equals(store.query(mac, id(51)).state)) {
            throw new AssertionError("cap");
        }
    }

    private static LinkReceipts.Receipt awaitState(LinkReceipts store, String mac, String op, String state) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        LinkReceipts.Receipt receipt = null;
        while (System.nanoTime() < deadline) {
            receipt = store.query(mac, op);
            if (state.equals(receipt.state)) {
                return receipt;
            }
            Thread.sleep(10);
        }
        throw new AssertionError(receipt == null ? "missing" : receipt.state);
    }

    private static int count(Path root, String suffix) throws Exception {
        int found = 0;
        try (var entries = Files.newDirectoryStream(root)) {
            for (Path file : entries) {
                if (file.getFileName().toString().endsWith(suffix)) {
                    found++;
                }
            }
        }
        return found;
    }

    private static String id(int value) {
        return String.format("%032x", value);
    }

    private static void delete(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (var entries = Files.newDirectoryStream(root)) {
            for (Path file : entries) {
                if (Files.isDirectory(file)) {
                    delete(file);
                } else {
                    Files.deleteIfExists(file);
                }
            }
        }
        Files.deleteIfExists(root);
    }

    private static final class Manual implements LinkReceipts.Clock {
        long now;
        public long elapsedMillis() { return now; }
        public String bootId() { return "boot-1"; }
    }
}
