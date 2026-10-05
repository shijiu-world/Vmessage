package me.feusalamander.vmessage;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.io.File;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

@Plugin(
        id = "vmessage",
        name = "Vmessage",
        version = "1.6.1",
        description = "A velocity plugin that creates a multi server chat for the network",
        authors = {"拾玖世界"},
        dependencies = {
                @Dependency(id = "luckperms", optional = true),
                @Dependency(id = "discord",optional = true),
                @Dependency(id = "papiproxybridge", optional = true)
        }
)
public class VMessage {
    private final ProxyServer proxy;
    private final Logger logger;
    private final Metrics.Factory metricsFactory;
    private final Path dataDirectory;
    public Listeners listeners;
    // ⚠️ 这两个是别的线程（聊天事件、异步 PAPI 回调）也要读的静态字段，
    //    没有 volatile 就没有 happens-before，可能读到「写了一半」的旧值
    private static volatile boolean discord;
    private static volatile PapiBridge papi;
    private Configuration configuration;
    /** 收子服发来的「这条聊天被取消了」信号。 */
    private Suppression suppression;
    /** 自动热重载：记住上次的修改时间，变了才重读 */
    private long lastConfigModified;
    /** 上一次重载失败的原因，给 /vmessage reload 的失败提示用。 */
    private volatile String lastReloadError;

    @Inject
    public VMessage(ProxyServer proxy, Logger logger, Metrics.Factory metricsFactory, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.metricsFactory = metricsFactory;
        this.dataDirectory = dataDirectory;
        this.discord = proxy.getPluginManager().isLoaded("discord");
    }

    @Subscribe
    private void onProxyInitialization(ProxyInitializeEvent event) {
        reportDiscordStub();
        // 命令先注册：万一配置文件读不出来，至少 /vmessage reload 还在，能自救
        CommandManager commandManager = proxy.getCommandManager();
        // ⚠️ 主名必须是小写：Velocity 底层走 Brigadier，literal 节点大小写敏感，
        //    注册成 "Vmessage" 时敲 /vmessage 会「命令不存在」。大写作为别名保留，两种敲法都能用。
        CommandMeta commandMeta = commandManager.metaBuilder("vmessage")
                .aliases("Vmessage")
                .plugin(this)
                .build();
        SimpleCommand command = new ReloadCommand(this);
        commandManager.register(commandMeta, command);
        CommandMeta sendmeta = commandManager.metaBuilder("sendall")
                .plugin(this)
                .build();
        SimpleCommand sendcommand = new SendCommand(this);
        commandManager.register(sendmeta, sendcommand);
        configuration = Configuration.load(dataDirectory);
        if (configuration == null) {
            return;
        }
        createPapi();
        metricsFactory.make(this, 16527);
        // 收子服的「这条聊天被取消了」信号 —— 子服要装 VmessageSuppress 才会有
        suppression = new Suppression(proxy, configuration, logger);
        proxy.getChannelRegistrar().register(Suppression.CHANNEL);
        proxy.getEventManager().register(this, suppression);
        // 抑制信号 / 等待记录里超过有效期的残留，定时清一遍。
        // ⚠️ 不能挂在 auto-reload 那个任务上 —— 它默认关着，关着就不清理了。
        proxy.getScheduler().buildTask(this, suppression::purge)
                .repeat(30L, TimeUnit.SECONDS)
                .schedule();
        listeners = new Listeners(this, proxy, configuration, suppression);
        proxy.getEventManager().register(this, listeners);
        logger.info("Vmessage by 拾玖世界 is working !");
        reportConfig();
        // 自动热重载：改了 config.toml 不用敲命令（默认关，config.toml 里 auto-reload = true 打开）
        startAutoReload();
    }

