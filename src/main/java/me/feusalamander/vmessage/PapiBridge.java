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
                    if (cacheExpiryMillis > 0) {
                        try {
                            setCacheExpiry.invoke(found, cacheExpiryMillis);
                        } catch (Throwable ignored) {
                        }
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
