package me.feusalamander.vmessage;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import org.slf4j.Logger;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 收子服发来的「这条聊天被取消掉了」信号。
 *
 * <p>为什么需要它：代理在收到玩家聊天的【同一时刻】就把消息发给了别的子服（System Chat，
 * 不经任何后端聊天事件），而子服插件的 setCancelled(true) 发生在【之后】，代理无从知晓 ——
 * 于是别的子服会看到玩家在商店里输入的「64」。
 *
 * <p>配套插件 VmessageSuppress 装在每个子服，它抢在所有人前面记下玩家真正输入的原文，
 * 等所有人跑完（MONITOR）发现事件被取消了，就往代理发一个信号；代理收到后把这条标记为
 * 「别转发」，转发任务到期时对不上号就整条丢弃。
 *
 * <p>⚠️ 时序：等待窗口（默认 100 毫秒）比信号的有效期（TTL ≥ 3 秒）短得多，所以信号【迟到】
 * 是常态 —— 一次等待超时之后到的信号，是上一次留下的陈旧信号，绝不能再拿去拦下一条聊天
 * （玩家紧接着又打了一句相同内容时被整条吞掉）。因此这里用 System.nanoTime() 这个单调时钟
 * 同时记下「本次等待开始的时刻」和「信号到达的时刻」，只有到达时刻 ≥ 开始时刻才算本次命中。
 */
public final class Suppression {

    /** 通道 vmessage:suppress，与子服配套插件约定好的。 */
    public static final MinecraftChannelIdentifier CHANNEL =
            MinecraftChannelIdentifier.create("vmessage", "suppress");

    /** 键 = uuid + '\0' + 原文 → 信号【到达】时刻（System.nanoTime()）。 */
    private final Map<String, Long> marks = new ConcurrentHashMap<>();
    /** 键 = uuid + '\0' + 原文 → 本次【开始等待】的时刻（System.nanoTime()）。 */
    private final Map<String, Long> waits = new ConcurrentHashMap<>();
    /** 只把第一个信号打成 INFO，之后走 debug。 */
    private final AtomicBoolean first = new AtomicBoolean(true);
    private final ProxyServer proxy;
    private final Configuration configuration;
    private final Logger logger;

    Suppression(final ProxyServer proxy, final Configuration configuration, final Logger logger) {
        this.proxy = proxy;
        this.configuration = configuration;
        this.logger = logger;
    }

    @Subscribe
    public void onPluginMessage(final PluginMessageEvent e) {
        if (!CHANNEL.equals(e.getIdentifier())) {
            return;
        }
        // 只认子服发来的，玩家端发的一概不理
        if (!(e.getSource() instanceof ServerConnection)) {
            return;
        }
        // 这是给代理看的，不要再透传给客户端
        e.setResult(PluginMessageEvent.ForwardResult.handled());
        final String from = ((ServerConnection) e.getSource()).getServerInfo().getName();
        try (DataInputStream in = new DataInputStream(e.dataAsInputStream())) {
            final String uuid = in.readUTF();
            final String message = in.readUTF();
            // 校验来源：这个 uuid 现在必须真的在【发信号的那个服】上，
            // 否则是别的子服伪造的（或玩家已经切服了，信号已过期），一律忽略。
            if (!fromServer(uuid, from)) {
                logger.debug("[vmessage] 抑制信号的来源对不上（uuid={}，来自 {}），已忽略。", uuid, from);
                return;
            }
            mark(uuid, message);
        } catch (final IOException ex) {
            logger.warn("[vmessage] 收到一条读不懂的抑制信号，已忽略：" + ex);
        }
    }

    /**
     * 信号里的 uuid 是不是来自 fromServer 这个服上的在线玩家。
     *
     * <p>保守实现：查不到玩家（掉线了 / 正在切服）、uuid 解析不了，一律放行 ——
     * 宁可多拦一条，也别因为 API 取不到人就把真正的抑制信号丢掉。
     */
    private boolean fromServer(final String uuid, final String fromServer) {
        try {
            final Optional<Player> player = proxy.getPlayer(UUID.fromString(uuid));
            if (player.isEmpty()) {
                return true;
            }
            final Optional<ServerConnection> current = player.get().getCurrentServer();
            if (current.isEmpty()) {
                return true;
            }
            return current.get().getServerInfo().getName().equals(fromServer);
        } catch (final RuntimeException ex) {
            return true;
        }
    }

