package dev.phonestation.adbkeep.link;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/** 帧级链路引擎。读写循环、流窗口和背压都在这里；不接入现有通道。 */
public final class LinkEngine {
    public static final int DEFAULT_OUTBOUND = 4 * LinkFlow.INITIAL_WINDOW;

    private final Object lock = new Object();
    private final Object life = new Object();
    private final boolean fromMac;
    private final int buffer;
    private final int outboundLimit;
    private final int maxMessage;
    private final ArrayDeque<Queued>[] queues = queues();
    private final HashMap<Long, Stream> streams = new HashMap<>();
    private final List<Chunk> inbox = new ArrayList<>();
    private final AtomicReference<Exception> failure = new AtomicReference<>();
    private LinkFlow.Assembly control = new LinkFlow.Assembly();
    private int controlLen;
    private int controlKind;
    private int outbound;
    private long outSeq;
    private long inSeq;
    private long lastIn;
    private long lastOut;
    private boolean broken;
    private byte[] peer = new byte[16];
    private int frameLimit = LinkFrame.MAIN_PAYLOAD;
    private int inboxLimit = 8 * 1024 * 1024;
    private int inboxBytes;
    private boolean writeBlocked;
    private int writable;
    private Codec codec;

    public interface Codec {
        Frame seal(Frame frame);

        Frame open(Frame frame);
    }
    private volatile boolean stopped;
    private volatile Thread reader;
    private volatile Thread writer;

    public LinkEngine(boolean fromMac, int buffer, int outbound, int maxMessage, long startMs) {
        if (buffer <= 0) {
            buffer = LinkFlow.INITIAL_WINDOW;
        }
        if (outbound <= 0) {
            outbound = DEFAULT_OUTBOUND;
        }
        if (maxMessage <= 0) {
            maxMessage = LinkFlow.MAX_MESSAGE;
        }
        this.fromMac = fromMac;
        this.buffer = buffer;
        this.outboundLimit = outbound;
        this.maxMessage = maxMessage;
        this.lastIn = startMs;
        this.lastOut = startMs;
    }

    public void useSeal(Codec codec) {
        synchronized (lock) {
            this.codec = codec;
        }
    }

    public Exception failed() {
        return failure.get();
    }

    public void setPeer(byte[] id) {
        if (id == null || id.length != 16) {
            throw new IllegalArgumentException("peer");
        }
        synchronized (lock) {
            peer = Arrays.copyOf(id, 16);
        }
    }

    public void setFrameLimit(int limit) {
        if (limit <= 0) {
            return;
        }
        synchronized (lock) {
            frameLimit = limit;
        }
    }

    public void setInboxLimit(int limit) {
        if (limit <= 0) {
            return;
        }
        synchronized (lock) {
            inboxLimit = limit;
        }
    }

    public int takeWritable() {
        synchronized (lock) {
            int count = writable;
            writable = 0;
            return count;
        }
    }

    public int head() {
        synchronized (lock) {
            for (int priority = 0; priority < queues.length; priority++) {
                if (!queues[priority].isEmpty()) {
                    return priority;
                }
            }
            return -1;
        }
    }

    /** 只传递帧，不绑定具体的 WebSocket 库。 */
    public interface Conn {
        Frame read() throws Exception;

        void write(Frame frame) throws Exception;
    }

    public static final class Frame {
        public final int kind;
        public final int flags;
        public final byte[] peer;
        public final long channel;
        public final long seq;
        public final byte[] payload;

        public Frame(int kind, int flags, long channel, long seq, byte[] payload) {
            this(kind, flags, null, channel, seq, payload);
        }

        public Frame(int kind, int flags, byte[] peer, long channel, long seq, byte[] payload) {
            this.kind = kind;
            this.flags = flags;
            this.peer = peer == null ? new byte[16] : Arrays.copyOf(peer, 16);
            this.channel = channel;
            this.seq = seq;
            this.payload = payload == null ? new byte[0] : payload.clone();
        }
    }

    public static final class Chunk {
        public final long channel;
        public final byte[] payload;
        public final boolean end;

        Chunk(long channel, byte[] payload, boolean end) {
            this.channel = channel;
            this.payload = payload == null ? new byte[0] : payload;
            this.end = end;
        }
    }

    public void open(long channel) {
        synchronized (lock) {
            if (streams.containsKey(channel)) {
                throw new IllegalArgumentException("channel");
            }
            LinkFlow.openStream(active(), channel, fromMac);
            streams.put(channel, newStream());
        }
    }

