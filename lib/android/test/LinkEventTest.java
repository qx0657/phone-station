package dev.phonestation.adbkeep;

import dev.phonestation.adbkeep.link.LinkEvents;
import dev.phonestation.adbkeep.link.LinkReceipts;
import dev.phonestation.adbkeep.link.LinkRoute;
import dev.phonestation.adbkeep.link.LocalLink;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** 事件订阅、合并、游标和通道选择。不连接正在运行的手机服务。 */
final class LinkEventTest {
    private static final String MAC_A = "0123456789abcdef0123456789abcdef";
    private static final String MAC_B = "fedcba9876543210fedcba9876543210";
    private static final String OP_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String OP_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String SLOW = "cccccccccccccccccccccccccccccccc";

    public static void main(String[] args) throws Exception {
        coalesce();
        grants();
        ordered();
        broadcast();
        states();
        route();
        Path root = Files.createTempDirectory("link-events");
        try {
            topics(root);
            running(root);
        } finally {
            delete(root);
        }
        System.out.println("link events ok");
    }

    private static void coalesce() {
        Manual clock = new Manual();
        LinkEvents events = LinkEvents.manual(clock);
        Sink sink = new Sink();
        events.subscribe("s", MAC_A, List.of("device", "ops"), Map.of(), sink);
        events.publishState("device", null, bytes("one"));
        events.publishState("device", null, bytes("two"));
        events.publishOp(MAC_A, OP_A, "queued", null);
        events.publishOp(MAC_A, OP_A, "running", null);
        events.publishOp(MAC_A, OP_B, "queued", null);
        if (events.deliver(999) != 0 || !sink.messages.isEmpty()) {
            throw new AssertionError("early");
        }
        if (events.deliver(1000) != 3) {
            throw new AssertionError("deliver");
        }
        String all = texts(sink.messages);
        if (!all.contains("\"topic\":\"device\"") || !all.contains(encode("two")) || all.contains(encode("one"))) {
            throw new AssertionError("device");
        }
        if (!all.contains("\"op\":\"" + OP_A + "\"") || !all.contains("\"state\":\"running\"")) {
            throw new AssertionError("op");
        }
        if (!all.contains("\"op\":\"" + OP_B + "\"") || count(all, "\"state\":\"queued\"") != 1) {
            throw new AssertionError("other");
        }
        sink.messages.clear();
        List<byte[]> again = events.subscribe("s2", MAC_A, List.of("device"), Map.of(), new Sink());
        if (texts(again).indexOf(encode("two")) < 0) {
            throw new AssertionError("snapshot");
        }
        events.close();
    }

    private static void grants() {
        LinkEvents events = LinkEvents.manual(new Manual());
        Sink first = new Sink();
        Sink second = new Sink();
        events.subscribe("a", MAC_A, List.of("grants"), Map.of(), first);
        events.subscribe("b", MAC_A, List.of("grants"), Map.of(), second);
        events.publishState("grants", MAC_B, bytes("other"));
        events.publishState("grants", MAC_A, bytes("office"));
        events.deliver(1000);
        if (first.messages.size() != 1 || second.messages.size() != 1 || texts(first.messages).contains(encode("other"))) {
            throw new AssertionError("grants");
        }
        events.subscribe("a", MAC_A, List.of("ops"), Map.of(), first);
        first.messages.clear();
        second.messages.clear();
        events.publishState("grants", MAC_A, bytes("home"));
        events.deliver(2000);
        if (!first.messages.isEmpty() || second.messages.size() != 1) {
            throw new AssertionError("replaced");
        }
        events.close();
    }

