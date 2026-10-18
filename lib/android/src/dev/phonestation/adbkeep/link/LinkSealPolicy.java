package dev.phonestation.adbkeep.link;

/** 必须加密时拒绝明文，不降级。本地链路不加密。序号不能回头，满 2^32 帧或 24 小时要换钥。 */
public final class LinkSealPolicy {
    public final boolean required;
    public final boolean local;
    private boolean seen;
    private long last;
    private long frames;
    private long startMs;

    public LinkSealPolicy(boolean required, boolean local) {
        this.required = required;
        this.local = local;
    }

    /** 必须加密时拒绝明文应用帧，不把原正文退回给调用方。 */
    public static byte[] admitApplication(boolean required, int flags, int kind, byte[] payload) {
        if (kind != LinkFrame.KIND_MESSAGE && kind != LinkFrame.KIND_DATA) {
            throw new IllegalArgumentException("kind");
        }
        new LinkSealPolicy(required, false).admit(flags);
        return payload == null ? new byte[0] : payload.clone();
    }

    public void admit(int flags) {
        boolean encrypted = (flags & 0x01) != 0;
        if (local) {
            if (encrypted) {
                throw new IllegalArgumentException("local");
            }
            return;
        }
        if (required && !encrypted) {
            throw new IllegalArgumentException("plaintext");
        }
    }

    public void accept(long seq, long nowMs) {
        if (startMs != 0 && (nowMs - startMs >= 24L * 60L * 60L * 1000L || frames >= (1L << 32))) {
            throw new IllegalArgumentException("rekey");
        }
        if (seen && Long.compareUnsigned(seq, last) <= 0) {
            throw new IllegalArgumentException("replay");
        }
        if (!seen) {
            startMs = nowMs;
        }
        seen = true;
        last = seq;
        frames++;
    }

    public void setFrames(long value) {
        frames = value;
    }

    public void setStart(long value) {
        startMs = value;
    }
}
