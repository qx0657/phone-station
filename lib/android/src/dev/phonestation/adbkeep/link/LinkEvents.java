package dev.phonestation.adbkeep.link;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 会话内的事件订阅。状态主题 1 秒内只留最新值，ops 再按 op 合并。
 * clipboard 与 notifications 有序并带游标；手机不再持有的部分用 reset，不补历史。
 * 订阅绑定在会话上，会话结束就卸下。现有 MCP 服务还不调用。
 */
public final class LinkEvents {
    public static final long COALESCE_MS = 1000;
    public static final long NOTICE_HOLD_MS = 20_000;
    public static final List<String> TOPICS = List.of(
            "device", "grants", "capabilities", "ops", "clipboard", "notifications");

    public interface Clock {
        long elapsedMillis();
    }

    /** 之后的事件走这里。subscribe 的立即回复由调用方先写出去。 */
    public interface Sender {
        void send(byte[] message);
    }

    private final Clock clock;
    private final boolean automatic;
    private final Object lock = new Object();
    private final Map<String, Sub> subs = new LinkedHashMap<String, Sub>();
    private final Map<String, Slot> pending = new LinkedHashMap<String, Slot>();
    private final Map<String, Slot> latest = new LinkedHashMap<String, Slot>();
    private final Map<String, Log> logs = new LinkedHashMap<String, Log>();
    private final ScheduledExecutorService timer;
    private ScheduledFuture<?> timerTask;
    private boolean closed;

