package dev.phonestation.adbkeep;

import dev.phonestation.adbkeep.link.LinkFlow;
import dev.phonestation.adbkeep.link.LinkFrame;
import java.util.Arrays;

/** 链路库的分片、流控、优先级、心跳和拒绝。 */
final class LinkFlowTest {
    public static void main(String[] args) {
        LinkFlow.Assembly assembly = new LinkFlow.Assembly();
        if (assembly.add(0x04, new byte[] {'a', 'b'}) != null) {
            throw new AssertionError("first fragment");
        }
        LinkFlow.Result joined = assembly.add(0x02, new byte[] {'c', 'd'});
        if (joined == null || !joined.end || !Arrays.equals(joined.payload, new byte[] {'a', 'b', 'c', 'd'})) {
            throw new AssertionError("joined");
        }
        LinkFlow.Result next = assembly.add(0, new byte[] {'z'});
        if (next == null || next.end || !Arrays.equals(next.payload, new byte[] {'z'})) {
            throw new AssertionError("next message");
        }
        byte[] chunk = new byte[LinkFrame.MAIN_PAYLOAD];
        Arrays.fill(chunk, (byte) 1);
        for (int i = 0; i < LinkFlow.MAX_MESSAGE / LinkFrame.MAIN_PAYLOAD; i++) {
            if (assembly.add(0x04, chunk) != null) {
                throw new AssertionError("fill");
            }
        }
        try {
            assembly.add(0, new byte[] {2});
            throw new AssertionError("oversize message");
        } catch (IllegalArgumentException error) {
            if (!"limit".equals(error.getMessage())) {
                throw new AssertionError("oversize message");
            }
        }

        LinkFlow.Window window = new LinkFlow.Window();
        if (window.credit != LinkFlow.INITIAL_WINDOW) {
            throw new AssertionError("initial window");
        }
        window.take(LinkFlow.INITIAL_WINDOW);
        try {
            window.take(1);
            throw new AssertionError("over window");
        } catch (IllegalArgumentException error) {
            if (!"window".equals(error.getMessage())) {
                throw new AssertionError("over window");
            }
        }
        window.grant(new byte[] {0, 0, 0, 4});
        if (window.credit != 4) {
            throw new AssertionError("grant");
        }
        window.take(4);
        for (int i = 0; i < LinkFlow.MAX_STREAMS; i++) {
            LinkFlow.openStream(i, i * 2L + 1L, true);
        }
        expect("streams", () -> LinkFlow.openStream(LinkFlow.MAX_STREAMS, 17, true));
        expect("channel", () -> LinkFlow.openStream(0, 2, true));
        expect("channel", () -> LinkFlow.openStream(0, 1, false));

        LinkFlow.Scheduler scheduler = new LinkFlow.Scheduler();
        byte[] peer = new byte[16];
        peer[0] = 1;
        scheduler.push(LinkFlow.PRIORITY_FILE, LinkFrame.KIND_DATA, 0, peer, 1, 1, new byte[] {'f'}, LinkFrame.MAIN_PAYLOAD);
        scheduler.push(LinkFlow.PRIORITY_TERMINAL, LinkFrame.KIND_DATA, 0, peer, 3, 2, new byte[] {'t'}, LinkFrame.MAIN_PAYLOAD);
        scheduler.push(LinkFlow.PRIORITY_CONTROL, LinkFrame.KIND_MESSAGE, 0, new byte[16], 0, 3, new byte[] {'{', '}'}, LinkFrame.MAIN_PAYLOAD);
        scheduler.push(LinkFlow.PRIORITY_LINK, LinkFrame.KIND_LINK, 0, new byte[16], 0, 0, new byte[] {'p'}, LinkFrame.MAIN_PAYLOAD);
        int[] order = new int[4];
        for (int i = 0; i < order.length; i++) {
            LinkFrame frame = scheduler.pop();
            if (frame == null) {
                throw new AssertionError("missing");
            }
            order[i] = frame.kind;
        }
        if (order[0] != LinkFrame.KIND_LINK || order[1] != LinkFrame.KIND_MESSAGE
                || order[2] != LinkFrame.KIND_DATA || order[3] != LinkFrame.KIND_DATA || scheduler.pop() != null) {
            throw new AssertionError("order");
        }
        try {
            scheduler.push(LinkFlow.PRIORITY_FILE, LinkFrame.KIND_DATA, 0, peer, 1, 1, new byte[LinkFrame.MAIN_PAYLOAD + 1], LinkFrame.MAIN_PAYLOAD);
            throw new AssertionError("frame limit");
        } catch (IllegalArgumentException error) {
            if (!"limit".equals(error.getMessage())) {
                throw new AssertionError("frame limit");
            }
        }
        try {
            LinkFrame.decode(new byte[] {1}, LinkFrame.MAIN_PAYLOAD);
            throw new AssertionError("truncated");
        } catch (IllegalArgumentException error) {
            if (!"truncated".equals(error.getMessage())) {
                throw new AssertionError("truncated");
            }
        }
        byte[] bad = new byte[LinkFrame.HEADER];
        bad[0] = 1;
        try {
            LinkFrame.decode(bad, LinkFrame.MAIN_PAYLOAD);
            throw new AssertionError("version");
        } catch (IllegalArgumentException error) {
            if (!"version".equals(error.getMessage())) {
                throw new AssertionError("version");
            }
        }

        long start = 1_700_000_000_000L;
        LinkFlow.Beat quiet = LinkFlow.heartbeat(start, start, start + 19_000L);
        LinkFlow.Beat ping = LinkFlow.heartbeat(start, start, start + LinkFlow.HEARTBEAT_EVERY_MS);
        LinkFlow.Beat dead = LinkFlow.heartbeat(start, start, start + LinkFlow.HEARTBEAT_TIMEOUT_MS);
        LinkFlow.Beat step = LinkFlow.heartbeat(start, start, start - 1_000L);
        if (quiet.send || quiet.dead || !ping.send || ping.dead || dead.send || !dead.dead || step.send || step.dead) {
            throw new AssertionError("heartbeat");
        }
        System.out.println("link flow ok");
    }

    private static void expect(String message, Runnable action) {
        try {
            action.run();
            throw new AssertionError(message);
        } catch (IllegalArgumentException error) {
            if (!message.equals(error.getMessage())) {
                throw new AssertionError(message);
            }
        }
    }
}