    private static void ordered() {
        Manual clock = new Manual();
        LinkEvents events = LinkEvents.manual(clock);
        Sink live = new Sink();
        events.subscribe("live", MAC_A, List.of("clipboard"), Map.of(), live);
        if (!"1".equals(events.append("clipboard", MAC_A, bytes("old")))) {
            throw new AssertionError("cursor");
        }
        if (live.messages.size() != 1) {
            throw new AssertionError("live");
        }
        events.append("clipboard", MAC_A, bytes("new"));
        List<byte[]> replay = events.subscribe("replay", MAC_A, List.of("clipboard"), Map.of("clipboard", "1"), new Sink());
        String replayText = texts(replay);
        if (!replayText.contains("\"cursor\":\"2\"") || replayText.contains("\"type\":\"reset\"")) {
            throw new AssertionError("replay");
        }
        List<byte[]> reset = events.subscribe("reset", MAC_A, List.of("clipboard"), Map.of("clipboard", "0"), new Sink());
        if (!texts(reset).contains("\"type\":\"reset\"")) {
            throw new AssertionError("reset");
        }
        List<byte[]> bad = events.subscribe("bad", MAC_A, List.of("clipboard", "nope"), Map.of("clipboard", "00"), new Sink());
        String badText = texts(bad);
        if (!badText.contains("\"rejected\":[\"clipboard\",\"nope\"]") || !badText.contains("\"reasons\":[\"cursor\",\"unknown\"]")) {
            throw new AssertionError("rejected");
        }
        events.unbind("live");
        int before = live.messages.size();
        events.append("clipboard", MAC_A, bytes("after"));
        if (live.messages.size() != before) {
            throw new AssertionError("unbind");
        }
        clock.now = 0;
        events.append("notifications", MAC_A, bytes("n1"));
        clock.now = LinkEvents.NOTICE_HOLD_MS + 1;
        events.append("notifications", MAC_A, bytes("n2"));
        List<byte[]> notice = events.subscribe("note", MAC_A, List.of("notifications"), Map.of("notifications", "0"), new Sink());
        if (!texts(notice).contains("\"type\":\"reset\"")) {
            throw new AssertionError("notice");
        }
        events.close();
    }

    private static void broadcast() {
        LinkEvents events = LinkEvents.manual(new Manual());
        Sink first = new Sink();
        Sink second = new Sink();
        events.subscribe("a", MAC_A, List.of("clipboard"), Map.of(), first);
        events.subscribe("b", MAC_B, List.of("notifications"), Map.of(), second);
        LinkLiveEvents.attach(events);
        LinkLiveEvents.note("clipboard");
        LinkLiveEvents.note("device");
        if (first.messages.size() != 1 || !texts(first.messages).contains("\"topic\":\"clipboard\"") || !second.messages.isEmpty()) {
            throw new AssertionError("clipboard");
        }
        LinkLiveEvents.note("notifications");
        if (second.messages.size() != 1 || !texts(second.messages).contains("\"topic\":\"notifications\"")) {
            throw new AssertionError("notifications");
        }
        LinkLiveEvents.detach(events);
        LinkLiveEvents.note("clipboard");
        if (first.messages.size() != 1) {
            throw new AssertionError("detached");
        }
        events.close();
    }

    private static void states() {
        Manual clock = new Manual();
        LinkEvents events = LinkEvents.manual(clock);
        Sink first = new Sink();
        Sink second = new Sink();
        events.subscribe("a", MAC_A, List.of("device", "grants", "capabilities"), Map.of(), first);
        events.subscribe("b", MAC_B, List.of("grants", "capabilities"), Map.of(), second);
        LinkLiveEvents.attach(events);
        byte[] older = LinkSnapshots.device(40, false, "wifi", true, true, false);
        byte[] newer = LinkSnapshots.device(41, true, "wifi", true, true, false);
        LinkLiveEvents.state("device", null, older);
        LinkLiveEvents.state("device", null, newer);
        clock.now = 1000;
        int deviceSent = events.deliver(1000);
        String sent = texts(first.messages);
        if (deviceSent != 1 || !sent.contains(java.util.Base64.getEncoder().encodeToString(newer))
                || sent.contains(java.util.Base64.getEncoder().encodeToString(older)) || !second.messages.isEmpty()) {
            throw new AssertionError("device");
        }
        first.messages.clear();
        LinkLiveEvents.state("device", null, newer);
        if (events.deliver(2000) != 0) {
            throw new AssertionError("same");
        }
        byte[] withIssues = LinkSnapshots.device(41, true, "wifi", true, true, false,
                Json.arr().add(Json.obj().put("id", "shizuku").put("title", "Shizuku 未启动")
                        .put("detail", "需要 Shizuku").put("destination", "permissions")));
        String issueText = new String(withIssues, java.nio.charset.StandardCharsets.UTF_8);
        if (!issueText.contains("\"issues\"") || !issueText.contains("Shizuku 未启动")) {
            throw new AssertionError("issues");
        }
        LinkLiveEvents.state("device", null, withIssues);
        clock.now = 2000;
        if (events.deliver(2000) != 1 || !texts(first.messages).contains(java.util.Base64.getEncoder().encodeToString(withIssues))) {
            throw new AssertionError("issues push");
        }
        first.messages.clear();
        LinkLiveEvents.state("device", null, withIssues);
        if (events.deliver(2000) != 0) {
            throw new AssertionError("same issues");
        }
        LinkLiveEvents.state("grants", MAC_A, LinkSnapshots.grants(true));
        LinkLiveEvents.state("capabilities", null, LinkSnapshots.capabilities(true, true, Map.of("shell", true)));
        byte[] grant = LinkSnapshots.grants(true);
        byte[] capability = LinkSnapshots.capabilities(true, true, Map.of("shell", true));
        clock.now = 3000;
        events.deliver(3000);
        String granted = texts(first.messages);
        if (!granted.contains("\"topic\":\"grants\"") || !granted.contains(java.util.Base64.getEncoder().encodeToString(grant))
                || !granted.contains("\"topic\":\"capabilities\"") || !granted.contains(java.util.Base64.getEncoder().encodeToString(capability))) {
            throw new AssertionError("state");
        }
        String other = texts(second.messages);
        if (other.contains("\"topic\":\"grants\"") || !other.contains("\"topic\":\"capabilities\"")) {
            throw new AssertionError("scope");
        }
        boolean rejected = false;
        try {
            LinkSnapshots.device(101, false, "wifi", true, true, false);
        } catch (IllegalArgumentException error) {
            rejected = "device".equals(error.getMessage());
        }
        if (!rejected) {
            throw new AssertionError("range");
        }
        LinkLiveEvents.detach(events);
        events.close();
    }

