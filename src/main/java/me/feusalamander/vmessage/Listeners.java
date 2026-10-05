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
    /**
     * 给 Discord 剥颜色码用（&c / &#RRGGBB / &#RGB / &x&F&F&0&0&0&0 / §c 都算）。
     *
     * ⚠️ 单字符那一档后面加了 `(?!=)`：网址里 `?a=1&b=2` 的 `&b` 正好是合法颜色码字符，
     *    不加这个断言会把 `&b=2` 整段吃掉，链接直接坏了。真正的颜色码后面紧跟 `=` 的情况几乎不存在。
     */
    private static final Pattern DISCORD_COLOR = Pattern.compile(
            "[&§](?:#[0-9a-fA-F]{6}"
                    + "|#[0-9a-fA-F]{3}(?![0-9a-fA-F])"
                    + "|x(?:[&§][0-9a-fA-F]){6}"
                    + "|[0-9a-fA-Fk-oK-OrR](?!=))");
    /** 组件解析失败只报一次，别每条消息都刷屏。 */
    private static final java.util.concurrent.atomic.AtomicBoolean warnedParse =
            new java.util.concurrent.atomic.AtomicBoolean(true);
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
        final Player p = e.getPlayer();
        final Optional<ServerConnection> current = p.getCurrentServer();
        // 这个服不参与跨服聊天（或 Message.enabled 关了）：整条都不做 ——
        // 不转发、不跑 Message.commands、不进 Discord。
        // ⚠️ 连原始聊天也不能 deny：all=true 时代理是靠自己重发来保证大家看得到的，
        //    这里 deny 了却不代发，该服玩家的聊天就被彻底吞掉了。
        if (!canSpeakHere(p)) {
            return;
        }
        final String m = e.getMessage();
        if(configuration.isAllEnabled()){
            e.setResult(PlayerChatEvent.ChatResult.denied());
            // ⚠️ all=true 时代理自己重发、原始聊天根本不会发到子服 —— 子服也就不可能产生抑制信号，
            //    这时候再等一个超时纯属白等（每条聊天都平白多出 await-cancel-timeout-millis 的延迟）。
            message(p, m);
            return;
        }
        // 这个服不排队等信号 —— 要么没装 VmessageSuppress（名单里排除了），要么功能关了。
        // 直接转发，别的服看到消息零延迟；代价是被子服取消的聊天（商店输入等）会漏过去。
        if (suppression == null
                || !configuration.isAwaitCancelServer(current.get().getServerInfo().getName())) {
            message(p, m);
            return;
        }
        // 先等一小会儿再转发：子服插件可能把这条聊天取消掉（商店输入数量、菜单输入、签到输入……）。
        // 子服那边会回一句「这条被取消了」；到期还没等到，就说明是正常聊天，照常转发。
        // ⚠️ 只推迟「发给别的子服」这一步 —— 玩家自己所在服的聊天是子服自己广播的，不受影响。
        // ⚠️ 命中抑制时整条都不做：跨服转发、Message.commands、Discord 转发一律跳过。
        // ⚠️ 登记必须在调度【之前】：记下本次等待的开始时刻，并丢掉上一轮迟到的陈旧信号
        //    （等待窗口比信号有效期短得多，超时之后才到的信号会污染下一条内容相同的聊天）。
        suppression.beginWait(p.getUniqueId(), m);
        proxyServer.getScheduler().buildTask(plugin, () -> {
            if (suppression.consume(p.getUniqueId(), m)) {
                return;
            }
            // 超时了，按正常聊天处理 —— 先把这一轮的记录清干净，别让迟到的信号污染下一次
            suppression.endWait(p.getUniqueId(), m);
            // 等的这会儿玩家可能掉线了、或正在切服（这时 getCurrentServer() 是空的）
            if (!p.isActive() || p.getCurrentServer().isEmpty()) {
                return;
            }
            message(p, m);
        }).delay(configuration.getAwaitCancelTimeoutMillis(), TimeUnit.MILLISECONDS).schedule();
    }

    /**
     * 这个玩家现在说的一句话要不要走跨服聊天 —— Message.enabled 与 server-filter 两道闸。
     *
     * <p>/sendall 也得过这两道闸：不然玩家能从一个「不参与跨服聊天」的服（或 Message.enabled = false 时）
     * 把话喊到全服，等于绕过了配置。
     */
    public boolean canSpeakHere(final Player p) {
        if (!configuration.isMessageEnabled()) {
            return false;
        }
        final Optional<ServerConnection> current = p.getCurrentServer();
        // 还没落到子服上（刚登录、正在切服）：拿不到服务器名，按「不参与」处理
        return current.isPresent()
                && configuration.isChatServerAllowed(current.get().getServerInfo().getName());
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
			trySendDiscord(discordRaw);
        } else {
            proxyServer.sendMessage(SERIALIZER.deserialize(message));
			trySendDiscord(discordRaw);
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
			trySendDiscord(discordRaw);
        } else {
            proxyServer.sendMessage(SERIALIZER.deserialize(message));
			trySendDiscord(discordRaw);
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
            // ⚠️ 别名是 String（[Aliases] 里 "生存" = "survival" 这种），要用 contains 而不是 containsTable
            if(configuration.getAliases().contains(oldservername)){
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
				 trySendDiscord(discordRaw);
            } else {
                proxyServer.sendMessage(SERIALIZER.deserialize(message));
				 trySendDiscord(discordRaw);
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
				trySendDiscord(discordRaw);
            } else {
                proxyServer.sendMessage(SERIALIZER.deserialize(message));
				trySendDiscord(discordRaw);
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
            trySendDiscord(discordRaw);
        } else {
            proxyServer.sendMessage(SERIALIZER.deserialize(message));
            trySendDiscord(discordRaw);
        }
    }

    /**
     * Discord 要的是纯文本，颜色码得剥掉。
     *
     * ⚠️ 上游原本的写法是 `split("§")` 之后对每段 `substring(1)` —— 那只在字符串【以颜色码开头】
     *    时才对得上；开头是普通文字时会把第一个字当成颜色码吃掉（"史蒂夫离开了" → "蒂夫离开了"）。
     *    前缀为空、占位符被折叠掉之后格式就未必以 & 开头了，这个坑很容易踩到。
     *    改成直接删颜色码，一字不动地保留正文。
     *
     * ⚠️ 另外上游这里还有一句 `proxyServer.sendMessage(Component.text(Arrays.toString(dump2)))` ——
     *    那是调试残留，装了 VelocityDiscord 时每次进/出/切服都会向【全服】广播一句
     *    `[&e, xxx离开了...]` 这样的数组文本。已删。
     */
    private static String discordRaw(final String message) {
        return VMessage.isDiscord() ? stripColors(message) : message;
    }

    /**
     * 往 Discord 转发一句（装了 discord 插件、且开关打开时才真发）。
     *
     * <p>⚠️ 为什么不能直接 `VelocityDiscord.getDiscord().sendMessage(...)`：
     * 本插件自带的是【占位实现】（ooo.foooooooooooo.velocitydiscord.VelocityDiscord 恒返回 null），
     * 而开关判的是「代理里有没有加载 id 为 discord 的插件」。Velocity 各插件 ClassLoader 隔离，
     * Vmessage 只能看到自己这份 stub —— 一旦代理装了真实的 discord 插件，这里必然 NPE。
     * NPE 抛在聊天/进出服的事件处理里会打断后面的逻辑（deliver 里那一处更是会让所有子服一条都收不到），
     * 所以这里判空 + 兜住所有 Throwable：Discord 转发失效可以接受，消息转发不能跟着崩。
     */
    private static void trySendDiscord(final String plain) {
        if (!VMessage.isDiscord()) {
            return;
        }
        try {
            final VelocityDiscord.Discord discord = VelocityDiscord.getDiscord();
            if (discord != null) {
                discord.sendMessage(plain);
            }
        } catch (final Throwable ignored) {
            // 转发失败就算了，不能影响跨服聊天本身
        }
    }

    /** 删掉颜色码（&c / &#RRGGBB / §c），正文一字不动。包内可见，便于单测。 */
    static String stripColors(final String message) {
        return DISCORD_COLOR.matcher(message).replaceAll("");
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
        final Optional<ServerConnection> current = p.getCurrentServer();
        // ⚠️ 取不到就放弃，不要 orElseThrow()：玩家刚登录还没落到子服（/sendall、延迟转发任务
        //    都可能撞上这个瞬间）时会抛 NoSuchElementException，把整条消息连带后面的处理一起打断。
        if (current.isEmpty()) {
            return;
        }
        final String actualservername;
        {
            final String raw = current.get().getServerInfo().getName();
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
            // ⚠️ 先给 %xxx% 套边界标记再送去过桥接：变量【解析成功但值是空串】时，
            //    字符串里已经没有 %xxx% 了，只有标记还能指出"这一段是个变量、现在空了"，
            //    finish() 才能把它两侧多余的空格收掉（否则 [生存]  [无公会] 两个空格）。
            papi.format(FormatCleaner.mark(main), p.getUniqueId()).thenAccept(resolved ->
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
        // ⚠️ 这一句在发服循环【之前】：真崩了的话所有子服一条都收不到，所以必须兜住
        if (VMessage.isDiscord()) {
            final String source = mainFormat.isEmpty() ? altFormat : mainFormat;
            // ⚠️ #message# 在这一步才换成玩家真正说的话 —— 上面那两个组件里是换好了的，
            //    但 Discord 要的是纯文本，只能在这里自己换一次（以前漏了这步，Discord 上全是 "#message#"）。
            trySendDiscord(discordRaw(source.replace("#message#", content)));
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
            // ⚠️ 主格式为空（Message.format = ""）时 mainComponent 是 null，这里要退回备用格式，
            //    否则非 no-papi 的服一条都收不到（整条消息凭空消失）
            final Component target = noPapi && altComponent != null
                    ? altComponent
                    : (mainComponent != null ? mainComponent : altComponent);
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
                ? parseQuietly(content.replace("§", ""), true)
                : messageComponent(colorMode, content, namedColors, gradient);
        // 网址做成 [链接]（可点击、悬停看完整网址）
        body = Linkify.apply(body, configuration);
        final Component parsed = parseQuietly(message.replace("§", ""), mini);
        return parsed.replaceText(net.kyori.adventure.text.TextReplacementConfig.builder()
                .matchLiteral("#message#")
                .replacement(body)
                .build());
    }

    /**
     * 解析一条格式串；解析失败时**退化成纯文本**而不是把异常抛出去。
     *
     * 为什么要兜底：抛出去的话整条消息就没了 —— 别的子服一条都收不到，比"显示得难看"严重得多。
     * 触发场景很实在：MiniMessage 模式下玩家内容里有畸形标签、LuckPerms 前缀里带奇怪字符等。
     *
     * @param text  待解析的串
     * @param mini  true = 按 MiniMessage 解析，false = 按 & 颜色码解析
     */
    private static Component parseQuietly(final String text, final boolean mini) {
        try {
            return mini ? mm.deserialize(text) : SERIALIZER.deserialize(text);
        } catch (final RuntimeException ex) {
            if (warnedParse.compareAndSet(true, false)) {
                org.slf4j.LoggerFactory.getLogger("vmessage").warn(
                        "[vmessage] 有一条消息解析失败，已按纯文本发出（之后不再重复提示）：" + ex);
            }
            return Component.text(text);
        }
    }

    /** 按 colorMode 决定消息内容怎么变成组件：parse 解析颜色码（含 CMI 的 {#RRGGBB} 那套），strip / keep 都按纯文本处理。 */
    private static Component messageComponent(final String colorMode, final String content,
                                              final Map<String, String> namedColors, final boolean gradient) {
        return ChatColors.component(colorMode, content, namedColors, gradient);
    }
}