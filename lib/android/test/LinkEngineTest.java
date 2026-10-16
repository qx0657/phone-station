package dev.phonestation.adbkeep;

import dev.phonestation.adbkeep.link.LinkEngine;
import dev.phonestation.adbkeep.link.LinkFrame;
import dev.phonestation.adbkeep.link.LinkMux;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Go 与 Java 读同一份链路引擎用例。 */
final class LinkEngineTest {
    public static void main(String[] args) throws Exception {
        Json file = Json.parse(Files.readString(cases()));
        Set<String> need = new HashSet<>(List.of(
                "out-of-order", "interleaved", "window-send", "window-grant", "window-receive", "reset", "slow-receiver"));
        Set<String> seen = new HashSet<>();
        for (Json item : file.get("cases").array()) {
            String name = item.get("name").string();
            if (need.contains(name)) {
                seen.add(name);
            }
            runCase(name, item);
        }
        if (!seen.equals(need)) {
            throw new AssertionError("missing " + need);
        }
        delivers();
        stopsOnControlSeq();
        System.out.println("link engine ok");
    }

    private static void runCase(String name, Json item) {
        boolean muxed = item.get("mux") != null && item.get("mux").boolValue();
        int sessions = muxed ? 2 : 1;
        LinkEngine[] engines = new LinkEngine[sessions];
        for (int i = 0; i < sessions; i++) {
            engines[i] = new LinkEngine(
                    item.get("fromMac").boolValue(),
                    count(item, "buffer"),
                    count(item, "outbound"),
                    count(item, "maxMessage"),
                    0L);
            engines[i].setFrameLimit(count(item, "frame"));
            engines[i].setInboxLimit(count(item, "inbox"));
        }
        LinkMux link = new LinkMux(0L, engines);
        List<Json> steps = item.get("steps").array();
        for (int i = 0; i < steps.size(); i++) {
            Json step = steps.get(i);
            String op = step.get("op").string();
            int session = (int) number(step, "session");
            if (session < 0 || session >= engines.length) {
                throw new AssertionError(name + " step " + i + " session");
            }
            try {
                runStep(engines[session], link, step);
            } catch (AssertionError error) {
                throw new AssertionError(name + " step " + i + " " + op + ": " + error.getMessage());
            } catch (RuntimeException error) {
                throw new AssertionError(name + " step " + i + " " + op + ": " + error.getMessage());
            }
        }
    }

    private static void runStep(LinkEngine endpoint, LinkMux link, Json step) {
        String op = step.get("op").string();
        String want = text(step, "error");
        if ("out".equals(op)) {
            matchOut(link, step);
            return;
        }
        if ("inbox".equals(op)) {
            matchInbox(endpoint, step);
            return;
        }
        String got = "";
        try {
            switch (op) {
                case "open":
                    endpoint.open(number(step, "channel"));
                    break;
                case "write":
                    endpoint.send((int) number(step, "priority"), frame(step));
                    break;
                case "message":
                    endpoint.sendMessage((int) number(step, "priority"), number(step, "channel"),
                            (int) number(step, "kind"), hex(text(step, "payload")));
                    break;
                case "peer":
                    endpoint.setPeer(hex(text(step, "payload")));
                    break;
                case "writable":
                    if (endpoint.takeWritable() != number(step, "n")) {
                        throw new AssertionError("writable != count");
                    }
                    break;
                case "in":
                    endpoint.accept(frame(step));
                    break;
                case "consume":
                    endpoint.consume(number(step, "channel"), (int) number(step, "n"));
                    break;
                case "tick":
                    link.advance(number(step, "ms"));
                    break;
                default:
                    throw new AssertionError(op);
            }
        } catch (IllegalArgumentException error) {
            got = error.getMessage();
        }
        if (!want.equals(got)) {
            throw new AssertionError(want + " != " + got);
        }
    }

    private static void matchOut(LinkMux link, Json step) {
        List<Json> frames = array(step, "frames");
        for (Json want : frames) {
            LinkEngine.Frame got = link.pull();
            if (got == null) {
                throw new AssertionError("empty");
            }
            byte[] peer = text(want, "peer").isEmpty() ? new byte[16] : hex(text(want, "peer"));
            if (got.kind != number(want, "kind")
                    || got.flags != number(want, "flags")
                    || got.channel != number(want, "channel")
                    || got.seq != number(want, "seq")
                    || !Arrays.equals(got.payload, hex(text(want, "payload")))
                    || !Arrays.equals(got.peer, peer)) {
                throw new AssertionError("mismatch");
            }
        }
        if (link.pull() != null) {
            throw new AssertionError("extra");
        }
    }

    private static void matchInbox(LinkEngine endpoint, Json step) {
        List<Json> items = array(step, "items");
        List<LinkEngine.Chunk> got = endpoint.inbox();
        if (got.size() != items.size()) {
            throw new AssertionError("len");
        }
        for (int i = 0; i < items.size(); i++) {
            Json want = items.get(i);
            boolean end = want.get("end") != null && want.get("end").boolValue();
            if (got.get(i).channel != number(want, "channel")
                    || got.get(i).end != end
                    || !Arrays.equals(got.get(i).payload, hex(text(want, "payload")))) {
                throw new AssertionError("chunk");
            }
        }
    }