    private static void route() {
        LinkRoute route = new LinkRoute();
        if (route.prefer() != null) {
            throw new AssertionError("down");
        }
        route.up(LinkRoute.Side.REMOTE, true);
        if (route.prefer() != LinkRoute.Side.REMOTE) {
            throw new AssertionError("remote");
        }
        route.begin(OP_A, LinkRoute.Side.REMOTE);
        route.up(LinkRoute.Side.LOCAL, true);
        if (route.prefer() != LinkRoute.Side.LOCAL || route.query(OP_A) != LinkRoute.Side.REMOTE) {
            throw new AssertionError("stay");
        }
        route.begin(OP_A, LinkRoute.Side.LOCAL);
        route.openStream(7, LinkRoute.Side.REMOTE);
        route.up(LinkRoute.Side.REMOTE, false);
        if (route.query(OP_A) != LinkRoute.Side.LOCAL || route.streamOpen(7)) {
            throw new AssertionError("move");
        }
        route.openStream(8, LinkRoute.Side.LOCAL);
        route.up(LinkRoute.Side.LOCAL, false);
        if (route.query(OP_A) != null || route.streamOpen(8) || route.prefer() != null) {
            throw new AssertionError("none");
        }
    }

    private static void topics(Path root) throws Exception {
        LocalLink.Device phoneKey = LocalLink.Device.generate();
        LocalLink.Device macKey = LocalLink.Device.generate();
        try (LinkReceipts receipts = new LinkReceipts(root.resolve("topics"), clock());
                LocalLink.Phone phone = LocalLink.Phone.listen(phoneKey, List.of(macKey), receipts, () -> new byte[0])) {
            Held held = hold(phone, macKey, phoneKey);
            try {
                LocalLink.Subscribed subscribed = held.mac.subscribe(List.of(
                        "device", "grants", "capabilities", "ops", "clipboard", "notifications", "nope"),
                        Map.of("clipboard", "0"));
                if (!subscribed.topics.equals(List.of("device", "grants", "capabilities", "ops", "clipboard", "notifications"))
                        || !subscribed.rejected.equals(List.of("nope"))
                        || !subscribed.reasons.equals(List.of("unknown"))) {
                    throw new AssertionError("topics");
                }
                if (held.mac.nextEvent(200) != null) {
                    throw new AssertionError("history");
                }
            } finally {
                held.close();
            }
        }
    }

