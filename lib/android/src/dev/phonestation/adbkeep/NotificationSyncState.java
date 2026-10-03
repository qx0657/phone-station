package dev.phonestation.adbkeep;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.UUID;

/** 白名单先于内容读取。短期队列只供在线 Mac 接收，不做通知历史。 */
final class NotificationSyncState {
    static final long ONLINE_MS = 15_000;
    static final long MAX_AGE_MS = 20_000;
    static final int MAX_EVENTS = 64;
    private final String ownPackage;
    private boolean enabled;
    private boolean access;
    private boolean connected;
    private Set<String> selected = new HashSet<>();
    private String session = UUID.randomUUID().toString();
    private String client = "";
    private long sequence;
    private long lastPoll = -1;
    private final ArrayDeque<Event> events = new ArrayDeque<>();
    private final LinkedHashMap<String, String> seen = new LinkedHashMap<>();

    NotificationSyncState(String ownPackage) { this.ownPackage = ownPackage; }

    synchronized void configure(boolean on, Set<String> packages) {
        Set<String> next = new HashSet<>(packages);
        next.remove(ownPackage);
        if (enabled != on || !selected.equals(next)) {
            enabled = on;
            selected = next;
            reset();
        }
    }

    synchronized void access(boolean granted) {
        if (access != granted) { access = granted; reset(); }
    }

    synchronized void listener(boolean on) {
        if (connected != on) { connected = on; reset(); }
    }

    synchronized boolean accepts(String pkg, long now) {
        return enabled && access && connected && !ownPackage.equals(pkg)
                && selected.contains(pkg) && online(now);
    }

    synchronized boolean allowsIcon(String pkg) {
        return enabled && access && !ownPackage.equals(pkg) && selected.contains(pkg);
    }

    synchronized void posted(String pkg, String key, String app, String title, String text,
                             boolean ongoing, boolean summary, long now) {
        if (!accepts(pkg, now) || ongoing || summary) { return; }
        title = bounded(title, 200);
        text = bounded(text, 4000);
        if (title.isEmpty() && text.isEmpty()) { return; }
        String fingerprint = title + "\u0000" + text;
        if (fingerprint.equals(seen.get(key))) { return; }
        seen.put(key, fingerprint);
        while (seen.size() > 256) { seen.remove(seen.keySet().iterator().next()); }
        events.addLast(new Event(++sequence, now, Json.obj()
                .put("id", session + ":" + sequence).put("key", bounded(key, 512))
                .put("packageName", pkg).put("app", bounded(app, 200))
                .put("title", title).put("text", text)));
        prune(now);
    }

    synchronized void removed(String key) { seen.remove(key); }

    synchronized Json status(long now) {
        prune(now);
        return Json.obj().put("enabled", enabled).put("accessGranted", access)
                .put("listenerConnected", connected).put("selectedCount", selected.size())
                .put("macOnline", online(now)).put("cursor", cursor()).put("events", Json.arr());
    }

    synchronized Json poll(String clientId, String cursor, long now) {
        if (clientId == null || clientId.isEmpty() || clientId.length() > 128) {
            throw new IllegalArgumentException("需要 Mac clientId，最多 128 字");
        }
        boolean baseline = !clientId.equals(client) || !online(now);
        long after = parseCursor(cursor);
        baseline |= after < 0 || after > sequence;
        if (enabled && access && connected && !selected.isEmpty()) {
            if (baseline) { events.clear(); seen.clear(); }
            client = clientId;
            lastPoll = now;
        }
        Json result = status(now);
        Json batch = Json.arr();
        if (!baseline && enabled && access && connected) {
            for (Event event : events) {
                if (event.sequence > after) { batch.add(event.payload); }
            }
        }
        return result.put("events", batch).put("baseline", baseline);
    }

    private boolean online(long now) { return lastPoll >= 0 && now >= lastPoll && now - lastPoll < ONLINE_MS; }
    private String cursor() { return session + ":" + sequence; }
    private long parseCursor(String value) {
        if (value == null || !value.startsWith(session + ":")) { return -1; }
        try { return Long.parseLong(value.substring(session.length() + 1)); }
        catch (NumberFormatException error) { return -1; }
    }
    private void reset() {
        session = UUID.randomUUID().toString(); sequence = 0; lastPoll = -1; client = "";
        events.clear(); seen.clear();
    }
    private void prune(long now) {
        while (!events.isEmpty() && (events.size() > MAX_EVENTS || now - events.peekFirst().time >= MAX_AGE_MS)) {
            events.removeFirst();
        }
        if (!online(now)) { events.clear(); seen.clear(); }
    }
    private static String bounded(String value, int max) {
        if (value == null) { return ""; }
        if (value.length() <= max) { return value; }
        int end = Character.isHighSurrogate(value.charAt(max - 1)) ? max - 1 : max;
        return value.substring(0, end);
    }
    private static final class Event {
        final long sequence, time;
        final Json payload;
        Event(long sequence, long time, Json payload) { this.sequence = sequence; this.time = time; this.payload = payload; }
    }
}
