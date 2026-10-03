package me.feusalamander.vmessage;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.cacheddata.CachedMetaData;
import ooo.foooooooooooo.velocitydiscord.VelocityDiscord;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@SuppressWarnings({"UnstableApiUsage", "deprecation"})
public final class Listeners {
    public static final LegacyComponentSerializer SERIALIZER = LegacyComponentSerializer.builder()
            .character('&')
            .hexColors()
            .build();
    public static final MiniMessage mm = MiniMessage.miniMessage();
    // 内建占位符：这些由插件自己填，不能拿去当 meta 键查
    private static final Set<String> BUILTIN = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "player", "prefix", "suffix", "message", "server", "oldserver")));
    private static final Pattern PLACEHOLDER = Pattern.compile("#([A-Za-z0-9_\\-]+)#");
    /** 消息内容里的颜色码：&4 / §4 / &#FF0000 这类 */
    private static final Pattern COLOR_CODE =
            Pattern.compile("[&§](?:[0-9a-fA-Fk-oK-OrR]|#[0-9a-fA-F]{6})");
    /** 有这个权限的玩家，聊天内容里的颜色码会被解析（无视 message-colors 配置） */
    private static final String COLOR_PERMISSION = "vmessage.color";
    private LuckPerms luckPermsAPI;
    private final Configuration configuration;
    private final ProxyServer proxyServer;

    Listeners(final ProxyServer proxyServer, final Configuration configuration) {
        if (proxyServer.getPluginManager().getPlugin("luckperms").isPresent()) {
            this.luckPermsAPI = LuckPermsProvider.get();
        }
        this.configuration = configuration;
        this.proxyServer = proxyServer;
    }
    @Subscribe
    private void onMessage(final PlayerChatEvent e) {
        if (!configuration.isMessageEnabled()) {
            return;
        }
        if(configuration.isAllEnabled()){
            e.setResult(PlayerChatEvent.ChatResult.denied());
        }
        message(e.getPlayer(), e.getMessage());
    }
    @Subscribe
    private void onLeave(final DisconnectEvent e) {
        if (!configuration.isLeaveEnabled()) {
            return;
        }
        if (!e.getLoginStatus().equals(DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN)){
            return;
        }
        if(e.getPlayer().hasPermission("vmessage.silent.leave")){
            return;
        }
        final Player p = e.getPlayer();
        final Optional<ServerConnection> server = p.getCurrentServer();
        if (server.isEmpty()) {
            return;
        }
        String message = configuration.getLeaveFormat();
        String servername = server.get().getServerInfo().getName();
        if(configuration.getAliases().contains(servername)){
            servername = configuration.getAliases().getString(servername);
        }
        if(configuration.getLeavecmd() != null&&!configuration.getLeavecmd().isEmpty())
            for(String s : configuration.getLeavecmd()){
                s = s
                        .replace("#player#", p.getUsername())
                        .replace("#oldserver#", servername);
                if (luckPermsAPI != null) {
                    s = luckperms(s, p);
                }
                proxyServer.getCommandManager().executeAsync(proxyServer.getConsoleCommandSource(), s);
            }
        if(message.isEmpty())return;
        message = message
                .replace("#player#", p.getUsername())
                .replace("#oldserver#", servername);
        if (luckPermsAPI != null) {
            message = luckperms(message, p);
        }
        String discordRaw;
        if(VMessage.isDiscord()){
            String dump = "";
            String[] dump2 = message.replace("&", "§").split("§");
            proxyServer.sendMessage(Component.text(Arrays.toString(dump2)));
            for(String string : dump2){
                if(string.length() >1)
                    dump = dump+string.substring(1);
            }
            discordRaw = dump;
        } else {
            discordRaw = message;
        }
        if (configuration.isMinimessageEnabled()) {
            proxyServer.sendMessage(mm.deserialize(message.replace("§", "")));
			if(VMessage.isDiscord())VelocityDiscord.getDiscord().sendMessage(discordRaw);
        } else {
            proxyServer.sendMessage(SERIALIZER.deserialize(message));
			if(VMessage.isDiscord())VelocityDiscord.getDiscord().sendMessage(discordRaw);
        }

    }
    @Subscribe
    private void onKick(final KickedFromServerEvent e) {
        if (!configuration.isKickEnabled()) {
            return;
        }
        if (!(e.getResult() instanceof KickedFromServerEvent.DisconnectPlayer)) {
            return;
        }
        if(e.getPlayer().hasPermission("vmessage.silent.leave")){
            return;
        }
        final Player p = e.getPlayer();
        final Optional<ServerConnection> server = p.getCurrentServer();
        if (server.isEmpty()) {
            return;
        }
        String message = configuration.getKickFormat();
        String servername = server.get().getServerInfo().getName();
        if(configuration.getAliases().contains(servername)){
            servername = configuration.getAliases().getString(servername);
        }
        if(configuration.getKickcmd() != null&&!configuration.getKickcmd().isEmpty())
            for(String s : configuration.getKickcmd()){
                s = s
                        .replace("#player#", p.getUsername())
                        .replace("#oldserver#", servername);
                if (luckPermsAPI != null) {
                    s = luckperms(s, p);
                }
                proxyServer.getCommandManager().executeAsync(proxyServer.getConsoleCommandSource(), s);
            }
        if(message.isEmpty())return;
        message = message
                .replace("#player#", p.getUsername())
                .replace("#oldserver#", servername);
        if (luckPermsAPI != null) {
            message = luckperms(message, p);
        }
        String discordRaw;
        if(VMessage.isDiscord()){
            String dump = "";
            String[] dump2 = message.replace("&", "§").split("§");
            proxyServer.sendMessage(Component.text(Arrays.toString(dump2)));
            for(String string : dump2){
                if(string.length() >1)
                    dump = dump+string.substring(1);
            }
            discordRaw = dump;
        } else {
            discordRaw = message;
        }
        if (configuration.isMinimessageEnabled()) {
            proxyServer.sendMessage(mm.deserialize(message.replace("§", "")));
			if(VMessage.isDiscord())VelocityDiscord.getDiscord().sendMessage(discordRaw);
        } else {
            proxyServer.sendMessage(SERIALIZER.deserialize(message));
			if(VMessage.isDiscord())VelocityDiscord.getDiscord().sendMessage(discordRaw);
        }

    }
    @Subscribe
    private void onChange(final ServerPostConnectEvent e) {
        if (!configuration.isChangeEnabled() && !configuration.isJoinEnabled()) {
            return;
        }
        final RegisteredServer pre = e.getPreviousServer();
        final Player p = e.getPlayer();
        final Optional<ServerConnection> serverConnection = e.getPlayer().getCurrentServer();
        if (pre != null&&serverConnection.isPresent()) {
            if (!configuration.isChangeEnabled()) {
                return;
            }
            if(e.getPlayer().hasPermission("vmessage.silent.change")){
                return;
            }
            final ServerConnection actual = serverConnection.get();
            String message = configuration.getChangeFormat();
            String actualservername = actual.getServerInfo().getName();
            if(configuration.getAliases().contains(actualservername)){
                actualservername = configuration.getAliases().getString(actualservername);
            }
            String oldservername = pre.getServerInfo().getName();
            if(configuration.getAliases().containsTable(oldservername)){
                oldservername = configuration.getAliases().getString(oldservername);
            }
            if(configuration.getChangecmd() != null&&!configuration.getChangecmd().isEmpty())
                for(String s : configuration.getChangecmd()){
                    s = s
                            .replace("#player#", p.getUsername())
                            .replace("#oldserver#", oldservername)
                            .replace("#server#", actualservername);
                    if (luckPermsAPI != null) {
                        s = luckperms(s, p);
                    }
                    proxyServer.getCommandManager().executeAsync(proxyServer.getConsoleCommandSource(), s);
                }
            if(message.isEmpty())return;
            message = message
                    .replace("#player#", p.getUsername())
                    .replace("#oldserver#", oldservername)
                    .replace("#server#", actualservername);
            if (luckPermsAPI != null) {
                message = luckperms(message, p);
            }
            String discordRaw;
            if(VMessage.isDiscord()){
                String dump = "";
                String[] dump2 = message.replace("&", "§").split("§");
                proxyServer.sendMessage(Component.text(Arrays.toString(dump2)));
                for(String string : dump2){
                    if(string.length() >1)
                        dump = dump+string.substring(1);
                }
                discordRaw = dump;
            } else {
                discordRaw = message;
            }
            if (configuration.isMinimessageEnabled()) {
                proxyServer.sendMessage(mm.deserialize(message.replace("§", "")));
				 if(VMessage.isDiscord())VelocityDiscord.getDiscord().sendMessage(discordRaw);
            } else {
                proxyServer.sendMessage(SERIALIZER.deserialize(message));
				 if(VMessage.isDiscord())VelocityDiscord.getDiscord().sendMessage(discordRaw);
            }
        } else if (serverConnection.isPresent()){
            if (!configuration.isJoinEnabled()) {
                return;
            }
            if(e.getPlayer().hasPermission("vmessage.silent.join")){
                return;
            }
            String actualservername = serverConnection.get().getServerInfo().getName();
            if(configuration.getAliases().contains(actualservername)){
                actualservername = configuration.getAliases().getString(actualservername);
            }
            if(configuration.getJoincmd() != null&&!configuration.getJoincmd().isEmpty())
                for(String s : configuration.getJoincmd()){
                    s = s
                            .replace("#player#", p.getUsername())
                            .replace("#server#", actualservername);
                    if (luckPermsAPI != null) {
                        s = luckperms(s, p);
                    }
                    proxyServer.getCommandManager().executeAsync(proxyServer.getConsoleCommandSource(), s);
                }
            String message = configuration.getJoinFormat();
            if(message.isEmpty())return;
            message = message
                    .replace("#player#", p.getUsername())
                    .replace("#server#", actualservername);
            if (luckPermsAPI != null) {
                message = luckperms(message, p);
            }
            String discordRaw;
            if(VMessage.isDiscord()){
                String dump = "";
                String[] dump2 = message.replace("&", "§").split("§");
                proxyServer.sendMessage(Component.text(Arrays.toString(dump2)));
                for(String string : dump2){
                    if(string.length() >1)
                        dump = dump+string.substring(1);
                }
                discordRaw = dump;
            } else {
                discordRaw = message;
            }
            if (configuration.isMinimessageEnabled()) {
                proxyServer.sendMessage(mm.deserialize(message.replace("§", "")));
				if(VMessage.isDiscord())VelocityDiscord.getDiscord().sendMessage(discordRaw);
            } else {
                proxyServer.sendMessage(SERIALIZER.deserialize(message));
				if(VMessage.isDiscord())VelocityDiscord.getDiscord().sendMessage(discordRaw);
            }
        }
    }
    private String luckperms(String message, final Player p) {
        final CachedMetaData data = luckPermsAPI.getPlayerAdapter(Player.class).getMetaData(p);
        final String prefix = data.getPrefix();
        final String suffix = data.getSuffix();

        if (message.contains("#prefix#") && prefix != null) {
            message = message.replace("#prefix#", prefix);
        }
        if (message.contains("#suffix#") && suffix != null) {
            message = message.replace("#suffix#", suffix);
        }

        // ① 显式映射：[Custom-Meta] 里写的 "占位符名 = meta键"（可选，用来起别名）
        final Map<String, String> values = new LinkedHashMap<>();
        final Set<String> resolved = new HashSet<>();
        for (final Map.Entry<String, String> entry : configuration.getCustomMeta().entrySet()) {
            final String placeholder = "#" + entry.getKey() + "#";
            resolved.add(entry.getKey());
            if (!message.contains(placeholder)) {
                continue;
            }
            final String value = metaValue(data, entry.getValue());
            if (value != null) {
                values.put(placeholder, value);
            }
        }
        for (final Map.Entry<String, String> entry : values.entrySet()) {
            message = message.replace(entry.getKey(), entry.getValue());
        }

        // ② 直连：剩下的 #xxx# 直接当作 LuckPerms 的 meta 键名去查，不用再配 [Custom-Meta]
        final Map<String, String> auto = new LinkedHashMap<>();
        final Matcher finder = PLACEHOLDER.matcher(message);
        while (finder.find()) {
            final String name = finder.group(1);
            if (BUILTIN.contains(name) || resolved.contains(name) || auto.containsKey(name)) {
                continue;
            }
            final String value = metaValue(data, name);
            auto.put(name, value == null ? "" : value);
        }
        for (final Map.Entry<String, String> entry : auto.entrySet()) {
            message = message.replace("#" + entry.getKey() + "#", entry.getValue());
        }

        // ③ 兜底：取不到值的占位符一律抹成空串，不会把 #xxx# 露给玩家
        message = message.replace("#prefix#", "").replace("#suffix#", "");
        final Matcher cleaner = PLACEHOLDER.matcher(message);
        final StringBuffer sb = new StringBuffer();
        while (cleaner.find()) {
            final String name = cleaner.group(1);
            cleaner.appendReplacement(sb, BUILTIN.contains(name) ? Matcher.quoteReplacement(cleaner.group()) : "");
        }
        cleaner.appendTail(sb);
        return sb.toString();
    }

    /**
     * LuckPerms 的 meta 键在入库时会被强制小写，这里原样查一次、再小写查一次，两种写法都能命中。
     */
    private static String metaValue(final CachedMetaData data, final String key) {
        final String direct = data.getMetaValue(key);
        if (direct != null) {
            return direct;
        }
        final String lower = key.toLowerCase(Locale.ROOT);
        return lower.equals(key) ? null : data.getMetaValue(lower);
    }
    public void message(final Player p, final String m) {
        String actualservername = p.getCurrentServer().orElseThrow().getServerInfo().getName();
        if(configuration.getAliases().contains(actualservername)){
            actualservername = configuration.getAliases().getString(actualservername);
        }
        if(configuration.getMessagecmd() != null&&!configuration.getMessagecmd().isEmpty())
            for(String s : configuration.getMessagecmd()){
                s = s
                        .replace("#player#", p.getUsername())
                        .replace("#server#", actualservername);
                if (luckPermsAPI != null) {
                    s = luckperms(s, p);
                }
                proxyServer.getCommandManager().executeAsync(proxyServer.getConsoleCommandSource(), s);
            }
        String message = configuration.getMessageFormat();
        if(message.isEmpty())return;
        final boolean permission = p.hasPermission("vmessage.minimessage");
        message = message
                .replace("#player#", p.getUsername())
                .replace("#server#", actualservername);
        if (luckPermsAPI != null) {
            message = luckperms(message, p);
        }
        // PAPIProxyBridge：把 %xxx% 交给玩家【所在子服】的 PlaceholderAPI 解析（异步）。
        // 这一步在 #message# 替换【之前】做 —— 玩家输入的聊天内容不会被当成占位符解析。
        final PapiBridge papi = VMessage.papi();
        if (papi != null && configuration.isPapiEnabled() && message.indexOf('%') >= 0) {
            papi.format(message, p.getUniqueId()).thenAccept(resolved ->
                    // PAPI 返回的是 § 码，而 Vmessage 用 & 序列化
                    deliver(p, PapiBridge.stripUnresolved(resolved.replace('§', '&')), m, permission));
            return;
        }
        // 没装桥接 / 手动关掉时也要清一遍，否则 format 里的 %xxx% 会原样显示给玩家
        deliver(p, PapiBridge.stripUnresolved(message), m, permission);
    }

    private void deliver(final Player p, String message, final String m, final boolean permission) {
        final boolean mini = configuration.isMinimessageEnabled();
        // 玩家聊天内容里的颜色码怎么处理；有 vmessage.color 权限的人一律解析
        final String colorMode = p.hasPermission(COLOR_PERMISSION) ? "parse" : configuration.getMessageColors();
        final String content = "strip".equals(colorMode) ? stripColors(m) : m;
        Component finalMessage;
        if (mini && permission) {
            // 开了 MiniMessage 且玩家有权限：消息内容直接参与解析（可以用 <red> 这类语法）
            finalMessage = mm.deserialize(message.replace("#message#", content).replace("§", ""));
        } else {
            finalMessage = mini ? mm.deserialize(message.replace("§", "")) : SERIALIZER.deserialize(message);
            finalMessage = finalMessage.replaceText(net.kyori.adventure.text.TextReplacementConfig.builder()
                    .matchLiteral("#message#")
                    .replacement(messageComponent(mini, colorMode, content))
                    .build());
        }
        String discordRaw;
        if(VMessage.isDiscord()){
            String dump = "";
            String[] dump2 = message.replace("&", "§").split("§");
            proxyServer.sendMessage(Component.text(Arrays.toString(dump2)));
            for(String string : dump2){
                if(string.length() >1)
                    dump = dump+string.substring(1);
            }
            discordRaw = dump;
        } else {
            discordRaw = message;
        }
        if(configuration.isAllEnabled()){
            proxyServer.sendMessage(finalMessage);
			if(VMessage.isDiscord())VelocityDiscord.getDiscord().sendMessage(discordRaw);
        }else {
            final Component FMessage = finalMessage;
            proxyServer.getAllServers().forEach(server -> {
                if (!Objects.equals(p.getCurrentServer().map(ServerConnection::getServerInfo).orElse(null), server.getServerInfo())) {
                    server.sendMessage(FMessage);
					if(VMessage.isDiscord())VelocityDiscord.getDiscord().sendMessage(discordRaw);
                }
            });
        }

    }

    /** 剥掉消息内容里的颜色码。对应 CMI 的 CleanUp：没颜色权限时把 &4 这类去掉，而不是把字面量显示出来。 */
    private static String stripColors(final String s) {
        return s == null ? "" : COLOR_CODE.matcher(s).replaceAll("");
    }

    /** 按 colorMode 决定消息内容怎么变成组件：parse 解析颜色码，strip / keep 都按纯文本处理。 */
    private static Component messageComponent(final boolean mini, final String colorMode, final String content) {
        if ("parse".equals(colorMode)) {
            return mini ? mm.deserialize(content) : SERIALIZER.deserialize(content.replace('§', '&'));
        }
        return Component.text(content);
    }
}