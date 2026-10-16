package dev.phonestation.adbkeep.link;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;

/** 一条连接上的多个会话。心跳只发一份，拉取时控制消息先于别的会话的文件字节。 */
public final class LinkMux {
    private final List<LinkEngine> sessions;
    private final ArrayDeque<LinkEngine.Frame> pings = new ArrayDeque<>();
    private long lastIn;
    private long lastOut;

    public LinkMux(long start, LinkEngine... sessions) {
        this.sessions = List.copyOf(Arrays.asList(sessions));
        this.lastIn = start;
        this.lastOut = start;
    }

    public void advance(long now) {
        LinkFlow.Beat beat = LinkFlow.heartbeat(lastIn, lastOut, now);
        if (beat.dead) {
            throw new IllegalArgumentException("dead");
        }
        if (!beat.send) {
            return;
        }
        pings.add(new LinkEngine.Frame(LinkFrame.KIND_LINK, 0, 0, 0,
                "{\"type\":\"ping\"}".getBytes(StandardCharsets.US_ASCII)));
        lastOut = now;
    }

    public LinkEngine.Frame pull() {
        LinkEngine.Frame ping = pings.poll();
        if (ping != null) {
            return ping;
        }
        LinkEngine chosen = null;
        int best = Integer.MAX_VALUE;
        for (LinkEngine session : sessions) {
            int head = session.head();
            if (head >= 0 && head < best) {
                best = head;
                chosen = session;
            }
        }
        return chosen == null ? null : chosen.pull();
    }
}
