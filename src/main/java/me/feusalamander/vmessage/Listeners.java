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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.TimeUnit;

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
    /** 有这个权限的玩家，聊天内容里的颜色码会被解析（无视 message-colors 配置） */
    private static final String COLOR_PERMISSION = "vmessage.color";

    /**
     * 渐变要不要看权限。
     * 配置的权限名为空 = 不限制（任何人都能用渐变）；否则没有这个权限的人，
     * 渐变标记会被摘掉、文字留下 —— 既不漏标记，也拿不到渐变效果。
     */
    private boolean allowGradient(final Player p) {
        final String perm = configuration.getGradientPermission();
        return perm.isEmpty() || p.hasPermission(perm);
    }
    private LuckPerms luckPermsAPI;
    private final Configuration configuration;
    private final ProxyServer proxyServer;
    /** 延迟转发要靠它建调度任务（Velocity 的调度器要求传插件实例）。 */
    private final VMessage plugin;
    /** 子服发来的「这条聊天被取消了」信号；null 表示没启用。 */
    private final Suppression suppression;
    /** 记住刚被踢出去的玩家 —— 别让 [Kick] 之后再补一条 [Leave]。 */
    private final KickTracker kickTracker = new KickTracker(10000L);

    Listeners(final VMessage plugin, final ProxyServer proxyServer,
              final Configuration configuration, final Suppression suppression) {
        if (proxyServer.getPluginManager().getPlugin("luckperms").isPresent()) {
            this.luckPermsAPI = LuckPermsProvider.get();
        }
        this.plugin = plugin;
        this.configuration = configuration;
        this.proxyServer = proxyServer;
        this.suppression = suppression;
    }
    @Subscribe
    private void onMessage(final PlayerChatEvent e) {
        if (!configuration.isMessageEnabled()) {
            return;
        }
        final Player p = e.getPlayer();
        final String m = e.getMessage();
        // 这个服不参与跨服聊天：整条都不做 —— 不转发、不跑 Message.commands、不进 Discord。
        // ⚠️ 连原始聊天也不能 deny：all=true 时代理是靠自己重发来保证大家看得到的，
        //    这里 deny 了却不代发，该服玩家的聊天就被彻底吞掉了。
        final Optional<ServerConnection> current = p.getCurrentServer();
        if (current.isEmpty()
                || !configuration.isChatServerAllowed(current.get().getServerInfo().getName())) {
            return;
        }
        if(configuration.isAllEnabled()){
            e.setResult(PlayerChatEvent.ChatResult.denied());
        }
        if (!configuration.isAwaitCancelSignal() || suppression == null) {
            message(p, m);
            return;
        }
        // 先等一小会儿再转发：子服插件可能把这条聊天取消掉（商店输入数量、菜单输入、签到输入……）。
        // 子服那边会回一句「这条被取消了」；到期还没等到，就说明是正常聊天，照常转发。
        // ⚠️ 只推迟「发给别的子服」这一步 —— 玩家自己所在服的聊天是子服自己广播的，不受影响。
        // ⚠️ 命中抑制时整条都不做：跨服转发、Message.commands、Discord 转发一律跳过。
        proxyServer.getScheduler().buildTask(plugin, () -> {
            if (suppression.consume(p.getUniqueId(), m)) {
                return;
            }
            // 等的这会儿玩家可能掉线了、或正在切服（这时 getCurrentServer() 是空的）
            if (!p.isActive() || p.getCurrentServer().isEmpty()) {
                return;
            }
            message(p, m);
        }).delay(configuration.getAwaitCancelTimeoutMillis(), TimeUnit.MILLISECONDS).schedule();
    }
    @Subscribe
    private void onLeave(final DisconnectEvent e) {
        final Player p = e.getPlayer();
        if(e.getPlayer().hasPermission("vmessage.silent.leave")){
            return;
        }
        // 被 /kick 踢出去的：[Kick] 已经广播过一条了，这里不能再补一条 [Leave]
        if (kickTracker.consume(p.getUniqueId())) {
            return;
        }
        // 登录没成功（版本不符、封禁、顶号、登录阶段被拒……）或断开时不在任何服上
        // → 走 [Disconnect]。这一档没有服务器名可用。
        if (!e.getLoginStatus().equals(DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN)
                || p.getCurrentServer().isEmpty()) {
            disconnect(p);
            return;
        }
        if (!configuration.isLeaveEnabled()) {
            return;
        }
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
                    s = FormatCleaner.removeHoles(luckperms(s, p));
                }
                proxyServer.getCommandManager().executeAsync(proxyServer.getConsoleCommandSource(), s);
            }
        if(message.isEmpty())return;
        message = message
                .replace("#player#", p.getUsername())
                .replace("#oldserver#", servername);
        if (luckPermsAPI != null) {
            message = FormatCleaner.finish(luckperms(message, p));
        }
        final String discordRaw = discordRaw(message);
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
        // 记一笔：踢完紧接着的 DisconnectEvent 不该再播一条 [Leave]（onLeave 里消费）
        kickTracker.mark(e.getPlayer().getUniqueId());
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
                    s = FormatCleaner.removeHoles(luckperms(s, p));
                }
                proxyServer.getCommandManager().executeAsync(proxyServer.getConsoleCommandSource(), s);
            }
        if(message.isEmpty())return;
        message = message
                .replace("#player#", p.getUsername())
                .replace("#oldserver#", servername);
        if (luckPermsAPI != null) {
            message = FormatCleaner.finish(luckperms(message, p));
        }
        final String discordRaw = discordRaw(message);
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
                        s = FormatCleaner.removeHoles(luckperms(s, p));
                    }
                    proxyServer.getCommandManager().executeAsync(proxyServer.getConsoleCommandSource(), s);
                }
            if(message.isEmpty())return;
            message = message
                    .replace("#player#", p.getUsername())
                    .replace("#oldserver#", oldservername)
                    .replace("#server#", actualservername);
            if (luckPermsAPI != null) {
                message = FormatCleaner.finish(luckperms(message, p));
            }
        final String discordRaw = discordRaw(message);
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
                        s = FormatCleaner.removeHoles(luckperms(s, p));
                    }
                    proxyServer.getCommandManager().executeAsync(proxyServer.getConsoleCommandSource(), s);
                }
            String message = configuration.getJoinFormat();
            if(message.isEmpty())return;
            message = message
                    .replace("#player#", p.getUsername())
                    .replace("#server#", actualservername);
            if (luckPermsAPI != null) {
                message = FormatCleaner.finish(luckperms(message, p));
            }
        final String discordRaw = discordRaw(message);
            if (configuration.isMinimessageEnabled()) {
                proxyServer.sendMessage(mm.deserialize(message.replace("§", "")));
				if(VMessage.isDiscord())VelocityDiscord.getDiscord().sendMessage(discordRaw);
            } else {
                proxyServer.sendMessage(SERIALIZER.deserialize(message));
				if(VMessage.isDiscord())VelocityDiscord.getDiscord().sendMessage(discordRaw);
            }
        }
    }
    /**
     * 人还没进任何服就被断开 —— 对齐 velocity-chat 的 [disconnect]。
     *
     * 与 [Leave] 的区别：没有服务器名（人根本没落到子服上），所以只有 #player# 和 LuckPerms 的那些占位符。
     * 发给【全体在线玩家】（跟 Join/Leave/Kick 一样走 proxyServer.sendMessage）。
     */
    private void disconnect(final Player p) {
        if (!configuration.isDisconnectEnabled()) {
            return;
        }
        final List<String> cmds = configuration.getDisconnectcmd();
        if (cmds != null && !cmds.isEmpty()) {
            for (String s : cmds) {
                s = s.replace("#player#", p.getUsername());
                if (luckPermsAPI != null) {
                    s = FormatCleaner.removeHoles(luckperms(s, p));
                }
                proxyServer.getCommandManager().executeAsync(proxyServer.getConsoleCommandSource(), s);
            }
        }
        String message = configuration.getDisconnectFormat();
        if (message.isEmpty()) {
            return;
        }
        message = message.replace("#player#", p.getUsername());
        if (luckPermsAPI != null) {
            message = FormatCleaner.finish(luckperms(message, p));
        }
        final String discordRaw = discordRaw(message);
        if (configuration.isMinimessageEnabled()) {
            proxyServer.sendMessage(mm.deserialize(message.replace("§", "")));
            if(VMessage.isDiscord())VelocityDiscord.getDiscord().sendMessage(discordRaw);
        } else {
            proxyServer.sendMessage(SERIALIZER.deserialize(message));
            if(VMessage.isDiscord())VelocityDiscord.getDiscord().sendMessage(discordRaw);
        }
    }

    /**
     * Discord 要的是纯文本，颜色码得剥掉。
     *
     * ⚠️ 上游这里有一句 `proxyServer.sendMessage(Component.text(Arrays.toString(dump2)))` ——
     *    那是调试残留，装了 VelocityDiscord 时每次进/出/切服都会向【全服】广播一句
     *    `[&e, xxx离开了...]` 这样的数组文本。已删。
     */
    private static String discordRaw(final String message) {
        if (!VMessage.isDiscord()) {
            return message;
        }
        final String[] parts = message.replace("&", "§").split("§");
        final StringBuilder dump = new StringBuilder();
        for (final String part : parts) {
            if (part.length() > 1) {
                dump.append(part, 1, part.length());
            }
        }
        return dump.toString();
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
            // 取不到就放哨兵而不是空串 —— 让后面的 collapse 知道"这一段没了"，好把它留出的空格收掉
            auto.put(name, value == null || value.isEmpty() ? FormatCleaner.HOLE : value);
        }
        for (final Map.Entry<String, String> entry : auto.entrySet()) {
            message = message.replace("#" + entry.getKey() + "#", entry.getValue());
        }

        // ③ 兜底：取不到值的占位符一律换成哨兵，不会把 #xxx# 露给玩家
        final String hole = Matcher.quoteReplacement(FormatCleaner.HOLE);
        message = message.replace("#prefix#", FormatCleaner.HOLE).replace("#suffix#", FormatCleaner.HOLE);
        final Matcher cleaner = PLACEHOLDER.matcher(message);
        final StringBuffer sb = new StringBuffer();
        while (cleaner.find()) {
            final String name = cleaner.group(1);
            cleaner.appendReplacement(sb, BUILTIN.contains(name) ? Matcher.quoteReplacement(cleaner.group()) : hole);
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
    /** 把格式串里的 #player# / #server# / #meta# 这些「代理端自己就能填」的占位符替换掉。 */
    private String prepare(final String format, final Player p, final String serverName) {
        final String s = format
                .replace("#player#", p.getUsername())
                .replace("#server#", serverName);
        return luckPermsAPI != null ? luckperms(s, p) : s;
    }
    public void message(final Player p, final String m) {
        final String actualservername;
        {
            final String raw = p.getCurrentServer().orElseThrow().getServerInfo().getName();
            actualservername = configuration.getAliases().contains(raw)
                    ? configuration.getAliases().getString(raw) : raw;
        }
        if(configuration.getMessagecmd() != null&&!configuration.getMessagecmd().isEmpty())
            for(String s : configuration.getMessagecmd()){
                s = s
                        .replace("#player#", p.getUsername())
                        .replace("#server#", actualservername);
                if (luckPermsAPI != null) {
                    s = FormatCleaner.removeHoles(luckperms(s, p));
                }
                proxyServer.getCommandManager().executeAsync(proxyServer.getConsoleCommandSource(), s);
            }
        final String mainFormat = configuration.getMessageFormat();
        // 备用格式：给没装 PAPIProxyBridge-Bukkit 的子服（黑名单里的）用，只认 #xxx#，不解析 %xxx%
        final String altFormat = configuration.getNoPapiFormat();
        if (mainFormat.isEmpty() && altFormat == null) {
            return;
        }
        final boolean permission = p.hasPermission("vmessage.minimessage");
        final String main = mainFormat.isEmpty() ? "" : prepare(mainFormat, p, actualservername);
        final String alt = altFormat == null ? main : prepare(altFormat, p, actualservername);
        // PAPIProxyBridge：把 %xxx% 交给玩家【所在子服】的 PlaceholderAPI 解析（异步）。
        // 这一步在 #message# 替换【之前】做 —— 玩家输入的聊天内容不会被当成占位符解析。
        final PapiBridge papi = VMessage.papi();
        if (papi != null && configuration.isPapiEnabled() && main.indexOf('%') >= 0) {
            papi.format(main, p.getUniqueId()).thenAccept(resolved ->
                    // PAPI 返回的是 § 码，而 Vmessage 用 & 序列化
                    deliver(p, FormatCleaner.finish(resolved.replace('§', '&')),
                            FormatCleaner.finish(alt), m, permission));
            return;
        }
        // 没装桥接 / 手动关掉时也要清一遍，否则 format 里的 %xxx% 会原样显示给玩家
        deliver(p, FormatCleaner.finish(main), FormatCleaner.finish(alt), m, permission);
    }

    /**
     * 把消息发到各子服。
     *
     * @param mainFormat 已经解析完的常规格式（可能含 PAPI 结果），还留着 #message# 没替换
     * @param altFormat  已经解析完的备用格式（从未走 PAPI），给黑名单里的子服用；与主格式相同时就是同一个串
     */
    private void deliver(final Player p, final String mainFormat, final String altFormat,
                         final String m, final boolean permission) {
        final boolean mini = configuration.isMinimessageEnabled();
        // 玩家聊天内容里的颜色码怎么处理；有 vmessage.color 权限的人一律解析
        final String colorMode = p.hasPermission(COLOR_PERMISSION) ? "parse" : configuration.getMessageColors();
        final String content = "strip".equals(colorMode) ? ChatColors.strip(m) : m;
        final Map<String, String> namedColors = configuration.getNamedColors();
        // 渐变单独一道权限（vmessage.gradient），跟 vmessage.color 分开控制
        final boolean gradient = allowGradient(p);
        final Component mainComponent = mainFormat.isEmpty() ? null
                : build(mainFormat, content, mini, permission, colorMode, namedColors, gradient);
        final Component altComponent = altFormat.isEmpty() ? mainComponent
                : altFormat.equals(mainFormat) ? mainComponent
                : build(altFormat, content, mini, permission, colorMode, namedColors, gradient);
        if (VMessage.isDiscord()) {
            final String source = mainFormat.isEmpty() ? altFormat : mainFormat;
            String dump = "";
            String[] dump2 = source.replace("&", "§").split("§");
            for(String string : dump2){
                if(string.length() >1)
                    dump = dump+string.substring(1);
            }
            VelocityDiscord.getDiscord().sendMessage(dump);
        }
        final com.velocitypowered.api.proxy.server.ServerInfo sender =
                p.getCurrentServer().map(ServerConnection::getServerInfo).orElse(null);
        // 变量是在【发送者所在服】算的：那个服自己就在名单里时，%xxx% 一样算不出来，
        // 这种时候所有服统一用备用格式，免得别的服收到一条被抹空的残缺消息。
        final boolean forceAlt = sender != null && configuration.isNoPapiServer(sender.getName());
        final boolean all = configuration.isAllEnabled();
        for (final RegisteredServer server : proxyServer.getAllServers()) {
            // server-filter：名单外的服不参与跨服聊天，一条都不发过去
            if (!configuration.isChatServerAllowed(server.getServerInfo().getName())) {
                continue;
            }
            // all=false：发送者所在服照常收到它自己的原始聊天（子服插件处理），这里跳过
            if (!all && Objects.equals(sender, server.getServerInfo())) {
                continue;
            }
            final boolean noPapi = forceAlt || configuration.isNoPapiServer(server.getServerInfo().getName());
            // 备用格式没配（null）时退回常规格式 —— 宁可有空段，也别整条消息没了
            final Component target = noPapi && altComponent != null ? altComponent : mainComponent;
            if (target == null) {
                continue;
            }
            server.sendMessage(target);
        }
    }

    /** 把一条格式串变成组件：#message# 在这一步才换成玩家真正说的话。 */
    private Component build(final String message, final String content, final boolean mini,
                            final boolean permission, final String colorMode,
                            final Map<String, String> namedColors, final boolean gradient) {
        // 消息内容先做成组件：开了 MiniMessage 且玩家有权限时按 MiniMessage 解析（可以用 <red> 这类语法），
        // 否则按 colorMode 处理（parse 才解析 & 颜色码）。
        // ⚠️ 不能像上游那样把 content 直接拼进格式串再一起反序列化 ——
        //    玩家说的话里带 < > 就会破坏格式串的结构，而且网址也会跟着被解析。
        Component body = mini && permission
                ? mm.deserialize(content.replace("§", ""))
                : messageComponent(colorMode, content, namedColors, gradient);
        // 网址做成 [链接]（可点击、悬停看完整网址）
        body = Linkify.apply(body, configuration);
        final Component parsed = mini ? mm.deserialize(message.replace("§", "")) : SERIALIZER.deserialize(message);
        return parsed.replaceText(net.kyori.adventure.text.TextReplacementConfig.builder()
                .matchLiteral("#message#")
                .replacement(body)
                .build());
    }

    /** 按 colorMode 决定消息内容怎么变成组件：parse 解析颜色码（含 CMI 的 {#RRGGBB} 那套），strip / keep 都按纯文本处理。 */
    private static Component messageComponent(final String colorMode, final String content,
                                              final Map<String, String> namedColors, final boolean gradient) {
        return ChatColors.component(colorMode, content, namedColors, gradient);
    }
}