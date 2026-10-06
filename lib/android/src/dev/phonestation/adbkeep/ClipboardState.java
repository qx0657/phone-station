package dev.phonestation.adbkeep;

import java.util.Objects;
import java.util.UUID;
import java.util.Base64;

/** Text stays in memory. Versions, rather than wall clocks, arbitrate simultaneous copies. */
final class ClipboardState {
    static final int LIMIT = 100_000;
    static final int IMAGE_LIMIT = 4 * 1024 * 1024;
    static final class Clip {
        final String kind;
        final String text;
        final long sourceVersion;
        Clip(String kind, String text) {
            this(kind, text, 0);
        }
        Clip(String kind, String text, long sourceVersion) {
            if (!"text".equals(kind) && !"empty".equals(kind) && !"unsupported".equals(kind)
                    && !"sensitive".equals(kind) && !"oversize".equals(kind) && !"locked".equals(kind)
                    && !"image".equals(kind)) {
                throw new IllegalArgumentException("剪贴板类型无效");
            }
            if ("text".equals(kind) && (text == null || text.length() > LIMIT)) {
                throw new IllegalArgumentException("剪贴板文字最多 100000 字");
            }
            this.kind = kind;
            this.text = "text".equals(kind) ? text : null;
            this.sourceVersion = sourceVersion;
        }
        Json json() { return Json.obj().put("kind", kind).put("text", text == null ? Json.nul() : Json.str(text)); }
        boolean same(Clip other) { return other != null && kind.equals(other.kind) && Objects.equals(text, other.text); }
        boolean sameSource(Clip other) { return same(other) && sourceVersion == other.sourceVersion; }
    }
    interface Access {
        Clip read();
        void write(String text);
        default void saveImage(byte[] png) { throw new FileFailure("当前版本不支持图片同步"); }
    }

    private Clip phone;
    private String revision = UUID.randomUUID().toString();
    private String sessionId = UUID.randomUUID().toString();
    private Clip mac;
    private String client;
    private String macVersion;
    private long macSeen;
    private String subscriber = "";
    private long leaseAt = -1;

    synchronized boolean lease(String id, long now) {
        if (id.isEmpty()) { subscriber = ""; leaseAt = -1; return false; }
        if (!id.equals(client) || !(now - macSeen < 10_000L || leased(now))) { return false; }
        subscriber = id; leaseAt = now; return true;
    }
    private boolean leased(long now) {
        return subscriber.equals(client) && leaseAt >= 0 && now >= leaseAt && now - leaseAt < 45_000L;
    }

    synchronized Json refresh(Access access, long now) {
        observe(access.read());
        return snapshot(now);
    }

    synchronized Json exchange(Access access, Json args, boolean automatic, long now) {
        return exchange(access, args, automatic, false, now);
    }

    synchronized Json exchange(Access access, Json args, boolean automatic, boolean images, long now) {
        observe(access.read());
        boolean applied = false;
        boolean savedImage = false;
        String imageError = null;
        String incomingClient = value(args, "clientId");
        if (incomingClient != null) {
            String incomingVersion = value(args, "macVersion");
            String kind = value(args, "macKind");
            if (incomingClient.length() > 100 || incomingVersion == null || incomingVersion.length() > 100 || kind == null) {
                throw new IllegalArgumentException("需要有效的 clientId、macVersion、macKind");
            }
            Clip incoming = new Clip(kind, value(args, "macText"));
            boolean changed = incomingClient.equals(client) && !incomingVersion.equals(macVersion);
            boolean current = revision.equals(value(args, "phoneVersion"));
            // Resume only a newer Mac event after a fresh read; the phone version must still match.
            boolean resume = args.get("resume") != null && args.get("resume").boolValue();
            boolean recent = now - macSeen < (resume ? 30_000L : 10_000L) || leased(now);
            if (automatic && images && changed && current && recent && "image".equals(incoming.kind)
                    && !"locked".equals(phone.kind) && !"sensitive".equals(phone.kind)
                    && value(args, "macImage") != null) {
                byte[] png = decodeImage(value(args, "macImage"));
                Clip latest = access.read();
                if (latest.sameSource(phone)) {
                    // Consume before saving: an unknown result must never create a duplicate album item.
                    client = incomingClient; macVersion = incomingVersion; mac = incoming; macSeen = now;
                    try { access.saveImage(png); savedImage = true; }
                    catch (FileFailure failure) { imageError = failure.getMessage(); }
                } else { observe(latest); }
            }
            // A first contact/reconnect only establishes a baseline. A phone-side edit wins a conflict.
            if (automatic && changed && current && recent
                    && "text".equals(incoming.kind) && "text".equals(phone.kind)
                    && !incoming.same(phone)) {
                // Read again before writing, so a copy made during the request is not overwritten.
                Clip latest = access.read();
                if (latest.sameSource(phone)) {
                    access.write(incoming.text);
                    observe(access.read());
                    applied = incoming.same(phone);
                } else { observe(latest); }
            } else if (automatic && changed && current && recent
                    && "text".equals(incoming.kind) && "empty".equals(phone.kind)) {
                Clip latest = access.read();
                if (latest.sameSource(phone)) {
                    access.write(incoming.text);
                    observe(access.read());
                    applied = incoming.same(phone);
                } else { observe(latest); }
            }
            client = incomingClient;
            macVersion = incomingVersion;
            mac = incoming;
            macSeen = now;
        }
        Json result = snapshot(now).put("appliedMac", applied).put("savedImage", savedImage);
        if (imageError != null) { result.put("imageError", imageError); }
        return result;
    }

    private static byte[] decodeImage(String encoded) {
        if (encoded.length() > ((IMAGE_LIMIT + 2) / 3) * 4) {
            throw new IllegalArgumentException("图片最多 4 MB");
        }
        byte[] png;
        try { png = Base64.getDecoder().decode(encoded); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("图片编码无效"); }
        byte[] signature = {(byte)137, 80, 78, 71, 13, 10, 26, 10};
        if (png.length < 24 || png.length > IMAGE_LIMIT) { throw new IllegalArgumentException("图片最多 4 MB，且必须是 PNG"); }
        for (int i = 0; i < signature.length; i++) {
            if (png[i] != signature[i]) { throw new IllegalArgumentException("图片必须是 PNG"); }
        }
        return png;
    }

    synchronized Json snapshot(long now) {
        boolean online = mac != null && (now - macSeen < 10_000L || leased(now));
        return Json.obj().put("phone", phone == null ? Json.nul() : phone.json())
                .put("phoneVersion", revision)
                .put("sessionId", sessionId)
                .put("mac", online ? mac.json() : Json.nul()).put("macOnline", online);
    }
    synchronized void clear() {
        subscriber = ""; leaseAt = -1;
        phone = null; mac = null; client = null; macVersion = null; macSeen = 0;
        revision = UUID.randomUUID().toString();
        sessionId = UUID.randomUUID().toString();
    }
    private void observe(Clip latest) {
        if (!latest.sameSource(phone)) { phone = latest; revision = UUID.randomUUID().toString(); }
    }
    private static String value(Json args, String key) {
        Json value = args.get(key);
        return value == null || value.isNull() ? null : value.string();
    }
}
