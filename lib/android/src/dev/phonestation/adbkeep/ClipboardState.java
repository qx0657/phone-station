package dev.phonestation.adbkeep;

import java.util.Objects;
import java.util.UUID;

/** Text stays in memory. Versions, rather than wall clocks, arbitrate simultaneous copies. */
final class ClipboardState {
    static final int LIMIT = 100_000;
    static final class Clip {
        final String kind;
        final String text;
        final long sourceVersion;
        Clip(String kind, String text) {
            this(kind, text, 0);
        }
        Clip(String kind, String text, long sourceVersion) {
            if (!"text".equals(kind) && !"empty".equals(kind) && !"unsupported".equals(kind)
                    && !"sensitive".equals(kind) && !"oversize".equals(kind) && !"locked".equals(kind)) {
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
    }

    private Clip phone;
    private String revision = UUID.randomUUID().toString();
    private Clip mac;
    private String client;
    private String macVersion;
    private long macSeen;

    synchronized Json exchange(Access access, Json args, boolean automatic, long now) {
        observe(access.read());
        boolean applied = false;
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
            // A first contact/reconnect only establishes a baseline. A phone-side edit wins a conflict.
            if (automatic && changed && current && now - macSeen < 10_000L
                    && "text".equals(incoming.kind) && "text".equals(phone.kind)
                    && !incoming.same(phone)) {
                // Read again before writing, so a copy made during the request is not overwritten.
                Clip latest = access.read();
                if (latest.sameSource(phone)) {
                    access.write(incoming.text);
                    observe(access.read());
                    applied = incoming.same(phone);
                } else { observe(latest); }
            } else if (automatic && changed && current && now - macSeen < 10_000L
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
        return snapshot(now).put("appliedMac", applied);
    }

    synchronized Json snapshot(long now) {
        boolean online = mac != null && now - macSeen < 10_000L;
        return Json.obj().put("phone", phone == null ? Json.nul() : phone.json())
                .put("phoneVersion", revision)
                .put("mac", online ? mac.json() : Json.nul()).put("macOnline", online);
    }
    synchronized void clear() {
        phone = null; mac = null; client = null; macVersion = null; macSeen = 0;
        revision = UUID.randomUUID().toString();
    }
    private void observe(Clip latest) {
        if (!latest.sameSource(phone)) { phone = latest; revision = UUID.randomUUID().toString(); }
    }
    private static String value(Json args, String key) {
        Json value = args.get(key);
        return value == null || value.isNull() ? null : value.string();
    }
}
