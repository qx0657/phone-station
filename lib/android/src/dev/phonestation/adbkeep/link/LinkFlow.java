package dev.phonestation.adbkeep.link;

import java.util.ArrayDeque;
import java.util.Arrays;

/** 分片、流控、优先级和心跳。不接入现有通道。 */
public final class LinkFlow {
    public static final int MAX_MESSAGE = 1 << 20;
    public static final int MAX_STREAMS = 8;
    public static final int INITIAL_WINDOW = 128 << 10;
    public static final long HEARTBEAT_EVERY_MS = 20_000L;
    public static final long HEARTBEAT_TIMEOUT_MS = 45_000L;
    public static final int PRIORITY_LINK = 0;
    public static final int PRIORITY_CONTROL = 1;
    public static final int PRIORITY_TERMINAL = 2;
    public static final int PRIORITY_FILE = 3;

    private LinkFlow() {}

    public static final class Assembly {
        private byte[] buf = new byte[0];

        public Result add(int flags, byte[] payload) {
            if (buf.length + payload.length > MAX_MESSAGE) {
                buf = new byte[0];
                throw new IllegalArgumentException("limit");
            }
            byte[] next = Arrays.copyOf(buf, buf.length + payload.length);
            System.arraycopy(payload, 0, next, buf.length, payload.length);
            if ((flags & 0x04) != 0) {
                buf = next;
                return null;
            }
            buf = new byte[0];
            return new Result(next, (flags & 0x02) != 0);
        }
    }

    public static final class Result {
        public final byte[] payload;
        public final boolean end;

        Result(byte[] payload, boolean end) {
            this.payload = payload;
            this.end = end;
        }
    }

    public static final class Window {
        public int credit = INITIAL_WINDOW;

        public void take(int n) {
            if (n < 0 || n > credit) {
                throw new IllegalArgumentException("window");
            }
            credit -= n;
        }

        public void grant(byte[] payload) {
            if (payload.length != 4) {
                throw new IllegalArgumentException("credit");
            }
            long amount = ((payload[0] & 0xffL) << 24) | ((payload[1] & 0xffL) << 16)
                    | ((payload[2] & 0xffL) << 8) | (payload[3] & 0xffL);
            if (amount > (1L << 30)) {
                throw new IllegalArgumentException("window");
            }
            credit += (int) amount;
        }
    }

    public static void openStream(int open, long channel, boolean fromMac) {
        if (open >= MAX_STREAMS) {
            throw new IllegalArgumentException("streams");
        }
        if (channel == 0 || ((channel % 2L) == 1L) != fromMac) {
            throw new IllegalArgumentException("channel");
        }
    }

    public static final class Scheduler {
        private final ArrayDeque<LinkFrame>[] queues = queues();

        public void push(int priority, int kind, int flags, byte[] peer, long channel, long seq, byte[] payload, int limit) {
            if (priority < PRIORITY_LINK || priority > PRIORITY_FILE) {
                throw new IllegalArgumentException("priority");
            }
            byte[] encoded = LinkFrame.encode(kind, flags, peer, channel, seq, payload, limit);
            queues[priority].add(LinkFrame.decode(encoded, limit));
        }

        public LinkFrame pop() {
            for (ArrayDeque<LinkFrame> queue : queues) {
                LinkFrame frame = queue.poll();
                if (frame != null) {
                    return frame;
                }
            }
            return null;
        }

        @SuppressWarnings("unchecked")
        private static ArrayDeque<LinkFrame>[] queues() {
            ArrayDeque<LinkFrame>[] out = new ArrayDeque[4];
            for (int i = 0; i < out.length; i++) {
                out[i] = new ArrayDeque<>();
            }
            return out;
        }
    }

    public static final class Beat {
        public final boolean send;
        public final boolean dead;

        Beat(boolean send, boolean dead) {
            this.send = send;
            this.dead = dead;
        }
    }

    public static Beat heartbeat(long lastInboundMs, long lastOutboundMs, long nowMs) {
        if (nowMs < lastInboundMs || nowMs < lastOutboundMs) {
            return new Beat(false, false);
        }
        if (nowMs - lastInboundMs >= HEARTBEAT_TIMEOUT_MS) {
            return new Beat(false, true);
        }
        return new Beat(nowMs - lastOutboundMs >= HEARTBEAT_EVERY_MS, false);
    }
}
