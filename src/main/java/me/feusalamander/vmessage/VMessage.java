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
        authors = {"FeuSalamander"},
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
    private static boolean discord;
    private static PapiBridge papi;
    private Configuration configuration;
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
        listeners = new Listeners(proxy, configuration);
        proxy.getEventManager().register(this, listeners);
        logger.info("Vmessage by FeuSalamander is working !");
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
            lastConfigModified = now;
            if (reload()) {
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
