package me.feusalamander.vmessage;

import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * PAPIProxyBridge（William278）软依赖封装。
 *
 * 全部走反射 —— 服上没装 PAPIProxyBridge 时不会抛 NoClassDefFoundError，
 * 只是 %xxx% 占位符不被解析而已。
 *
 * 有了它，代理端就能读到子服 PlaceholderAPI 的任意变量
 * （称号 %playerTitle_use%、公会 %legendaryguild_guild% 等），
 * 不需要再把数据写进 LuckPerms meta、也不需要代理端 LP 切 MySQL。
 *
 * <p>缓存（cacheExpiry，对应 config.toml 的 papi-cache-millis）是桥接自己做的，
 * 存在代理端内存里，结构是两层：
 * <pre>
 *   外层键 = (说话玩家的 UUID, 目标玩家 UUID, 玩家所在服务器名)
 *   内层键 = 待解析的文本（我们传进去的 format 模板）
 *   值     = 解析结果，TTL = cacheExpiry，逐条过期
 * </pre>
 * 因为外层键里有玩家 UUID，%playerTitle_use% 这种「人人不同」的变量不会串号，
 * 每个人一份。代价是【变更最多延迟一个 TTL 才生效】——玩家刚换称号，
 * 30 秒内跨服聊天里还显示旧的。缓存只在玩家下线时被主动清掉；
 * 切服不用清，因为服务器名是键的一部分，换服自然就重新取了。
 */
public final class PapiBridge {
    private static final String API = "net.william278.papiproxybridge.api.PlaceholderAPI";

    private final Logger logger;
    private final Method getInstance;
    private final Method setCacheExpiry;
    private final Method setRetryTimes;
    private final Method format;
    private final long cacheExpiryMillis;
    private final long timeoutMillis;
    private final int retryTimes;
    private Object instance;
    private boolean warned;

    private PapiBridge(final Logger logger, final Class<?> apiClass,
                       final long cacheExpiryMillis, final long timeoutMillis,
                       final int retryTimes) throws Exception {
        this.logger = logger;
        this.getInstance = apiClass.getMethod("getInstance");
        this.setCacheExpiry = apiClass.getMethod("setCacheExpiry", long.class);
        this.setRetryTimes = apiClass.getMethod("setRetryTimes", int.class);
        this.format = apiClass.getMethod("formatPlaceholders", String.class, UUID.class);
        this.cacheExpiryMillis = cacheExpiryMillis;
        this.timeoutMillis = timeoutMillis;
        this.retryTimes = retryTimes;
    }

    public static PapiBridge create(final Logger logger, final long cacheExpiryMillis,
                                    final long timeoutMillis, final int retryTimes) {
        try {
            return new PapiBridge(logger, Class.forName(API), cacheExpiryMillis, timeoutMillis, retryTimes);
        } catch (Throwable t) {
            logger.info("[vmessage] 未检测到 PAPIProxyBridge —— format 里的 %xxx% 占位符不会被解析。");
            return null;
        }
    }

    /**
     * 插件加载顺序不保证（Vmessage 可能比 PAPIProxyBridge 先初始化），
     * 所以每次用之前再取一次实例，取到就缓存住。
     */
    private synchronized Object api() {
        if (instance == null) {
            try {
                final Object found = getInstance.invoke(null);
                if (found != null) {
                    // ⚠️ 0 也必须传下去：桥接内部是「cacheExpiry > 0 才用缓存」，
                    // 0 就是关缓存（每条消息都实时问子服）。以前这里写成 >0 才设置，
                    // 配 0 时会静默沿用桥接自己默认的 30000ms，等于配了没生效。
                    // 负数会让它抛 IllegalArgumentException，夹一下。
                    try {
                        setCacheExpiry.invoke(found, Math.max(0L, cacheExpiryMillis));
                    } catch (Throwable ignored) {
                    }
                    // 默认会重试 3 次。子服没装 Bukkit 版时每次都白等一整轮超时，
                    // 而失败结果又不进缓存 —— 关掉重试，最坏只等一次。
                    try {
                        setRetryTimes.invoke(found, retryTimes);
                    } catch (Throwable ignored) {
                    }
                    instance = found;
                }
            } catch (Throwable t) {
                instance = null;
            }
        }
        return instance;
    }

    public boolean available() {
        return api() != null;
    }

    /**
     * 把字符串交给玩家所在子服的 PlaceholderAPI 解析。
     * 永远返回已完成的 future：没装插件 / 调用失败 / 超时，都原样返回输入。
     */
    public CompletableFuture<String> format(final String text, final UUID uuid) {
        final Object api = api();
        if (api == null) {
            return CompletableFuture.completedFuture(text);
        }
        try {
            @SuppressWarnings("unchecked")
            final CompletableFuture<String> future =
                    (CompletableFuture<String>) format.invoke(api, text, uuid);
            if (timeoutMillis > 0) {
                future.orTimeout(timeoutMillis, TimeUnit.MILLISECONDS);
            }
            return future.exceptionally(t -> {
                if (!warned) {
                    logger.warn("[vmessage] PAPIProxyBridge 解析失败，降级为不解析: " + t);
                    warned = true;
                }
                return text;
            });
        } catch (Throwable t) {
            if (!warned) {
                logger.warn("[vmessage] PAPIProxyBridge 调用异常，降级为不解析: " + t);
                warned = true;
            }
            return CompletableFuture.completedFuture(text);
        }
    }
}