    /** 按当前配置重建 PAPIProxyBridge 桥接 —— reload 之后 cache/timeout/retry 才能跟着变。 */
    private void createPapi() {
        papi = PapiBridge.create(logger, configuration.getPapiCacheMillis(),
                configuration.getPapiTimeoutMillis(), configuration.getPapiRetryTimes());
        if (papi != null) {
            logger.info("[vmessage] 检测到 PAPIProxyBridge —— format 里可直接用子服 PAPI 变量 (%xxx%)。");
            // 它的 API 实例不一定在 vmessage 初始化时就已注册，起服后复查一次便于排查
            proxy.getScheduler().buildTask(this, () -> logger.info("[vmessage] PAPIProxyBridge 状态："
                            + (papi.available() ? "已连接" : "未取到实例（检查子服是否装了 PAPIProxyBridge-Bukkit）")))
                    .delay(5, TimeUnit.SECONDS)
                    .schedule();
        }
    }

    /**
     * 装了真实 discord 插件时喊一声：Discord 转发其实是不会生效的。
     *
     * <p>本插件自带一份 <code>ooo.foooooooooooo.velocitydiscord.VelocityDiscord</code> 占位实现
     * （getDiscord() 恒返回 null）用来编译；而开关判的是代理里有没有加载 id 为 discord 的插件。
     * Velocity 各插件 ClassLoader 隔离，Vmessage 只能看到自己这份 stub —— 拿不到真实实例，
     * 转发自然不会生效。用 WARN 而不是 ERROR：这不是故障，只是这个功能不可用，其它功能不受影响。
     */
    private void reportDiscordStub() {
        if (!discord) {
            return;
        }
        logger.warn("[vmessage] 检测到代理装了 discord 插件，但 Discord 转发不会生效："
                + "本插件自带的是占位实现（VelocityDiscord stub），各插件 ClassLoader 隔离，"
                + "跨插件拿不到真实的 Discord 实例（其它功能不受影响）。");
    }

    /** 打印当前生效的关键配置（起服和每次 reload 后都打一遍，方便确认到底生效了没）。 */
    private void reportConfig() {
        logger.info("[vmessage] 聊天内容颜色码处理：" + configuration.getMessageColors()
                + "（strip=剥掉 / parse=解析 / keep=原样；有 vmessage.color 权限的玩家一律解析）");
        if (!configuration.getNoPapiServers().isEmpty()) {
            logger.info("[vmessage] 这些服收不到 PAPI 变量（走 no-papi-format）："
                    + String.join(", ", configuration.getNoPapiServers()));
        }
        final String gradient = configuration.getGradientPermission();
        logger.info("[vmessage] 渐变：" + (gradient.isEmpty()
                ? "所有人可用（gradient-permission 留空）"
                : "需要权限 " + gradient + "（没有的人只看到文字）"));
        logger.info("[vmessage] 聊天里的网址："
                + (configuration.isLinkEnabled()
                    ? "显示为 " + configuration.getLinkText() + "（可点击，悬停看完整网址）"
                    : "不处理"));
        reportServerFilter();
        logger.info("[vmessage] 没进服就断开（版本不符/封禁/顶号/登录超时）："
                + (configuration.isDisconnectEnabled()
                    ? "广播「" + configuration.getDisconnectFormat() + "」"
                      + "（扫服或反复重连会刷屏，嫌吵就关掉 Disconnect.enabled）"
                    : "不广播"));
        logger.info("[vmessage] 被 /kick 踢出去：只播 [Kick] 一条，不再补 [Leave]");
        reportAwaitCancel();
    }