    private static void delivers() throws Exception {
        ArrayBlockingQueue<LinkEngine.Frame> macToPhone = new ArrayBlockingQueue<>(4);
        ArrayBlockingQueue<LinkEngine.Frame> phoneToMac = new ArrayBlockingQueue<>(4);
        long now = System.currentTimeMillis();
        LinkEngine mac = new LinkEngine(true, 32, 64, 0, now);
        LinkEngine phone = new LinkEngine(false, 32, 64, 0, now);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Thread> threads = new ArrayList<>();
        threads.add(serve(mac, new Pipe(phoneToMac, macToPhone), failure));
        threads.add(serve(phone, new Pipe(macToPhone, phoneToMac), failure));
        try {
            mac.open(1);
            mac.send(3, new LinkEngine.Frame(LinkFrame.KIND_DATA, 0x02, 1, 0, "hi".getBytes()));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            LinkEngine.Chunk got = null;
            while (System.nanoTime() < deadline) {
                List<LinkEngine.Chunk> items = phone.inbox();
                if (items.size() == 1) {
                    got = items.get(0);
                    break;
                }
                Thread.sleep(5);
            }
            if (got == null || got.channel != 1 || !got.end || !Arrays.equals(got.payload, "hi".getBytes())) {
                throw new AssertionError("delivered");
            }
        } finally {
            mac.close();
            phone.close();
            for (Thread thread : threads) {
                thread.join(2000);
                if (thread.isAlive()) {
                    throw new AssertionError("serve did not stop");
                }
            }
        }
        if (failure.get() != null) {
            throw new AssertionError("serve " + failure.get().getMessage());
        }
    }

    private static void stopsOnControlSeq() throws Exception {
        ArrayBlockingQueue<LinkEngine.Frame> inbound = new ArrayBlockingQueue<>(1);
        ArrayBlockingQueue<LinkEngine.Frame> outbound = new ArrayBlockingQueue<>(1);
        LinkEngine endpoint = new LinkEngine(true, 32, 64, 0, System.currentTimeMillis());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = serve(endpoint, new Pipe(inbound, outbound), failure);
        inbound.put(new LinkEngine.Frame(LinkFrame.KIND_MESSAGE, 0, 0, 1, "{}".getBytes()));
        thread.join(2000);
        endpoint.close();
        if (thread.isAlive()) {
            throw new AssertionError("serve kept a broken session");
        }
        Throwable error = failure.get();
        if (error == null || !"seq".equals(error.getMessage())) {
            throw new AssertionError("serve error " + (error == null ? "" : error.getMessage()));
        }
    }

    private static Thread serve(LinkEngine endpoint, Pipe pipe, AtomicReference<Throwable> failure) {
        Thread thread = new Thread(() -> {
            try {
                endpoint.serve(pipe, System::currentTimeMillis);
            } catch (Throwable error) {
                failure.compareAndSet(null, error);
            }
        });
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static LinkEngine.Frame frame(Json step) {
        return new LinkEngine.Frame(
                (int) number(step, "kind"),
                (int) number(step, "flags"),
                number(step, "channel"),
                number(step, "seq"),
                hex(text(step, "payload")));
    }

    private static List<Json> array(Json item, String key) {
        Json value = item.get(key);
        return value == null ? List.of() : value.array();
    }

    private static int count(Json item, String key) {
        long value = number(item, key);
        if (value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("limit");
        }
        return (int) value;
    }

    private static long number(Json item, String key) {
        Json value = item.get(key);
        return value == null ? 0 : value.longValue();
    }

    private static String text(Json item, String key) {
        Json value = item.get(key);
        return value == null ? "" : value.string();
    }

    private static boolean zeros(byte[] peer) {
        for (byte bit : peer) {
            if (bit != 0) {
                return false;
            }
        }
        return peer.length == 16;
    }

    private static byte[] hex(String value) {
        if ((value.length() & 1) != 0) {
            throw new IllegalArgumentException("hex");
        }
        byte[] out = new byte[value.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static Path cases() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path file = dir.resolve("lib/link/testdata/engine.json");
            if (Files.isRegularFile(file)) {
                return file;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("找不到链路引擎用例");
    }

    private static final class Pipe implements LinkEngine.Conn {
        private final ArrayBlockingQueue<LinkEngine.Frame> in;
        private final ArrayBlockingQueue<LinkEngine.Frame> out;

        Pipe(ArrayBlockingQueue<LinkEngine.Frame> in, ArrayBlockingQueue<LinkEngine.Frame> out) {
            this.in = in;
            this.out = out;
        }

        public LinkEngine.Frame read() throws InterruptedException {
            return in.take();
        }

        public void write(LinkEngine.Frame frame) throws InterruptedException {
            out.put(frame);
        }
    }
}