    private static void running(Path root) throws Exception {
        Manual clock = new Manual();
        LocalLink.Device phoneKey = LocalLink.Device.generate();
        LocalLink.Device macKey = LocalLink.Device.generate();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (LinkReceipts receipts = new LinkReceipts(root.resolve("running"), clock())) {
            receipts.ensureEvents(clock);
            LocalLink.Phone phone = LocalLink.Phone.listen(phoneKey, List.of(macKey), receipts, () -> {
                started.countDown();
                if (!release.await(4, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("release");
                }
                return bytes("saved");
            });
            Held local = null;
            Held remote = null;
            try {
                local = hold(phone, macKey, phoneKey);
                remote = hold(phone, macKey, phoneKey);
                LinkRoute route = new LinkRoute();
                route.up(LinkRoute.Side.LOCAL, true);
                route.up(LinkRoute.Side.REMOTE, true);
                if (route.prefer() != LinkRoute.Side.LOCAL) {
                    throw new AssertionError("prefer");
                }
                LocalLink.Subscribed subscribed = remote.mac.subscribe(List.of("ops"), Map.of());
                if (subscribed.topics.size() != 1 || !"ops".equals(subscribed.topics.get(0))) {
                    throw new AssertionError("subscribed");
                }
                route.begin(SLOW, route.prefer());
                route.openStream(3, LinkRoute.Side.LOCAL);
                AtomicReference<Exception> error = new AtomicReference<>();
                LocalLink.Session caller = local.mac;
                Thread thread = new Thread(() -> {
                    try {
                        caller.call(SLOW, "clipboard.set", bytes("same"));
                    } catch (Exception failure) {
                        error.set(failure);
                    }
                });
                thread.start();
                if (!started.await(3, TimeUnit.SECONDS)) {
                    throw new AssertionError("started");
                }
                if (receipts.events().deliver(LinkEvents.COALESCE_MS) < 1) {
                    throw new AssertionError("deliver");
                }
                LocalLink.Event event = remote.mac.nextEvent(2000);
                if (event == null || !"running".equals(event.state) || !SLOW.equals(event.op) || !"ops".equals(event.topic)) {
                    throw new AssertionError(event == null ? "ops" : event.state);
                }
                caller.close();
                thread.join(2000);
                if (!(error.get() instanceof IllegalStateException)) {
                    throw new AssertionError("ended");
                }
                route.up(LinkRoute.Side.LOCAL, false);
                if (route.query(SLOW) != LinkRoute.Side.REMOTE || route.streamOpen(3) || route.prefer() != LinkRoute.Side.REMOTE) {
                    throw new AssertionError("route");
                }
                LocalLink.Result mid = remote.mac.query(SLOW);
                if (!"running".equals(mid.state)) {
                    throw new AssertionError(mid.state);
                }
                release.countDown();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                LocalLink.Result done = null;
                while (System.nanoTime() < deadline) {
                    done = remote.mac.query(SLOW);
                    if ("complete".equals(done.state)) {
                        break;
                    }
                    Thread.sleep(20);
                }
                if (done == null || !"complete".equals(done.state) || !"saved".equals(text(done.body))) {
                    throw new AssertionError("complete");
                }
            } finally {
                release.countDown();
                if (remote != null) {
                    remote.close();
                }
                if (local != null) {
                    local.close();
                }
                phone.close();
            }
        }
    }

    private static Held hold(LocalLink.Phone phone, LocalLink.Device macKey, LocalLink.Device phoneKey) throws Exception {
        AtomicReference<LocalLink.Session> accepted = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                accepted.set(phone.accept());
            } catch (Exception failure) {
                error.set(failure);
            }
        });
        thread.start();
        LocalLink.Session mac = LocalLink.dial(macKey, phoneKey, "127.0.0.1", phone.port());
        thread.join(5000);
        if (error.get() != null) {
            mac.close();
            throw error.get();
        }
        if (accepted.get() == null) {
            mac.close();
            throw new AssertionError("accept");
        }
        return new Held(mac, accepted.get());
    }

    private static LinkReceipts.Clock clock() {
        return new LinkReceipts.Clock() {
            public long elapsedMillis() {
                return 1;
            }

            public String bootId() {
                return "link-events";
            }
        };
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(byte[] raw) {
        return raw == null ? null : new String(raw, StandardCharsets.UTF_8);
    }

    private static String encode(String text) {
        return java.util.Base64.getEncoder().encodeToString(bytes(text));
    }

    private static String texts(List<byte[]> messages) {
        StringBuilder out = new StringBuilder();
        for (byte[] message : messages) {
            out.append(new String(message, StandardCharsets.US_ASCII)).append('\n');
        }
        return out.toString();
    }

    private static int count(String text, String needle) {
        int found = 0;
        int from = 0;
        while (from <= text.length()) {
            int at = text.indexOf(needle, from);
            if (at < 0) {
                return found;
            }
            found++;
            from = at + needle.length();
        }
        return found;
    }

    private static void delete(Path root) throws java.io.IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (java.io.IOException ignored) {
                    // 临时目录只留回执。
                }
            });
        }
    }

    private static final class Manual implements LinkEvents.Clock {
        long now;

        public long elapsedMillis() {
            return now;
        }
    }

    private static final class Sink implements LinkEvents.Sender {
        final List<byte[]> messages = new ArrayList<>();

        public void send(byte[] message) {
            messages.add(message);
        }
    }

    private static final class Held {
        final LocalLink.Session mac;
        final LocalLink.Session phone;

        Held(LocalLink.Session mac, LocalLink.Session phone) {
            this.mac = mac;
            this.phone = phone;
        }

        void close() {
            mac.close();
            phone.close();
        }
    }
}