    private LinkEvents(Clock clock, boolean automatic) {
        if (clock == null) {
            throw new IllegalArgumentException("clock");
        }
        this.clock = clock;
        this.automatic = automatic;
        this.timer = automatic ? Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "link-events");
            thread.setDaemon(true);
            return thread;
        }) : null;
    }

    public static LinkEvents automatic() {
        long start = System.nanoTime();
        return new LinkEvents(() -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), true);
    }

    public static LinkEvents manual(Clock clock) {
        return new LinkEvents(clock, false);
    }

    public static Clock systemClock() {
        long start = System.nanoTime();
        return () -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }

    /** 回执状态变化。busy、conflict、not_found 不是已接受操作的状态。 */
    public void changed(LinkReceipts.Receipt receipt) {
        if (receipt == null || receipt.op == null || receipt.op.isEmpty()) {
            return;
        }
        if ("busy".equals(receipt.state) || "conflict".equals(receipt.state) || "not_found".equals(receipt.state)) {
            return;
        }
        publishOp(receipt.macId, receipt.op, receipt.state, receipt.body);
    }

    public void publishState(String topic, String macId, byte[] body) {
        if (!"device".equals(topic) && !"grants".equals(topic) && !"capabilities".equals(topic)) {
            throw new IllegalArgumentException("topic");
        }
        String scope = "";
        if ("grants".equals(topic)) {
            scope = mac(macId);
        }
        slot(topic, scope, "", body, null, clock.elapsedMillis());
    }

    public void publishOp(String macId, String op, String state, byte[] body) {
        if (state == null || !state.matches("[a-z_]{1,16}")) {
            throw new IllegalArgumentException("state");
        }
        if (op == null || !op.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException("op");
        }
        slot("ops", mac(macId), op, body, state, clock.elapsedMillis());
    }

    /** 把一条剪贴板或通知变化发给每一台正在订阅的 Mac。没有订阅者时不保留。 */
    public void broadcastOrdered(String topic, byte[] body) {
        if (!"clipboard".equals(topic) && !"notifications".equals(topic)) {
            throw new IllegalArgumentException("topic");
        }
        List<String> macs = new ArrayList<String>();
        synchronized (lock) {
            for (Sub sub : subs.values()) {
                if (sub.topics.contains(topic) && !macs.contains(sub.macId)) {
                    macs.add(sub.macId);
                }
            }
        }
        for (String mac : macs) {
            append(topic, mac, body);
        }
    }

    /** 有序主题追加一条并立刻发给当前订阅。返回十进制游标。 */
    public String append(String topic, String macId, byte[] body) {
        if (!"clipboard".equals(topic) && !"notifications".equals(topic)) {
            throw new IllegalArgumentException("topic");
        }
        String id = mac(macId);
        byte[] payload = body == null ? new byte[0] : body;
        byte[] message;
        List<Sender> targets = new ArrayList<Sender>();
        String cursor;
        synchronized (lock) {
            if (closed) {
                return "0";
            }
            long now = clock.elapsedMillis();
            Log log = log(topic, id);
            trim(topic, log, now);
            long next = log.next;
            log.next = next + 1;
            log.items.add(new Item(next, payload, now));
            trim(topic, log, now);
            cursor = Long.toString(next);
            message = eventOrdered(topic, cursor, payload);
            for (Sub sub : subs.values()) {
                if (sub.accepts(topic, id)) {
                    targets.add(sub.sender);
                }
            }
        }
        for (Sender sender : targets) {
            sender.send(message);
        }
        return cursor;
    }

    /**
     * 替换这一会话的订阅。返回要先写出的消息：subscribed，然后是补发、reset 或已提交的状态快照。
     * 调用方必须先把这些写完，之后的事件才经 sender 发送。
     */
    public List<byte[]> subscribe(String session, String macId, List<String> topics, Map<String, String> cursors, Sender sender) {
        if (session == null || session.isEmpty() || sender == null || topics == null) {
            throw new IllegalArgumentException("subscribe");
        }
        String id = mac(macId);
        synchronized (lock) {
            if (closed) {
                return List.of(subscribed(List.of(), List.of(), List.of()));
            }
            long now = clock.elapsedMillis();
            List<String> accepted = new ArrayList<String>();
            List<String> rejected = new ArrayList<String>();
            List<String> reasons = new ArrayList<String>();
            Set<String> seen = new LinkedHashSet<String>();
            for (String topic : topics) {
                if (topic == null || !topic.matches("[a-z]{1,32}")) {
                    throw new IllegalArgumentException("subscribe");
                }
                if (!seen.add(topic)) {
                    continue;
                }
                if (!TOPICS.contains(topic)) {
                    rejected.add(topic);
                    reasons.add("unknown");
                    continue;
                }
                String cursor = cursors == null ? null : cursors.get(topic);
                if (cursor != null && !cursor.matches("0|[1-9][0-9]{0,17}")) {
                    rejected.add(topic);
                    reasons.add("cursor");
                    continue;
                }
                accepted.add(topic);
            }
            subs.put(session, new Sub(id, Set.copyOf(accepted), sender));
            List<byte[]> out = new ArrayList<byte[]>();
            out.add(subscribed(accepted, rejected, reasons));
            for (String topic : accepted) {
                if ("clipboard".equals(topic) || "notifications".equals(topic)) {
                    String cursor = cursors == null ? null : cursors.get(topic);
                    out.addAll(catchup(topic, id, cursor, now));
                    continue;
                }
                if ("ops".equals(topic)) {
                    continue;
                }
                String scope = "grants".equals(topic) ? id : "";
                Slot snap = latest.get(topic + "\0" + scope);
                if (snap != null) {
                    out.add(encode(snap));
                }
            }
            return out;
        }
    }

    public void unbind(String session) {
        synchronized (lock) {
            subs.remove(session);
        }
    }

    /** 发出 due 不晚于 now 的合并事件。返回实际发给订阅方的条数。 */
    public int deliver(long now) {
        List<Slot> due = new ArrayList<Slot>();
        List<Sub> current;
        synchronized (lock) {
            if (closed) {
                return 0;
            }
            Iterator<Slot> it = pending.values().iterator();
            while (it.hasNext()) {
                Slot slot = it.next();
                if (slot.due > now) {
                    continue;
                }
                it.remove();
                due.add(slot);
                if (!"ops".equals(slot.topic)) {
                    latest.put(slot.topic + "\0" + slot.macId, slot.copy());
                }
            }
            current = new ArrayList<Sub>(subs.values());
        }
        int sent = 0;
        for (Slot slot : due) {
            byte[] message = encode(slot);
            for (Sub sub : current) {
                if (sub.accepts(slot.topic, slot.macId)) {
                    sub.sender.send(message);
                    sent++;
                }
            }
        }
        if (automatic) {
            armTimer();
        }
        return sent;
    }

    public void close() {
        synchronized (lock) {
            closed = true;
            subs.clear();
            pending.clear();
            if (timerTask != null) {
                timerTask.cancel(false);
                timerTask = null;
            }
        }
        if (timer != null) {
            timer.shutdownNow();
        }
    }

    private void slot(String topic, String macId, String op, byte[] body, String state, long now) {
        synchronized (lock) {
            if (closed) {
                return;
            }
            String key = topic + "\0" + macId + "\0" + op;
            Slot existing = pending.get(key);
            if (existing == null) {
                Slot created = new Slot(topic, macId, op, body, state, now + COALESCE_MS);
                pending.put(key, created);
            } else {
                existing.body = body;
                existing.state = state;
            }
        }
        if (automatic) {
            armTimer();
        }
    }

    private void armTimer() {
        if (timer == null) {
            return;
        }
        long delay;
        synchronized (lock) {
            if (closed || pending.isEmpty()) {
                return;
            }
            long now = clock.elapsedMillis();
            long next = Long.MAX_VALUE;
            for (Slot slot : pending.values()) {
                if (slot.due < next) {
                    next = slot.due;
                }
            }
            delay = Math.max(1L, next - now);
            if (timerTask != null) {
                timerTask.cancel(false);
            }
            timerTask = timer.schedule(this::fire, delay, TimeUnit.MILLISECONDS);
        }
    }

    private void fire() {
        deliver(clock.elapsedMillis());
    }

    private List<byte[]> catchup(String topic, String macId, String cursor, long now) {
        Log log = logs.get(topic + "\0" + macId);
        if (log != null) {
            trim(topic, log, now);
        }
        if (cursor == null || cursor.isEmpty()) {
            return List.of();
        }
        long want = Long.parseLong(cursor);
        if (log == null || log.items.isEmpty()) {
            if (want == 0) {
                return List.of();
            }
            return List.of(reset(topic));
        }
        long oldest = log.items.peekFirst().cursor;
        long newest = log.items.peekLast().cursor;
        if (want >= newest) {
            return List.of();
        }
        if (want < oldest - 1) {
            return List.of(reset(topic));
        }
        List<byte[]> out = new ArrayList<byte[]>();
        for (Item item : log.items) {
            if (item.cursor > want) {
                out.add(eventOrdered(topic, Long.toString(item.cursor), item.body));
            }
        }
        return out;
    }

    private Log log(String topic, String macId) {
        String key = topic + "\0" + macId;
        Log log = logs.get(key);
        if (log == null) {
            log = new Log();
            logs.put(key, log);
        }
        return log;
    }

    private static void trim(String topic, Log log, long now) {
        if ("notifications".equals(topic)) {
            while (!log.items.isEmpty() && now - log.items.peekFirst().at > NOTICE_HOLD_MS) {
                log.items.removeFirst();
            }
        }
        int limit = "clipboard".equals(topic) ? 1 : 32;
        while (log.items.size() > limit) {
            log.items.removeFirst();
        }
    }

    private static String mac(String macId) {
        if (macId == null || !macId.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException("mac");
        }
        return macId;
    }

    private static byte[] encode(Slot slot) {
        if ("ops".equals(slot.topic)) {
            return eventOp(slot.op, slot.state, slot.body);
        }
        return eventState(slot.topic, slot.body);
    }

    private static byte[] subscribed(List<String> topics, List<String> rejected, List<String> reasons) {
        return ascii("{\"type\":\"subscribed\",\"topics\":" + array(topics)
                + ",\"rejected\":" + array(rejected) + ",\"reasons\":" + array(reasons) + "}");
    }

    private static byte[] eventState(String topic, byte[] body) {
        String text = "{\"type\":\"event\",\"topic\":\"" + topic + "\"";
        if (body != null) {
            text += ",\"body\":\"" + Base64.getEncoder().encodeToString(body) + "\"";
        }
        return ascii(text + "}");
    }

    private static byte[] eventOp(String op, String state, byte[] body) {
        String text = "{\"type\":\"event\",\"topic\":\"ops\",\"op\":\"" + op + "\",\"state\":\"" + state + "\"";
        if (body != null && body.length <= 1024) {
            text += ",\"body\":\"" + Base64.getEncoder().encodeToString(body) + "\"";
        }
        return ascii(text + "}");
    }

    private static byte[] eventOrdered(String topic, String cursor, byte[] body) {
        return ascii("{\"type\":\"event\",\"topic\":\"" + topic + "\",\"cursor\":\"" + cursor
                + "\",\"body\":\"" + Base64.getEncoder().encodeToString(body == null ? new byte[0] : body) + "\"}");
    }

    private static byte[] reset(String topic) {
        return ascii("{\"type\":\"reset\",\"topic\":\"" + topic + "\"}");
    }

    private static String array(List<String> values) {
        StringBuilder out = new StringBuilder();
        out.append('[');
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append('"').append(values.get(i)).append('"');
        }
        return out.append(']').toString();
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static final class Sub {
        final String macId;
        final Set<String> topics;
        final Sender sender;

        Sub(String macId, Set<String> topics, Sender sender) {
            this.macId = macId;
            this.topics = topics;
            this.sender = sender;
        }

        boolean accepts(String topic, String macId) {
            if (!topics.contains(topic)) {
                return false;
            }
            if ("device".equals(topic) || "capabilities".equals(topic)) {
                return true;
            }
            return this.macId.equals(macId);
        }
    }

    private static final class Slot {
        final String topic;
        final String macId;
        final String op;
        byte[] body;
        String state;
        final long due;

        Slot(String topic, String macId, String op, byte[] body, String state, long due) {
            this.topic = topic;
            this.macId = macId;
            this.op = op;
            this.body = body;
            this.state = state;
            this.due = due;
        }

        Slot copy() {
            return new Slot(topic, macId, op, body, state, due);
        }
    }

    private static final class Item {
        final long cursor;
        final byte[] body;
        final long at;

        Item(long cursor, byte[] body, long at) {
            this.cursor = cursor;
            this.body = body;
            this.at = at;
        }
    }

    private static final class Log {
        long next = 1;
        final ArrayDeque<Item> items = new ArrayDeque<Item>();
    }
}
