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
        Configuration configuration = Configuration.load(dataDirectory);
        if (configuration == null) {
            return;
        }
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
        metricsFactory.make(this, 16527);
        listeners = new Listeners(proxy, configuration);
        proxy.getEventManager().register(this, listeners);
        CommandManager commandManager = proxy.getCommandManager();
        CommandMeta commandMeta = commandManager.metaBuilder("Vmessage")
                .plugin(this)
                .build();
        SimpleCommand command = new ReloadCommand(configuration);
        commandManager.register(commandMeta, command);
        CommandMeta sendmeta = commandManager.metaBuilder("sendall")
                .plugin(this)
                .build();
        SimpleCommand sendcommand = new SendCommand(this);
        commandManager.register(sendmeta, sendcommand);
        logger.info("Vmessage by FeuSalamander is working !");
        logger.info("[vmessage] 聊天内容颜色码处理：" + configuration.getMessageColors()
                + "（strip=剥掉 / parse=解析 / keep=原样；有 vmessage.color 权限的玩家一律解析）");
        if (!configuration.getNoPapiServers().isEmpty()) {
            logger.info("[vmessage] 这些服收不到 PAPI 变量（走 no-papi-format）："
                    + String.join(", ", configuration.getNoPapiServers()));
        }
    }
    public static boolean isDiscord(){
        return discord;
    }
    /** PAPIProxyBridge 桥接实例，没装插件时为 null。 */
    public static PapiBridge papi(){
        return papi;
    }
}
