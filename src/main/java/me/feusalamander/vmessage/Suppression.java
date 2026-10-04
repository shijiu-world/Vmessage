package me.feusalamander.vmessage;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import org.slf4j.Logger;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
 * <p>信号里带的是 (玩家 UUID, 消息原文)。UUID 定长 36 位，直接拼起来不会串。
 */
public final class Suppression {

    /** 通道 vmessage:suppress，与子服配套插件约定好的。 */
    public static final MinecraftChannelIdentifier CHANNEL =
            MinecraftChannelIdentifier.create("vmessage", "suppress");

    /** 键 = uuid + 原文 → 过期时刻（毫秒）。 */
    private final Map<String, Long> marks = new ConcurrentHashMap<>();
    /** 只把第一个信号打成 INFO，之后走 debug。 */
    private final java.util.concurrent.atomic.AtomicBoolean first =
            new java.util.concurrent.atomic.AtomicBoolean(true);
    private final Configuration configuration;
    private final Logger logger;

    Suppression(final Configuration configuration, final Logger logger) {
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
        try (DataInputStream in = new DataInputStream(e.dataAsInputStream())) {
            final String uuid = in.readUTF();
            final String message = in.readUTF();
            mark(uuid, message);
        } catch (final IOException ex) {
            logger.warn("[vmessage] 收到一条读不懂的抑制信号，已忽略：" + ex);
        }
    }

    private void mark(final String uuid, final String message) {
        purge();
        marks.put(uuid + message, System.currentTimeMillis() + ttl());
        // 第一个信号值得打一条 INFO：这是「子服插件装好了、链路是通的」的验收标志。
        // 之后的都走 debug —— 商店输入一天几百次，不能每次都刷屏。
        if (first.compareAndSet(true, false)) {
            logger.info("[vmessage] 收到第一个抑制信号 —— 子服 VmessageSuppress 链路正常。");
        } else {
            logger.debug("[vmessage] 抑制信号：{} 的一条聊天被子服取消。", uuid);
        }
    }

    /** 顺手清掉过期的，免得没被取走的条目一直堆着。 */
    private void purge() {
        if (marks.size() < 64) {
            return;
        }
        final long now = System.currentTimeMillis();
        for (final Iterator<Map.Entry<String, Long>> it = marks.entrySet().iterator(); it.hasNext(); ) {
            if (it.next().getValue() < now) {
                it.remove();
            }
        }
    }

    /**
     * 取一次：这条消息是不是被子服取消掉了。
     * 取走就没了 —— 同一个人连着发两条一模一样的话，也只会拦住后到的那条对应的信号。
     */
    boolean consume(final UUID uuid, final String message) {
        final Long expire = marks.remove(uuid + message);
        return expire != null && expire > System.currentTimeMillis();
    }

    /** 信号的有效期：给足 3 倍等待窗口，最少 3 秒。 */
    private long ttl() {
        return Math.max(3000L, configuration.getAwaitCancelTimeoutMillis() * 3L);
    }
}