    public void send(int priority, Frame frame) {
        synchronized (lock) {
            boolean account = frame.kind == LinkFrame.KIND_DATA || frame.kind == LinkFrame.KIND_MESSAGE;
            Stream stream = null;
            if (frame.channel != 0) {
                stream = live(frame.channel);
                if (frame.kind != LinkFrame.KIND_DATA && frame.kind != LinkFrame.KIND_MESSAGE) {
                    throw new IllegalArgumentException("kind");
                }
                if (frame.payload.length > stream.send) {
                    throw new IllegalArgumentException("window");
                }
            } else if (frame.kind != LinkFrame.KIND_MESSAGE && frame.kind != LinkFrame.KIND_HANDSHAKE) {
                throw new IllegalArgumentException("kind");
            }
            queue(priority, frame.kind, frame.flags, frame.channel, frame.payload, account);
            if (stream != null) {
                stream.send -= frame.payload.length;
                if ((frame.flags & 0x02) != 0) {
                    stream.ended = true;
                }
            }
        }
    }

    public void sendMessage(int priority, long channel, int kind, byte[] payload) {
        synchronized (lock) {
            if (broken) {
                throw new IllegalArgumentException("link");
            }
            byte[] body = payload == null ? new byte[0] : payload;
            if (body.length > maxMessage) {
                throw new IllegalArgumentException("limit");
            }
            int limit = frameLimit <= 0 ? LinkFrame.MAIN_PAYLOAD : frameLimit;
            if (codec != null && limit > 16) {
                limit -= 16;
            }
            java.util.ArrayList<byte[]> parts = new java.util.ArrayList<>();
            java.util.ArrayList<Integer> flags = new java.util.ArrayList<>();
            if (body.length == 0) {
                parts.add(new byte[0]);
                flags.add(0x02);
            } else {
                for (int off = 0; off < body.length; ) {
                    int next = Math.min(body.length, off + limit);
                    parts.add(Arrays.copyOfRange(body, off, next));
                    flags.add(next == body.length ? 0x02 : 0x04);
                    off = next;
                }
            }
            boolean account = kind == LinkFrame.KIND_DATA || kind == LinkFrame.KIND_MESSAGE;
            int total = 0;
            if (account) {
                for (byte[] part : parts) {
                    total += part.length;
                }
                if ((long) outbound + total > outboundLimit) {
                    writeBlocked = true;
                    throw new IllegalArgumentException("backpressure");
                }
            }
            Stream stream = null;
            if (channel != 0) {
                stream = live(channel);
                if (kind != LinkFrame.KIND_DATA && kind != LinkFrame.KIND_MESSAGE) {
                    throw new IllegalArgumentException("kind");
                }
                if (total > stream.send) {
                    throw new IllegalArgumentException("window");
                }
            } else if (kind != LinkFrame.KIND_MESSAGE && kind != LinkFrame.KIND_HANDSHAKE) {
                throw new IllegalArgumentException("kind");
            }
            for (int i = 0; i < parts.size(); i++) {
                queue(priority, kind, flags.get(i), channel, parts.get(i), account);
            }
            if (stream != null) {
                stream.send -= total;
                if ((flags.get(flags.size() - 1) & 0x02) != 0) {
                    stream.ended = true;
                }
            }
        }
    }

    public void consume(long channel, int n) {
        synchronized (lock) {
            Stream stream = streams.get(channel);
            if (stream == null || stream.dead || n < 0 || n > stream.unconsumed) {
                throw new IllegalArgumentException("credit");
            }
            if (n == 0) {
                return;
            }
            stream.unconsumed -= n;
            stream.peer += n;
            byte[] payload = new byte[] {(byte) (n >>> 24), (byte) (n >>> 16), (byte) (n >>> 8), (byte) n};
            queue(LinkFlow.PRIORITY_CONTROL, LinkFrame.KIND_CREDIT, 0, channel, payload, false);
        }
    }

