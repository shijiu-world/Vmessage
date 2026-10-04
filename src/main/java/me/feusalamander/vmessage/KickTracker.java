package me.feusalamander.vmessage;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 记住「刚刚被踢出去」的玩家，好让紧接着的那条离开消息不再广播一遍。
 *
 * 背景：玩家被 /kick 时，Velocity 先给一个 KickedFromServerEvent(DisconnectPlayer)，
 * 断开连接时又会给一个 DisconnectEvent —— 于是 [Kick] 和 [Leave] 两条消息会各发一次。
 * 这里只做一件事：踢人的时候记一笔，离开的时候查一下、查到就撤掉这一笔并跳过 Leave。
 *
 * ⚠️ 为什么是"取一次就失效"：玩家被踢后可能马上重连，标记必须消费掉，
 *    否则他下一次正常退出会被误吞（静默）。
 */
final class KickTracker {
    /** 键 = 玩家 UUID，值 = 打标记的时刻（毫秒）。 */
    private final Map<UUID, Long> marks = new ConcurrentHashMap<>();
    /** 标记多久自动作废；正常情况下一秒内就被消费掉，这只是兜底。 */
    private final long ttlMillis;

    KickTracker(final long ttlMillis) {
        this.ttlMillis = ttlMillis;
    }

    /** 踢出去的时候记一笔。 */
    void mark(final UUID id) {
        purge();
        marks.put(id, System.currentTimeMillis());
    }

    /** 离开的时候查一下：查到就撤掉并返回 true（表示 Leave 该跳过）。 */
    boolean consume(final UUID id) {
        final Long at = marks.remove(id);
        return at != null && System.currentTimeMillis() - at <= ttlMillis;
    }

    private void purge() {
        final long now = System.currentTimeMillis();
        for (final Iterator<Map.Entry<UUID, Long>> it = marks.entrySet().iterator(); it.hasNext(); ) {
            if (now - it.next().getValue() > ttlMillis) {
                it.remove();
            }
        }
    }
}
