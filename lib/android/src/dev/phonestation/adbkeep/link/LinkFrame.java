package dev.phonestation.adbkeep.link;

import java.util.Arrays;

/** 链路帧。不接入现有通道。 */
public final class LinkFrame {
    public static final int HEADER = 32;
    public static final int MAIN_PAYLOAD = 32 * 1024;
    public static final int MEDIA_PAYLOAD = 256 * 1024;
    public static final int KIND_LINK = 0x01;
    public static final int KIND_CREDIT = 0x05;

    public final int kind;
    public final int flags;
    public final byte[] peer;
    public final long channel;
    public final long seq;
    public final byte[] payload;

    private LinkFrame(int kind, int flags, byte[] peer, long channel, long seq, byte[] payload) {
        this.kind = kind;
        this.flags = flags;
        this.peer = Arrays.copyOf(peer, 16);
        this.channel = channel;
        this.seq = seq;
        this.payload = payload == null ? new byte[0] : Arrays.copyOf(payload, payload.length);
    }

    public static byte[] encode(int kind, int flags, byte[] peer, long channel, long seq, byte[] payload, int limit) {
        LinkFrame frame = new LinkFrame(kind, flags, peer, channel, seq, payload);
        validate(frame, limit);
        return raw(frame);
    }

    public static LinkFrame decode(byte[] frame, int limit) {
        if (frame == null || frame.length < HEADER) {
            throw new IllegalArgumentException("truncated");
        }
        if ((frame[0] & 0xff) != 2) {
            throw new IllegalArgumentException("version");
        }
        if (frame[3] != 0) {
            throw new IllegalArgumentException("reserved");
        }
        byte[] peer = Arrays.copyOfRange(frame, 4, 20);
        long channel = readUnsigned(frame, 20, 4);
        long seq = readUnsigned(frame, 24, 8);
        byte[] payload = Arrays.copyOfRange(frame, HEADER, frame.length);
        LinkFrame value = new LinkFrame(frame[1] & 0xff, frame[2] & 0xff, peer, channel, seq, payload);
        validate(value, limit);
        return value;
    }

    private static void validate(LinkFrame frame, int limit) {
        if (frame.kind < 0x01 || frame.kind > 0x06) {
            throw new IllegalArgumentException("kind");
        }
        if ((frame.flags & ~0x07) != 0) {
            throw new IllegalArgumentException("flags");
        }
        if (limit < 0 || frame.payload.length > limit) {
            throw new IllegalArgumentException("limit");
        }
        if (frame.kind == KIND_LINK) {
            for (byte bit : frame.peer) {
                if (bit != 0) {
                    throw new IllegalArgumentException("peer");
                }
            }
            if (frame.channel != 0) {
                throw new IllegalArgumentException("channel");
            }
            if (frame.seq != 0) {
                throw new IllegalArgumentException("seq");
            }
        }
        if (frame.kind == KIND_CREDIT && frame.payload.length != 4) {
            throw new IllegalArgumentException("credit");
        }
    }

    private static byte[] raw(LinkFrame frame) {
        byte[] out = new byte[HEADER + frame.payload.length];
        out[0] = 2;
        out[1] = (byte) frame.kind;
        out[2] = (byte) frame.flags;
        System.arraycopy(frame.peer, 0, out, 4, 16);
        writeUnsigned(out, 20, 4, frame.channel);
        writeUnsigned(out, 24, 8, frame.seq);
        System.arraycopy(frame.payload, 0, out, HEADER, frame.payload.length);
        return out;
    }

    private static long readUnsigned(byte[] in, int offset, int length) {
        long value = 0;
        for (int i = 0; i < length; i++) {
            value = (value << 8) | (in[offset + i] & 0xffL);
        }
        return value;
    }

    private static void writeUnsigned(byte[] out, int offset, int length, long value) {
        for (int i = length - 1; i >= 0; i--) {
            out[offset + i] = (byte) value;
            value >>>= 8;
        }
    }
}
