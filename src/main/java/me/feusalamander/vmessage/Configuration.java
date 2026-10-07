package me.feusalamander.vmessage;

import com.moandjiezana.toml.Toml;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public final class Configuration {
    private volatile String messageFormat;
    private volatile String joinFormat;
    private volatile String leaveFormat;
    private volatile String kickFormat;
    /** [Disconnect] 的格式：人还没进任何服就断开了（版本不符、封禁、顶号、登录阶段超时……）。 */
    private volatile String disconnectFormat;
    private volatile String changeFormat;
    private volatile boolean messageEnabled;
    private volatile boolean joinEnabled;
    private volatile boolean leaveEnabled;
    private volatile boolean kickEnabled;
    private volatile boolean disconnectEnabled;
    private volatile boolean changeEnabled;
    private volatile boolean minimessage;
    private volatile boolean all;
    private volatile boolean papiEnabled;
    private volatile long papiCacheMillis;
    private volatile long papiTimeoutMillis;
    private volatile int papiRetryTimes;
    /**
     * 读 [Message.named-colors] 这张表 —— CMI 风格命名色 {#名字} 的对照表。
     * 例：
     *   [Message.named-colors]
     *   brown = "#A52A2A"
     * → 聊天里写 {#brown} 会被解析成这个颜色。表里没有的名字，其 {#名字} 会被摘掉。
     * 键统一转小写（CMI 也不区分大小写），值允许 "#RRGGBB" 或 "RRGGBB"。
     */
    private static Map<String, String> readNamedColors(final Toml config) {
        final Map<String, String> map = new LinkedHashMap<>();
        final Toml table = config.getTable("Message.named-colors");
        if (table == null) {
            return map;
        }
        for (final Map.Entry<String, Object> entry : table.toMap().entrySet()) {
            if (!(entry.getValue() instanceof String)) {
                continue;
            }
            String hex = ((String) entry.getValue()).trim().replace("#", "");
            if (hex.length() == 3) {
                hex = "" + hex.charAt(0) + hex.charAt(0) + hex.charAt(1) + hex.charAt(1)
                        + hex.charAt(2) + hex.charAt(2);
            }
            if (hex.matches("[0-9a-fA-F]{6}")) {
                map.put(entry.getKey().toLowerCase(), hex);
            }
        }
        return map;
    }

    private volatile String messageColors;
    /**
     * 使用渐变需要的 LuckPerms 权限名；留空 = 不限制。
     * 没有这个权限的人，渐变标记会被摘掉、文字留下（其它颜色码照常）。
     */
    private volatile String gradientPermission = "vmessage.gradient";
    private volatile Map<String, String> namedColors = Collections.emptyMap();
    /**
     * 等不等子服回一句「这条聊天被我取消掉了」。
     * 子服插件（商店输入数量、菜单输入……）取消聊天的事发生在代理转发之后，
     * 且没有机制回传给代理 —— 只能靠子服这边主动说一声。
     * 需要各子服装配套插件 VmessageSuppress；没装的话每次都等到超时，行为与关闭时一致。
     */
    private volatile boolean awaitCancelSignal;
    /** 最多等多少毫秒；期间没收到信号就当正常聊天照常转发（上限 1000）。 */
    private volatile long awaitCancelTimeoutMillis;
    /**
     * 等信号的范围（与 server-filter 同一套黑/白名单写法）。
     * false = 黑名单：名单里的服【不】等（收到就转发），其余都等 —— 名单为空就是「全都等」（默认，与没这个功能时一样）
     * true  = 白名单：只有名单里的服等 —— 名单为空就是「谁都不等」，等于把抑制关掉
     */
    private volatile boolean suppressAwaitWhitelist;
    /** await-cancel-servers 里写的服务器名，存小写；用 velocity.toml 里注册的名字。 */
    private volatile Set<String> suppressAwaitServers = new LinkedHashSet<>();
    /**
     * 参与跨服聊天的子服范围（server-filter 的两种读法）。
     * false = 黑名单：名单里的服不参与，其余都参与 —— 名单为空就是「全部参与」（默认，与没这个功能时一样）
     * true  = 白名单：只有名单里的服参与 —— 名单为空就是「谁都不参与」，等于把跨服聊天关掉
     */
    private volatile boolean serverFilterWhitelist;
    /** server-filter 里写的服务器名，存小写；用 velocity.toml 里注册的名字。 */
    private volatile Set<String> serverFilter = new LinkedHashSet<>();
    /** 聊天内容里的网址做成可点击短文本（[链接]，点一下打开浏览器）。 */
    private volatile boolean linkEnabled;
    /** 链接显示的文字（支持 & 颜色码）。 */
    private volatile String linkText;
    /** 悬停提示，{url} 换成完整网址；留空则不显示提示。 */
    private volatile String linkHover;
    /** 识别网址的正则；写错就用默认的那条。 */
    private volatile Pattern linkPattern;
    /**
     * ★ 跨服聊天消息的悬停 / 点击：别的子服的玩家把鼠标放到这条消息上时显示发送时间，
     * 点一下把命令（默认 /msg 发送者）填进自己的聊天框。
     */
    private volatile boolean tooltipEnabled = true;
    /** 悬停提示；支持 {time} {player} {server}，留空则不显示提示。 */
    private volatile String tooltipHover = ChatTooltip.DEFAULT_HOVER;
    /**
     * 悬停提示前面那截固定前缀（{@code Tooltip.prefix}）：只有跨服聊天加，五档广播不加。
     * 串里可以写 {@code #server#} / {@code #player#} 和 {@code %xxx%}（子服 PAPI 变量），
     * 由 {@code Listeners} 在发消息前跟消息格式同一批解析；这里存的是**还没解析**的模板。
     */
    private volatile String tooltipPrefix = ChatTooltip.DEFAULT_PREFIX;
    /** 点击时填入聊天框的命令；支持同样的三个占位符，留空则不响应点击。 */
    private volatile String tooltipSuggest = ChatTooltip.DEFAULT_SUGGEST;
    /** {time} 的时间格式（Java 的 DateTimeFormatter 写法）。 */
    private volatile DateTimeFormatter tooltipTimeFormat = ChatTooltip.FALLBACK_TIME;
    /** {time} 用的时区；留空 = 服务器系统时区。 */
    private volatile ZoneId tooltipZone = ZoneId.systemDefault();
    /** 正文（#message# 那一截）的「悬停提示 + 点一下复制到剪贴板」要不要挂。 */
    private volatile boolean tooltipCopy = true;
    /** 正文上悬停显示的提示；留空 = 只不显示提示，点击照样复制。 */
    private volatile String tooltipCopyHover = ChatTooltip.DEFAULT_COPY_HOVER;
    /**
     * ★ Join / Leave / Kick / Disconnect / Server-change 五类广播的总闸。
     * false = 一条都不发（连各段自己的 commands 也不跑），等于把广播整体关掉；true = 交给各段的 enabled 决定。
     */
    private volatile boolean broadcastEnabled = true;
    private Toml config;
    private static File file;
    private volatile List<String> messagecmd;
    private volatile List<String> joincmd;
    private volatile List<String> leavecmd;
    private volatile List<String> kickcmd;
    private volatile List<String> disconnectcmd;
    private volatile List<String> changecmd;
    // 改版：Custom-Meta 支持任意多个槽位，key = 占位符名(对应 #key#)，value = LuckPerms meta 键
    private volatile Map<String, String> customMeta;
    private volatile Toml aliases = emptyToml();
    /**
     * 发给「解析不了 PAPI」的子服的另一套格式（那些服没装 PAPIProxyBridge-Bukkit）。
     * 为空表示不另配 —— 那些服收到的仍是主 format，只是 %xxx% 会被抹掉。
     */
    private volatile String noPapiFormat;
    /**
     * 装了 PAPIProxyBridge-Bukkit、**能**解析 {@code %xxx%} 的子服；存小写，
     * 用 velocity.toml 里注册的服务器名（不是 [Aliases] 的中文别名）。
     *
     * <p>⚠️ 语义是**白名单**：
     * <ul>
     *   <li>名单非空 → 只有名单里的服解析 {@code %xxx%}，其余一律走 {@link #noPapiFormat}；</li>
     *   <li>留空 → 全部都按「装了桥接」处理（跟老版 {@code no-papi-servers = []} 完全一致，
     *       升级上来不会变样；代价是新加的服没装桥接时每次发言要白等一次 timeout）。</li>
     * </ul>
     */
    private volatile Set<String> papiServers = new LinkedHashSet<>();
    /** 改了 config.toml 之后自动重载，不用敲命令（默认关）。 */
    private volatile boolean autoReload;
    /** 自动重载的检查间隔（秒）。 */
    private volatile long autoReloadIntervalSeconds;
    /** 上一次 reload 失败的原因（TOML 语法错误），读一次就清掉。 */
    private volatile String lastError;

    Configuration(Toml config) {
        apply(config);
    }

    /**
     * 把一份 TOML 灌进所有字段。
     * 构造和 reload 走的是同一段代码 —— 以前两处各写一遍，新增字段时 reload 漏抄过一次
     * （reload 后仍是启动时的值），现在不会再犯。
     */
    private void apply(final Toml config) {
        final String newMessageFormat = config.getString("Message.format", "");
        final String newJoinFormat = config.getString("Join.format", "");
        final String newLeaveFormat = config.getString("Leave.format", "");
        final String newKickFormat = config.getString("Kick.format", "");
        final String newDisconnectFormat = config.getString("Disconnect.format", "");
        final String newChangeFormat = config.getString("Server-change.format", "");

        final boolean newMessageEnabled = config.getBoolean("Message.enabled", false);
        final boolean newJoinEnabled = config.getBoolean("Join.enabled", false);
        final boolean newLeaveEnabled = config.getBoolean("Leave.enabled", false);
        final boolean newKickEnabled = config.getBoolean("Kick.enabled", false);
        final boolean newDisconnectEnabled = config.getBoolean("Disconnect.enabled", true);
        final boolean newChangeEnabled = config.getBoolean("Server-change.enabled", false);

        final Toml newAliases = tableOrEmpty(config, "Aliases");

        final List<String> newMessagecmd = listOrEmpty(config, "Message.commands");
        final List<String> newJoincmd = listOrEmpty(config, "Join.commands");
        final List<String> newLeavecmd = listOrEmpty(config, "Leave.commands");
        final List<String> newKickcmd = listOrEmpty(config, "Kick.commands");
        final List<String> newDisconnectcmd = listOrEmpty(config, "Disconnect.commands");
        final List<String> newChangecmd = listOrEmpty(config, "Server-change.commands");
        // ⚠️ 全项目唯一没有默认值的 getBoolean：缺键时返回 null，拆箱成 boolean 会 NPE；
        //    而首次加载时 NPE 会让 VMessage 直接 return（监听器注册不到），只能重启代理。
        final boolean newMinimessage = Boolean.TRUE.equals(
                config.getBoolean("Message-format.minimessage", Boolean.FALSE));
        final boolean newAll = config.getBoolean("Message.all", false);

        final boolean newPapiEnabled = config.getBoolean("Message.papiproxybridge", true);
        final long newPapiCacheMillis = config.getLong("Message.papi-cache-millis", 30000L);
        final long newPapiTimeoutMillis = config.getLong("Message.papi-timeout-millis", 1500L);
        final int newPapiRetryTimes = config.getLong("Message.papi-retry-times", 0L).intValue();

        // 玩家聊天内容里的颜色码怎么处理：strip=剥掉 / parse=解析 / keep=原样显示
        final String mode = config.getString("Message.message-colors", "strip");
        final String newMessageColors = normalizeColorMode(mode);
        // 留空 = 任何人都能用渐变
        final String perm = config.getString("Message.gradient-permission", "vmessage.gradient");
        final String newGradientPermission = perm == null ? "" : perm.trim();
        final Map<String, String> newNamedColors = readNamedColors(config);

        // ---- 被子服插件取消的聊天（商店输入数量、菜单输入等）----
        final boolean newAwaitCancelSignal = config.getBoolean("Message.await-cancel-signal", true);
        // 上限 1 秒：再久别的服看到消息就会有明显延迟，也说明子服那边不正常
        final long newAwaitCancelTimeoutMillis = Math.min(1000L, Math.max(0L,
                config.getLong("Message.await-cancel-timeout-millis", 100L)));

        // ---- 哪些服要等这个信号 ----
        // 只认 blacklist / whitelist 两个词；写错、留空都当黑名单（默认全都等，不会静默关掉抑制）
        final String awaitMode = config.getString("Message.await-cancel-server-mode", "blacklist");
        final boolean newSuppressAwaitWhitelist =
                awaitMode != null && awaitMode.trim().equalsIgnoreCase("whitelist");
        // 整个集合替换，不在原集合上 clear+addAll —— 免得别的线程读到「清空了但还没填」的中间态
        final Set<String> newSuppressAwaitServers = readServerList(config, "Message.await-cancel-servers");

        // ---- 参与跨服聊天的子服范围 ----
        // 只认 blacklist / whitelist 两个词；写错、留空都当黑名单（默认全参与，不会静默断流）
        final String filterMode = config.getString("Message.server-filter-mode", "blacklist");
        final boolean newServerFilterWhitelist =
                filterMode != null && filterMode.trim().equalsIgnoreCase("whitelist");
        // 整个集合替换，不在原集合上 clear+addAll —— 免得别的线程读到「清空了但还没填」的中间态
        final Set<String> newServerFilter = readServerList(config, "Message.server-filter");

        // ---- 聊天里的网址 ----
        final boolean newLinkEnabled = config.getBoolean("Link.enabled", true);
        final String newLinkText = config.getString("Link.text", "&9[链接]");
        final String newLinkHover = config.getString("Link.hover", "&7点击打开：&f{url}");
        final Pattern newLinkPattern =
                compilePattern(config.getString("Link.pattern", Linkify.DEFAULT_PATTERN_SOURCE));

        // ---- 跨服聊天消息的悬停 / 点击 ----
        final boolean newTooltipEnabled = config.getBoolean("Tooltip.enabled", true);
        // 留空 = 不做这一项（hover 空 = 不显示提示；suggest 空 = 点了没反应）
        final String newTooltipHover = strOrEmpty(
                config.getString("Tooltip.hover", ChatTooltip.DEFAULT_HOVER));
        // 悬停提示的前缀：留空 = 不加（老配置没有这个键时也是空，行为不变）
        final String newTooltipPrefix = strOrEmpty(
                config.getString("Tooltip.prefix", ChatTooltip.DEFAULT_PREFIX));
        final String newTooltipSuggest = strOrEmpty(
                config.getString("Tooltip.suggest", ChatTooltip.DEFAULT_SUGGEST));
        // 时间格式 / 时区写错都只退回默认值，不能让别处的配置跟着失效
        final DateTimeFormatter newTooltipTimeFormat = ChatTooltip.compileTimeFormat(
                config.getString("Tooltip.time-format", ChatTooltip.DEFAULT_TIME_PATTERN));
        final ZoneId newTooltipZone = ChatTooltip.parseZone(
                trimToNull(config.getString("Tooltip.time-zone", "")));

        // ---- 正文（#message#）的「点一下复制」 ----
        // ⚠️ 同样是 Boolean：toml4j 缺键返回 null，直接拆箱会 NPE
        final Boolean copyRaw = config.getBoolean("Tooltip.copy", Boolean.TRUE);
        final boolean newTooltipCopy = copyRaw == null || copyRaw;
        final String newTooltipCopyHover = strOrEmpty(
                config.getString("Tooltip.copy-hover", ChatTooltip.DEFAULT_COPY_HOVER));

        // ---- 广播（Join / Leave / Kick / Disconnect / Server-change）的总闸 ----
        // ⚠️ 问号表达式在这里不顶用：toml4j 对缺失的键返回 null，Boolean 拆箱会 NPE。
        final Boolean broadcastRaw = config.getBoolean("Broadcast.enabled", Boolean.TRUE);
        final boolean newBroadcastEnabled = broadcastRaw == null || broadcastRaw;

        final Map<String, String> newCustomMeta = readCustomMeta(config);

        // ---- 给「没有 PAPI 桥接」的子服用的备用格式 ----
        final String newNoPapiFormat = trimToNull(config.getString("Message.no-papi-format", ""));
        // 整个集合替换，不在原集合上 clear+addAll —— 免得别的线程读到「清空了但还没填」的中间态
        final Set<String> newPapiServers = readServerList(config, "Message.papi-servers");

        // ---- 热重载 ----
        final boolean newAutoReload = config.getBoolean("auto-reload", false);
        final long newAutoReloadIntervalSeconds =
                Math.max(1L, config.getLong("auto-reload-interval-seconds", 5L));

        // ↓↓↓ 全部算完才统一赋值 —— 中途抛异常时旧配置还是完整的一套，不会留下「半套配置」 ↓↓↓
        messageFormat = newMessageFormat;
        joinFormat = newJoinFormat;
        leaveFormat = newLeaveFormat;
        kickFormat = newKickFormat;
        disconnectFormat = newDisconnectFormat;
        changeFormat = newChangeFormat;

        messageEnabled = newMessageEnabled;
        joinEnabled = newJoinEnabled;
        leaveEnabled = newLeaveEnabled;
        kickEnabled = newKickEnabled;
        disconnectEnabled = newDisconnectEnabled;
        changeEnabled = newChangeEnabled;

        aliases = newAliases;

        messagecmd = newMessagecmd;
        joincmd = newJoincmd;
        leavecmd = newLeavecmd;
        kickcmd = newKickcmd;
        disconnectcmd = newDisconnectcmd;
        changecmd = newChangecmd;
        minimessage = newMinimessage;
        all = newAll;

        papiEnabled = newPapiEnabled;
        papiCacheMillis = newPapiCacheMillis;
        papiTimeoutMillis = newPapiTimeoutMillis;
        papiRetryTimes = newPapiRetryTimes;

        messageColors = newMessageColors;
        gradientPermission = newGradientPermission;
        namedColors = newNamedColors;

        awaitCancelSignal = newAwaitCancelSignal;
        awaitCancelTimeoutMillis = newAwaitCancelTimeoutMillis;
        suppressAwaitWhitelist = newSuppressAwaitWhitelist;
        suppressAwaitServers = newSuppressAwaitServers;

        serverFilterWhitelist = newServerFilterWhitelist;
        serverFilter = newServerFilter;

        linkEnabled = newLinkEnabled;
        linkText = newLinkText;
        linkHover = newLinkHover;
        linkPattern = newLinkPattern;

        tooltipEnabled = newTooltipEnabled;
        tooltipHover = newTooltipHover;
        tooltipPrefix = newTooltipPrefix;
        tooltipSuggest = newTooltipSuggest;
        tooltipTimeFormat = newTooltipTimeFormat;
        tooltipZone = newTooltipZone;
        tooltipCopy = newTooltipCopy;
        tooltipCopyHover = newTooltipCopyHover;
        broadcastEnabled = newBroadcastEnabled;

        customMeta = newCustomMeta;

        noPapiFormat = newNoPapiFormat;
        papiServers = newPapiServers;
        warnRenamedKeys(config);

        autoReload = newAutoReload;
        autoReloadIntervalSeconds = newAutoReloadIntervalSeconds;

        this.config = config;
    }

    /**
     * 读一张表；表被用户整段删掉时给一张【空表】而不是 null。
     * [Aliases] 就是这样：删掉之后 getAliases() 一旦是 null，所有 .contains() 调用点全崩。
     */
    private static Toml tableOrEmpty(final Toml config, final String path) {
        final Toml table = config.getTable(path);
        if (table != null) {
            return table;
        }
        return emptyToml();
    }

    /** 一张空的 Toml（读空字符串得到），contains() 一律返回 false。 */
    private static Toml emptyToml() {
        try {
            return new Toml().read("");
        } catch (final RuntimeException e) {
            // 理论上不会发生；真发生了就用裸的，至少不会是 null
            return new Toml();
        }
    }

    /** 读一个字符串数组；键缺失时给空列表 —— 调用方不用再判 null。 */
    private static List<String> listOrEmpty(final Toml config, final String path) {
        final List<String> list = config.getList(path);
        return list == null ? Collections.emptyList() : list;
    }

    /**
     * 老配置里那两个键已经废了 —— 看到就提醒一句，但**不要**拿它们干活：
     * {@code no-papi-servers} 是黑名单、跟现在的白名单语义相反，直接沿用会整反；
     * 也没法自动转换（要把黑名单转白名单得先知道「全部服有哪些」，这里拿不到）。
     */
    private static void warnRenamedKeys(final Toml config) {
        if (config.contains("Message.no-papi-servers")
                || config.contains("Message.read-bridge-blacklist")) {
            LoggerFactory.getLogger("vmessage").warn(
                    "[vmessage] config.toml 里的 no-papi-servers / read-bridge-blacklist 已经不用了"
                            + "（papi-servers 改成白名单后它们没有意义），现在一律忽略。"
                            + "请把这两行删掉，改为列出【装了桥接】的服：papi-servers = [\"lobby\", \"survival\"]");
        }
    }

    /** 读一个字符串数组（允许写成 papi-servers = ["lobby", "survival"] 或多行）。 */
    private static Set<String> readServerList(final Toml config, final String path) {
        final Set<String> set = new LinkedHashSet<>();
        final List<String> list = config.getList(path);
        if (list == null) {
            return set;
        }
        for (final String raw : list) {
            if (raw == null) {
                continue;
            }
            final String name = raw.trim();
            if (!name.isEmpty()) {
                set.add(name.toLowerCase(Locale.ROOT));
            }
        }
        return set;
    }

    /** 正则写错了就用默认的，别让一条配置把整个聊天搞挂。 */
    private static Pattern compilePattern(final String regex) {
        if (regex == null || regex.trim().isEmpty()) {
            return Linkify.defaultPattern();
        }
        try {
            return Pattern.compile(regex);
        } catch (final Exception e) {
            return Linkify.defaultPattern();
        }
    }

    private static String trimToNull(final String s) {
        if (s == null) {
            return null;
        }
        final String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** 读一个字符串，null 当空串 —— 调用方只需要判 isEmpty()，不用再判 null。 */
    private static String strOrEmpty(final String s) {
        return s == null ? "" : s;
    }

    /**
     * 聊天内容里颜色码的处理方式。只认 strip / parse / keep，写错一律退回 strip。
     */
    private static String normalizeColorMode(final String mode) {
        if (mode == null) {
            return "strip";
        }
        final String m = mode.trim();
        if (m.equalsIgnoreCase("parse")) {
            return "parse";
        }
        if (m.equalsIgnoreCase("keep")) {
            return "keep";
        }
        return "strip";
    }

    /**
     * 读 [Custom-Meta] 这张表。表里每个 "占位符名 = meta键" 都会生成一个 #占位符名#。
     * 例：
     *   [Custom-Meta]
     *   custom1 = "title"
     *   custom2 = "guild"
     *   level   = "level"
     * → 可用 #custom1# #custom2# #level#，个数不限。
     */
    private static Map<String, String> readCustomMeta(final Toml config) {
        final Map<String, String> map = new LinkedHashMap<>();
        final Toml table = config.getTable("Custom-Meta");
        if (table == null) {
            return map;
        }
        for (final Map.Entry<String, Object> entry : table.toMap().entrySet()) {
            if (entry.getValue() instanceof String) {
                final String metaKey = ((String) entry.getValue()).trim();
                if (!metaKey.isEmpty()) {
                    map.put(entry.getKey(), metaKey);
                }
            }
        }
        return map;
    }

    static Configuration load(Path dataDirectory) {
        Path f = createConfig(dataDirectory);
        if (f != null) {
            file = f.toFile();
            return new Configuration(new Toml().read(file));
        }
        return null;
    }

    private static Path createConfig(Path dataDirectory){
        try {
            if (Files.notExists(dataDirectory)){
                Files.createDirectory(dataDirectory);
            }
            Path f = dataDirectory.resolve("config.toml");
            if (Files.notExists(f)){
                try (InputStream stream = Configuration.class.getResourceAsStream("/config.toml")) {
                    Files.copy(Objects.requireNonNull(stream), f);
                }
            }
            return f;
        } catch(Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public String getMessageFormat() {
        return this.messageFormat;
    }

    public String getJoinFormat() {
        return this.joinFormat;
    }

    public String getLeaveFormat() {
        return this.leaveFormat;
    }
    public String getKickFormat() {
        return this.kickFormat;
    }

    /** [Disconnect] 的格式：人还没进服就断开了。 */
    public String getDisconnectFormat() {
        return this.disconnectFormat;
    }

    public String getChangeFormat() {
        return this.changeFormat;
    }

    public boolean isMessageEnabled() {
        return this.messageEnabled;
    }

    public boolean isJoinEnabled() {
        return this.joinEnabled;
    }

    public boolean isLeaveEnabled() {
        return this.leaveEnabled;
    }
    public boolean isKickEnabled() {
        return this.kickEnabled;
    }

    /** 还没进服就断开时广播一条（对齐 velocity-chat 的 [disconnect]）。 */
    public boolean isDisconnectEnabled() {
        return this.disconnectEnabled;
    }

    public boolean isChangeEnabled() {
        return this.changeEnabled;
    }
    public boolean isMinimessageEnabled(){
        return  this.minimessage;
    }
    public boolean isAllEnabled(){
        return  this.all;
    }
    public List<String> getMessagecmd(){
        return this.messagecmd;
    }
    public List<String> getJoincmd(){
        return this.joincmd;
    }
    public List<String> getLeavecmd(){
        return this.leavecmd;
    }
    public List<String> getKickcmd(){
        return this.kickcmd;
    }
    public List<String> getDisconnectcmd(){
        return this.disconnectcmd;
    }
    public List<String> getChangecmd(){
        return this.changecmd;
    }
    /** [Aliases] 这张表；段被删掉时是空表 —— 永不返回 null（调用方直接 .contains()）。 */
    public Toml getAliases() {
        return aliases == null ? emptyToml() : aliases;
    }
    public Map<String, String> getCustomMeta() {
        return this.customMeta;
    }
    /** 是否启用 PAPIProxyBridge 解析（需要代理端和子服都装了该插件）。 */
    public boolean isPapiEnabled() {
        return this.papiEnabled;
    }
    public long getPapiCacheMillis() {
        return this.papiCacheMillis;
    }
    public long getPapiTimeoutMillis() {
        return this.papiTimeoutMillis;
    }
    /** 解析失败时的重试次数。默认 0：失败不进缓存，多重试一次就多等一轮超时。 */
    public int getPapiRetryTimes() {
        return this.papiRetryTimes;
    }
    /** 聊天内容里颜色码的处理方式：strip / parse / keep。 */
    public String getMessageColors() {
        return this.messageColors;
    }
    /** 渐变权限名；空串 = 不限制。 */
    public String getGradientPermission() {
        return this.gradientPermission == null ? "" : this.gradientPermission;
    }
    /** CMI 风格命名色 {#名字} → hex（不含 #），小写键。 */
    public Map<String, String> getNamedColors() {
        return this.namedColors;
    }

    /** 是否等子服回「这条聊天被取消了」的信号（需各子服装 VmessageSuppress）。 */
    public boolean isAwaitCancelSignal() {
        return this.awaitCancelSignal;
    }

    /** 最多等多少毫秒；期间没收到信号就当正常聊天照常转发。 */
    public long getAwaitCancelTimeoutMillis() {
        return this.awaitCancelTimeoutMillis;
    }

    /**
     * 这个服的聊天要不要先等一小会儿，看子服会不会把它取消掉。
     *
     * <p>不等的服：收到就立刻转发，零延迟；代价是商店输入「64」这类被子服取消掉的聊天
     * 也会漏到别的服。所以只给「确定没装 VmessageSuppress / 确定没有会取消聊天的插件」的服开。
     *
     * @param serverName velocity.toml 里注册的服务器名（不是 [Aliases] 的中文别名），大小写不敏感
     * @return true = 等 {@link #getAwaitCancelTimeoutMillis()} 毫秒再转发
     */
    public boolean isAwaitCancelServer(final String serverName) {
        // 总开关关了、或没给等待时间：对任何服都不等
        if (!awaitCancelSignal || awaitCancelTimeoutMillis <= 0L) {
            return false;
        }
        // ⚠️ 名字取不到时按「等」处理：等一下只是消息晚一点到，不会丢；反过来则会漏掉抑制
        if (serverName == null) {
            return true;
        }
        final boolean listed = suppressAwaitServers.contains(serverName.toLowerCase(Locale.ROOT));
        return suppressAwaitWhitelist == listed;
    }

    /** await-cancel 名单是不是白名单模式（true = 只有名单里的服才等）。 */
    public boolean isSuppressAwaitWhitelist() {
        return this.suppressAwaitWhitelist;
    }

    /** 当前生效的 await-cancel 名单（小写），只在日志里用。 */
    public Set<String> getSuppressAwaitServers() {
        return Collections.unmodifiableSet(suppressAwaitServers);
    }
    /**
     * 这个服参不参与跨服聊天 —— 不参与的服既不往外发，也不收别服的消息。
     * 传 velocity.toml 里注册的服务器名（不是 [Aliases] 的中文别名），大小写不敏感。
     * ⚠️ 名字取不到时按「参与」处理：宁可多发一条，也别因为取不到名字就静默断流。
     */
    public boolean isChatServerAllowed(final String serverName) {
        if (serverName == null) {
            return true;
        }
        final boolean listed = serverFilter.contains(serverName.toLowerCase(Locale.ROOT));
        return serverFilterWhitelist == listed;
    }

    /** server-filter 是不是白名单模式（true = 只有名单里的服参与）。 */
    public boolean isServerFilterWhitelist() {
        return this.serverFilterWhitelist;
    }

    /** 当前生效的 server-filter（小写），只在日志里用。 */
    public Set<String> getServerFilter() {
        return Collections.unmodifiableSet(serverFilter);
    }

    /** 聊天里的网址要不要做成可点击短文本。 */
    public boolean isLinkEnabled() {
        return this.linkEnabled;
    }
    public String getLinkText() {
        return this.linkText;
    }
    public String getLinkHover() {
        return this.linkHover;
    }
    /** 识别网址用的正则；永远不为 null（写错时退回默认）。 */
    public Pattern getLinkPattern() {
        return this.linkPattern == null ? Linkify.defaultPattern() : this.linkPattern;
    }

    /**
     * 跨服聊天消息的悬停 / 点击要不要挂（需要至少一项有内容才真的生效，
     * 见 {@link ChatTooltip#enabled(Configuration)}）。
     */
    public boolean isTooltipEnabled() {
        return this.tooltipEnabled;
    }
    /** 悬停提示；支持 {time} {player} {server}，留空 = 不显示提示。 */
    public String getTooltipHover() {
        return this.tooltipHover;
    }
    /**
     * 悬停提示前面那截固定前缀（**只有跨服聊天加，五档广播不加**）。
     * 支持 {@code #server#} / {@code #player#} 等内建占位符和 {@code %xxx%} 子服 PAPI 变量；
     * 留空 = 不额外加东西。返回的是**模板**，解析在 {@code Listeners} 里做。
     */
    public String getTooltipPrefix() {
        return this.tooltipPrefix;
    }
    /** 点击时填入聊天框的命令；留空 = 点了没反应。 */
    public String getTooltipSuggest() {
        return this.tooltipSuggest;
    }
    /** 悬停提示里 {time} 的时间格式（永不 null，写错时退回 HH:mm:ss）。 */
    public DateTimeFormatter getTooltipTimeFormat() {
        return this.tooltipTimeFormat == null ? ChatTooltip.FALLBACK_TIME : this.tooltipTimeFormat;
    }
    /** {time} 用的时区（永不 null，留空或写错时是服务器系统时区）。 */
    public ZoneId getTooltipZone() {
        return this.tooltipZone == null ? ZoneId.systemDefault() : this.tooltipZone;
    }

    /** 正文（#message#）要不要挂「悬停提示 + 点一下复制到剪贴板」。 */
    public boolean isTooltipCopyEnabled() {
        return this.tooltipCopy;
    }
    /** 正文上悬停显示的提示（默认 &7复制该文本）；留空 = 不显示提示，点击照样复制。 */
    public String getTooltipCopyHover() {
        return this.tooltipCopyHover;
    }

    /**
     * 广播总闸：Join / Leave / Kick / Disconnect / Server-change 五类要不要发。
     * false = 一条都不发，也不用去改五个段各自的 enabled。
     */
    public boolean isBroadcastEnabled() {
        return this.broadcastEnabled;
    }

    /**
     * 给「解析不了 PAPI 变量」的子服用的备用格式 —— 里面只能写 Vmessage 自己的占位符
     * （#player# #prefix# #suffix# #server# #message# 以及任意 #meta#），%xxx% 一律不解析。
     * 没配（null）表示这些服仍用主 format，只是 %xxx% 被抹成空。
     */
    public String getNoPapiFormat() {
        return this.noPapiFormat;
    }

    /**
     * 该服**能**解析 {@code %xxx%} 吗（= 装了 PAPIProxyBridge-Bukkit）。
     * 传 velocity.toml 里注册的服务器名（不是 [Aliases] 的中文别名），大小写不敏感。
     *
     * <p>⚠️ 白名单语义：名单留空时一律返回 {@code true}（全部按装了算）；
     * 只有名单非空时才逐个比对 —— 没写进去的服都算没装，走 {@link #getNoPapiFormat()}。
     */
    public boolean isPapiServer(final String serverName) {
        if (serverName == null) {
            return true;
        }
        return papiServers.isEmpty() || papiServers.contains(serverName.toLowerCase(Locale.ROOT));
    }

    /**
     * 该服是否要用 {@link #getNoPapiFormat()} —— 就是 {@link #isPapiServer(String)} 取反。
     * 取反写在这儿而不是散在各个调用点，免得哪天有人只改一边。
     */
    public boolean isNoPapiServer(final String serverName) {
        return !isPapiServer(serverName);
    }

    /** 当前生效的 papi 白名单（小写），只在日志里用；空 = 全部都按装了算。 */
    public Set<String> getPapiServers() {
        return Collections.unmodifiableSet(papiServers);
    }
	
    /**
     * 热重载：重新读一遍 config.toml。
     *
     * @return true = 已生效；false = 文件读不出来（TOML 写坏了），**旧配置原样保留**
     */
    boolean reload() {
        if (file == null) {
            return false;
        }
        try {
            // 用全新的 Toml 读（不带旧值做默认值），这样删掉的配置项是真的消失
            apply(new Toml().read(file));
            return true;
        } catch (Throwable e) {
            // ⚠️ 不能只 catch RuntimeException：toml4j 缺依赖（如 gson 不在）时抛的是
            //    NoClassDefFoundError —— 那是 Error，漏掉它就会一路穿到事件/命令线程上去。
            //    TOML 语法错误会抛 IllegalStateException 的包装 —— 不能让它把配置打回默认值。
            lastError = e.getMessage() == null ? e.toString() : e.getMessage();
            return false;
        }
    }

    /** 上一次 reload 失败的理由（成功时是 null），给 /vmessage reload 的提示用。 */
    String lastError() {
        final String e = lastError;
        lastError = null;
        return e;
    }

    /** 配置文件本身（自动热重载靠它比对修改时间）。 */
    public static File getConfigFile() {
        return file;
    }

    /** 改了 config.toml 就自动重载，不用敲命令。 */
    public boolean isAutoReload() {
        return this.autoReload;
    }

    /** 自动重载的检查间隔（秒）。 */
    public long getAutoReloadIntervalSeconds() {
        return this.autoReloadIntervalSeconds;
    }
}