    public void accept(Frame frame) {
        synchronized (lock) {
            if (broken) {
                throw new IllegalArgumentException("link");
            }
            if (frame.kind == LinkFrame.KIND_LINK) {
                return;
            }
            if (frame.seq != inSeq) {
                broken = true;
                throw new IllegalArgumentException("seq");
            }
            inSeq++;
            if (codec != null && (frame.kind == LinkFrame.KIND_MESSAGE || frame.kind == LinkFrame.KIND_DATA)) {
                frame = codec.open(frame);
            }
            if (frame.channel == 0) {
                if (frame.kind == LinkFrame.KIND_MESSAGE || frame.kind == LinkFrame.KIND_HANDSHAKE) {
                    finishControl(frame);
                    return;
                }
                throw new IllegalArgumentException("channel");
            }
            if (frame.kind == LinkFrame.KIND_CREDIT) {
                grant(frame);
                return;
            }
            if (frame.kind == LinkFrame.KIND_RESET) {
                kill(frame.channel, "");
                return;
            }
            if (frame.kind == LinkFrame.KIND_DATA || frame.kind == LinkFrame.KIND_MESSAGE) {
                inbound(frame);
                return;
            }
            throw new IllegalArgumentException("kind");
        }
    }

    public List<Chunk> inbox() {
        synchronized (lock) {
            List<Chunk> out = new ArrayList<>(inbox);
            inbox.clear();
            inboxBytes = 0;
            return out;
        }
    }

    public Frame pull() {
        synchronized (lock) {
            return pullLocked();
        }
    }

    public void advance(long nowMs) {
    }

    private void beat(long nowMs) {
        synchronized (lock) {
            LinkFlow.Beat beat = LinkFlow.heartbeat(lastIn, lastOut, nowMs);
            if (beat.dead) {
                throw new IllegalArgumentException("dead");
            }
            if (!beat.send) {
                return;
            }
            queue(LinkFlow.PRIORITY_LINK, LinkFrame.KIND_LINK, 0, 0, "{\"type\":\"ping\"}".getBytes(StandardCharsets.US_ASCII), false);
            lastOut = nowMs;
        }
    }

    /**
     * 读帧、写已调度的帧，并按真实时间推进心跳。流上的协议错误不停止链路；
     * channel 0 序号错误和心跳超时会停止。close 使本次调用返回。
     */
    public void serve(Conn conn, LongSupplier clock) throws Exception {
        if (clock == null) {
            clock = System::currentTimeMillis;
        }
        LongSupplier now = clock;
        synchronized (lock) {
            long clockMs = now.getAsLong();
            lastIn = clockMs;
            lastOut = clockMs;
        }
        Thread reading = new Thread(() -> readLoop(conn, now), "link-read");
        Thread writing = new Thread(() -> writeLoop(conn, now), "link-write");
        reading.setDaemon(true);
        writing.setDaemon(true);
        reader = reading;
        writer = writing;
        reading.start();
        writing.start();
        try {
            long interval = LinkFlow.HEARTBEAT_EVERY_MS * 1_000_000L;
            long next = System.nanoTime() + interval;
            while (true) {
                synchronized (life) {
                    while (!stopped && failure.get() == null) {
                        long remaining = next - System.nanoTime();
                        if (remaining <= 0) {
                            break;
                        }
                        life.wait(Math.max(1L, remaining / 1_000_000L));
                    }
                }
                if (stopped || failure.get() != null) {
                    break;
                }
                try {
                    beat(now.getAsLong());
                } catch (IllegalArgumentException error) {
                    failure.compareAndSet(null, error);
                    close();
                    break;
                }
                next = System.nanoTime() + interval;
            }
        } finally {
            close();
            reading.join(2000);
            writing.join(2000);
        }
        Exception error = failure.get();
        if (error != null) {
            throw error;
        }
    }

    public void close() {
        synchronized (life) {
            stopped = true;
            life.notifyAll();
        }
        synchronized (lock) {
            lock.notifyAll();
        }
        Thread reading = reader;
        Thread writing = writer;
        if (reading != null) {
            reading.interrupt();
        }
        if (writing != null) {
            writing.interrupt();
        }
    }