    private void mark(final String uuid, final String message) {
        marks.put(key(uuid, message), System.nanoTime());
        // 第一个信号值得打一条 INFO：这是「子服插件装好了、链路是通的」的验收标志。
        // 之后的都走 debug —— 商店输入一天几百次，不能每次都刷屏。
        if (first.compareAndSet(true, false)) {
            logger.info("[vmessage] 收到第一个抑制信号 —— 子服 VmessageSuppress 链路正常。");
        } else {
            logger.debug("[vmessage] 抑制信号：{} 的一条聊天被子服取消。", uuid);
        }
    }

    /**
     * 开始等一次：记下本次等待的开始时刻。
     *
     * <p>必须在【调度延迟任务之前】调用。顺手丢掉上一轮残留的陈旧信号 ——
     * 等待窗口（≤ 1 秒）比信号有效期（≥ 3 秒）短，超时之后才到的信号就是这种残留，
     * 留着会把下一条内容相同的正常聊天整条吞掉。
     */
    void beginWait(final UUID uuid, final String message) {
        final String key = key(uuid.toString(), message);
        waits.put(key, System.nanoTime());
        marks.remove(key);
    }

    /** 结束一次等待（超时、决定照常转发）：把这一轮的记录清干净。 */
    void endWait(final UUID uuid, final String message) {
        final String key = key(uuid.toString(), message);
        waits.remove(key);
        marks.remove(key);
    }

    /**
     * 取一次：这条消息是不是【本次等待期间】被子服取消掉了。
     * 取走就没了 —— 不管命中与否，两边（信号、本次等待）的记录都一起清掉。
     */
    boolean consume(final UUID uuid, final String message) {
        final String key = key(uuid.toString(), message);
        final Long arrived = marks.remove(key);
        final Long started = waits.remove(key);
        if (arrived == null || started == null) {
            return false;
        }
        // ① 信号必须是在本次等待开始【之后】才到的：早于开始时刻的是上一轮的陈旧信号，不算数
        // ② 还要在有效期内（TTL 比等待窗口长得多，取到基本都满足；防的是极端卡顿留下的老条目）
        return arrived >= started && System.nanoTime() - arrived <= ttlNanos();
    }

    /**
     * 清掉超过 TTL 的残留条目。由 VMessage 每 30 秒调一次 ——
     * 以前是在每次收到信号时顺手清、且有 size < 64 的短路，条目少的时候永远不清。
     */
    void purge() {
        final long deadline = System.nanoTime() - ttlNanos();
        purgeOlderThan(marks, deadline);
        purgeOlderThan(waits, deadline);
    }

    private static void purgeOlderThan(final Map<String, Long> map, final long deadline) {
        for (final Iterator<Map.Entry<String, Long>> it = map.entrySet().iterator(); it.hasNext(); ) {
            final Map.Entry<String, Long> entry = it.next();
            final Long value = entry.getValue();
            if (value == null || value < deadline) {
                it.remove();
            }
        }
    }

    /**
     * 键：uuid + '\0' + 原文。
     * ⚠️ 中间必须加分隔符 —— 不加的话得靠 UUID 定长 36 位才不会串（"…ab" + "c" 和 "…a" + "bc" 撞车），
     *     '\0' 不会出现在聊天内容里，安全。
     */
    private static String key(final String uuid, final String message) {
        return uuid + '\u0000' + (message == null ? "" : message);
    }

    /** 信号的有效期（纳秒）：给足 3 倍等待窗口，最少 3 秒。 */
    private long ttlNanos() {
        return TimeUnit.MILLISECONDS.toNanos(Math.max(3000L, configuration.getAwaitCancelTimeoutMillis() * 3L));
    }
}