    /**
     * 打印「哪些服要等子服的取消信号」。
     * 白名单配成空列表等于全服关掉抑制（商店输入会漏到别的服），所以单独用 WARN 喊一声。
     */
    private void reportAwaitCancel() {
        if (!configuration.isAwaitCancelSignal()) {
            logger.info("[vmessage] 被子服取消的聊天：不处理（照常转发）");
            return;
        }
        final long timeout = configuration.getAwaitCancelTimeoutMillis();
        if (timeout <= 0L) {
            logger.info("[vmessage] 被子服取消的聊天：await-cancel-timeout-millis = 0，不等，收到就转发");
            return;
        }
        final String head = "[vmessage] 被子服取消的聊天：等 " + timeout + " 毫秒，收到抑制信号就不转发";
        final String list = String.join(", ", configuration.getSuppressAwaitServers());
        if (configuration.isSuppressAwaitWhitelist()) {
            if (list.isEmpty()) {
                logger.warn(head + "，但【白名单】是空的 —— 没有服会等信号（等于关掉抑制；"
                        + "想恢复就把服名写进 await-cancel-servers，或把 await-cancel-server-mode 改回 blacklist）");
            } else {
                logger.info(head + "；只等这些服（白名单）：" + list + "；其余服收到就转发，零延迟");
            }
            return;
        }
        logger.info(head + (list.isEmpty()
                ? "（所有服都等；没装 VmessageSuppress 的服可以加进 await-cancel-servers 免去等待）"
                : "；这些服不等（黑名单，收到就转发）：" + list));
    }

    /**
     * 打印「哪些服参与跨服聊天」。
     * 白名单配成空列表是个很容易踩的坑（等于全服断流），所以单独用 WARN 喊一声。
     */
    private void reportServerFilter() {
        final String list = String.join(", ", configuration.getServerFilter());
        if (configuration.isServerFilterWhitelist()) {
            if (list.isEmpty()) {
                logger.warn("[vmessage] 跨服聊天是【白名单】模式但名单是空的 —— 没有任何服会收到跨服聊天"
                        + "（想恢复就让 server-filter 里写上服名，或把 server-filter-mode 改回 blacklist）");
            } else {
                logger.info("[vmessage] 参与跨服聊天的服（白名单）：" + list);
            }
            return;
        }
        if (!list.isEmpty()) {
            logger.info("[vmessage] 不参与跨服聊天的服（黑名单）：" + list);
        }
    }

    /** 定时比对 config.toml 的修改时间，变了就自动重载。 */
    private void startAutoReload() {
        final File file = Configuration.getConfigFile();
        if (file == null) {
            return;
        }
        lastConfigModified = file.lastModified();
        final long seconds = configuration.getAutoReloadIntervalSeconds();
        proxy.getScheduler().buildTask(this, () -> {
            if (!configuration.isAutoReload()) {
                // 中途把 auto-reload 关掉了：任务还在跑，但什么都不做
                lastConfigModified = file.lastModified();
                return;
            }
            final long now = file.lastModified();
            if (now == lastConfigModified) {
                return;
            }
            // ⚠️ 重载成功才记账：先记账的话，读失败时这一版改动就再也不会被检测到（只能重启代理）
            if (reload()) {
                lastConfigModified = now;
                logger.info("[vmessage] 检测到 config.toml 已修改，已自动重载。");
            }
        }).repeat(Math.max(1L, seconds), TimeUnit.SECONDS).schedule();
        if (configuration.isAutoReload()) {
            logger.info("[vmessage] 自动热重载已开启：每 " + seconds + " 秒检查一次 config.toml。");
        }
    }

    /**
     * 热重载：重读 config.toml、重建桥接、打印新配置。
     *
     * @return true = 生效；false = 文件读不出来（TOML 写坏了），旧配置原样保留
     */
    public boolean reload() {
        if (configuration == null) {
            return false;
        }
        if (!configuration.reload()) {
            lastReloadError = configuration.lastError();
            logger.warn("[vmessage] config.toml 读不出来，已保留旧配置：" + lastReloadError);
            return false;
        }
        lastReloadError = null;
        // papiproxybridge 开关 / 缓存 / 超时 / 重试次数都可能改了，重建才生效
        createPapi();
        reportConfig();
        return true;
    }

    /** 上一次重载失败的原因（成功时为 null）。 */
    public String lastReloadError() {
        return lastReloadError;
    }
    public static boolean isDiscord(){
        return discord;
    }
    /** PAPIProxyBridge 桥接实例，没装插件时为 null。 */
    public static PapiBridge papi(){
        return papi;
    }
}