    private void readLoop(Conn conn, LongSupplier now) {
        try {
            while (!stopped) {
                Frame frame = conn.read();
                synchronized (lock) {
                    lastIn = now.getAsLong();
                }
                try {
                    accept(frame);
                } catch (IllegalArgumentException error) {
                    String message = error.getMessage();
                    if ("link".equals(message) || "seq".equals(message) || "inbox".equals(message)) {
                        throw error;
                    }
                }
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (Exception error) {
            failure.compareAndSet(null, error);
            close();
        }
    }

    private void writeLoop(Conn conn, LongSupplier now) {
        try {
            while (!stopped) {
                Frame frame = take();
                conn.write(frame);
                synchronized (lock) {
                    lastOut = now.getAsLong();
                }
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (Exception error) {
            failure.compareAndSet(null, error);
            close();
        }
    }

    private Frame take() throws InterruptedException {
        synchronized (lock) {
            while (true) {
                Frame frame = pullLocked();
                if (frame != null) {
                    return frame;
                }
                if (stopped) {
                    throw new InterruptedException("closed");
                }
                lock.wait();
            }
        }
    }

    private Stream newStream() {
        Stream stream = new Stream();
        stream.send = buffer;
        stream.peer = buffer;
        return stream;
    }

    private int active() {
        int count = 0;
        for (Stream stream : streams.values()) {
            if (!stream.dead && !stream.ended) {
                count++;
            }
        }
        return count;
    }

    private Stream live(long channel) {
        Stream stream = streams.get(channel);
        if (stream == null || stream.dead || stream.ended) {
            throw new IllegalArgumentException("channel");
        }
        return stream;
    }

    private void queue(int priority, int kind, int flags, long channel, byte[] payload, boolean account) {
        byte[] body = payload == null ? new byte[0] : payload;
        if (account && (long) outbound + body.length > outboundLimit) {
            writeBlocked = true;
            throw new IllegalArgumentException("backpressure");
        }
        push(priority, kind, flags, channel, body);
        if (account) {
            outbound += body.length;
        }
        lock.notifyAll();
    }

    private void push(int priority, int kind, int flags, long channel, byte[] payload) {
        if (priority < LinkFlow.PRIORITY_LINK || priority > LinkFlow.PRIORITY_FILE) {
            throw new IllegalArgumentException("priority");
        }
        LinkFrame checked = LinkFrame.decode(
                LinkFrame.encode(kind, flags, new byte[16], channel, 0L, payload, LinkFrame.MAIN_PAYLOAD),
                LinkFrame.MAIN_PAYLOAD);
        queues[priority].add(new Queued(checked.kind, checked.flags, checked.channel, checked.payload));
    }

    private Frame pullLocked() {
        for (ArrayDeque<Queued> queue : queues) {
            Queued frame = queue.poll();
            if (frame == null) {
                continue;
            }
            if (frame.kind == LinkFrame.KIND_DATA || frame.kind == LinkFrame.KIND_MESSAGE) {
                outbound -= frame.payload.length;
                if (writeBlocked && outbound < outboundLimit) {
                    writeBlocked = false;
                    writable++;
                }
            }
            long seq = 0;
            byte[] id = new byte[16];
            if (frame.kind != LinkFrame.KIND_LINK) {
                seq = outSeq++;
                id = peer;
            }
            Frame built = new Frame(frame.kind, frame.flags, id, frame.channel, seq, frame.payload);
            if (codec != null && (built.kind == LinkFrame.KIND_MESSAGE || built.kind == LinkFrame.KIND_DATA)) {
                try {
                    return codec.seal(built);
                } catch (IllegalArgumentException error) {
                    broken = true;
                    failure.compareAndSet(null, error);
                    return null;
                }
            }
            return built;
        }
        return null;
    }

    private List<Queued> drop(long channel) {
        List<Queued> dropped = new ArrayList<>();
        for (ArrayDeque<Queued> queue : queues) {
            Iterator<Queued> items = queue.iterator();
            while (items.hasNext()) {
                Queued frame = items.next();
                if (frame.channel == channel && (frame.kind == LinkFrame.KIND_DATA || frame.kind == LinkFrame.KIND_MESSAGE)) {
                    dropped.add(frame);
                    items.remove();
                }
            }
        }
        return dropped;
    }

    private void grant(Frame frame) {
        Stream stream = streams.get(frame.channel);
        if (stream == null || stream.dead) {
            throw new IllegalArgumentException("channel");
        }
        if (frame.payload.length != 4) {
            kill(frame.channel, "window");
            throw new IllegalArgumentException("window");
        }
        long amount = ((frame.payload[0] & 0xffL) << 24) | ((frame.payload[1] & 0xffL) << 16)
                | ((frame.payload[2] & 0xffL) << 8) | (frame.payload[3] & 0xffL);
        if (amount > (1L << 30) || amount > (long) buffer - stream.send) {
            kill(frame.channel, "window");
            throw new IllegalArgumentException("window");
        }
        stream.send += (int) amount;
    }

    private void inbound(Frame frame) {
        Stream stream = streams.get(frame.channel);
        if (stream == null) {
            LinkFlow.openStream(active(), frame.channel, !fromMac);
            stream = newStream();
            streams.put(frame.channel, stream);
        }
        if (stream.dead || stream.ended) {
            throw new IllegalArgumentException("channel");
        }
        if (stream.pendingKind != 0 && frame.kind != stream.pendingKind) {
            kill(frame.channel, "fragment");
            throw new IllegalArgumentException("fragment");
        }
        if (frame.payload.length > stream.peer || stream.pendingLen + frame.payload.length > maxMessage) {
            String code = stream.pendingLen + frame.payload.length > maxMessage ? "limit" : "window";
            kill(frame.channel, code);
            throw new IllegalArgumentException(code);
        }
        stream.peer -= frame.payload.length;
        LinkFlow.Result result;
        try {
            result = stream.assembly.add(frame.flags, frame.payload);
        } catch (IllegalArgumentException error) {
            kill(frame.channel, "limit");
            throw new IllegalArgumentException("limit");
        }
        if (result == null) {
            stream.pendingKind = frame.kind;
            stream.pendingLen += frame.payload.length;
            return;
        }
        stream.pendingKind = 0;
        stream.pendingLen = 0;
        stream.unconsumed += result.payload.length;
        inbox.add(new Chunk(frame.channel, result.payload, result.end));
        if (result.end) {
            stream.ended = true;
        }
    }

    private void finishControl(Frame frame) {
        if (controlKind != 0 && frame.kind != controlKind) {
            resetControl();
            throw new IllegalArgumentException("fragment");
        }
        if (controlLen + frame.payload.length > maxMessage) {
            resetControl();
            throw new IllegalArgumentException("limit");
        }
        LinkFlow.Result result;
        try {
            result = control.add(frame.flags, frame.payload);
        } catch (IllegalArgumentException error) {
            resetControl();
            throw new IllegalArgumentException("limit");
        }
        if (result == null) {
            controlKind = frame.kind;
            controlLen += frame.payload.length;
            return;
        }
        if (inboxBytes + result.payload.length > inboxLimit) {
            broken = true;
            resetControl();
            throw new IllegalArgumentException("inbox");
        }
        controlKind = 0;
        controlLen = 0;
        inboxBytes += result.payload.length;
        inbox.add(new Chunk(0, result.payload, result.end));
    }

    private void resetControl() {
        control = new LinkFlow.Assembly();
        controlLen = 0;
        controlKind = 0;
    }

    private void kill(long channel, String code) {
        Stream stream = streams.get(channel);
        if (stream != null && stream.dead) {
            return;
        }
        if (stream == null) {
            stream = newStream();
            streams.put(channel, stream);
        }
        stream.dead = true;
        stream.assembly = new LinkFlow.Assembly();
        stream.pendingKind = 0;
        stream.pendingLen = 0;
        for (Queued frame : drop(channel)) {
            outbound -= frame.payload.length;
        }
        if (code == null || code.isEmpty()) {
            return;
        }
        try {
            byte[] payload = ("{\"code\":\"" + code + "\"}").getBytes(StandardCharsets.US_ASCII);
            queue(LinkFlow.PRIORITY_CONTROL, LinkFrame.KIND_RESET, 0, channel, payload, false);
        } catch (IllegalArgumentException ignored) {
            // 重置帧写不出去时，流已经标成失败。
        }
    }

    @SuppressWarnings("unchecked")
    private static ArrayDeque<Queued>[] queues() {
        ArrayDeque<Queued>[] out = new ArrayDeque[4];
        for (int i = 0; i < out.length; i++) {
            out[i] = new ArrayDeque<>();
        }
        return out;
    }

    private static final class Queued {
        final int kind;
        final int flags;
        final long channel;
        final byte[] payload;

        Queued(int kind, int flags, long channel, byte[] payload) {
            this.kind = kind;
            this.flags = flags;
            this.channel = channel;
            this.payload = payload;
        }
    }

    private static final class Stream {
        int send;
        int peer;
        int unconsumed;
        int pendingLen;
        int pendingKind;
        LinkFlow.Assembly assembly = new LinkFlow.Assembly();
        boolean ended;
        boolean dead;
    }
}
