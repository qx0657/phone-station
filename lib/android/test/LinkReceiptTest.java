package dev.phonestation.adbkeep;

import dev.phonestation.adbkeep.link.LinkReceipts;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
                bodyExpires(store, later);
                cancelQueued(store);
                privilegedCap(store);
            }
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

    private static void bodyExpires(LinkReceipts store, Manual clock) throws Exception {
        String mac = id(18);
        String op = id(19);
        store.submit(mac, op, "clipboard.set", "keep".getBytes(), () -> "body".getBytes());
        awaitState(store, mac, op, "complete");
        clock.now = LinkReceipts.BODY_MS;
        if (store.query(mac, op).body != null || !"complete".equals(store.query(mac, op).state)) {
            throw new AssertionError("body age");
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
