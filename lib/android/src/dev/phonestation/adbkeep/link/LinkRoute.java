package dev.phonestation.adbkeep.link;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mac 在本地与远程都可用时把新操作放在本地。已经开始的留在原通道；
 * 原通道断开后，查询改走另一条仍在的通道。流不跟着迁移。
 */
public final class LinkRoute {
    public enum Side {
        LOCAL,
        REMOTE
    }

    private boolean localUp;
    private boolean remoteUp;
    private final Map<String, Side> inflight = new LinkedHashMap<String, Side>();
    private final Map<Long, Side> streams = new LinkedHashMap<Long, Side>();

    public void up(Side side, boolean open) {
        if (side == Side.LOCAL) {
            localUp = open;
        } else {
            remoteUp = open;
        }
        if (open) {
            return;
        }
        Iterator<Map.Entry<Long, Side>> it = streams.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue() == side) {
                it.remove();
            }
        }
    }

    /** 新操作走哪一边。两边都没有时返回 null。 */
    public Side prefer() {
        if (localUp) {
            return Side.LOCAL;
        }
        if (remoteUp) {
            return Side.REMOTE;
        }
        return null;
    }

    /** 第一次提交记下通道。之后再 begin 不会把在途操作挪走。 */
    public void begin(String op, Side side) {
        if (op == null || side == null) {
            throw new IllegalArgumentException("op");
        }
        if (!inflight.containsKey(op)) {
            inflight.put(op, side);
        }
    }

    /** 现在该向哪一边查询。原通道还在就仍走原通道。 */
    public Side query(String op) {
        Side side = inflight.get(op);
        if (side == null) {
            return null;
        }
        if (open(side)) {
            return side;
        }
        Side other = side == Side.LOCAL ? Side.REMOTE : Side.LOCAL;
        return open(other) ? other : null;
    }

    public void openStream(long id, Side side) {
        if (side == null) {
            throw new IllegalArgumentException("stream");
        }
        streams.put(id, side);
    }

    public boolean streamOpen(long id) {
        return streams.containsKey(id);
    }

    private boolean open(Side side) {
        return side == Side.LOCAL ? localUp : remoteUp;
    }
}
