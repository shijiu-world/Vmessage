package me.feusalamander.vmessage;

import com.moandjiezana.toml.Toml;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
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
    // ⚠️ 全部 volatile：reload 可能在别的线程（命令、自动热重载的定时任务）里改这些值，
    //    而聊天事件在读它们。没有可见性保证的话，改完要等很久才生效，甚至永远不生效。
    private volatile String messageFormat;
    private volatile String joinFormat;
    private volatile String leaveFormat;
    private volatile String kickFormat;
    private volatile String changeFormat;
    private volatile boolean messageEnabled;
    private volatile boolean joinEnabled;
    private volatile boolean leaveEnabled;
    private volatile boolean kickEnabled;
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
    private volatile Map<String, String> namedColors = Collections.emptyMap();
    /** 聊天内容里的网址做成可点击短文本（[链接]，点一下打开浏览器）。 */
    private volatile boolean linkEnabled;
    /** 链接显示的文字（支持 & 颜色码）。 */
    private volatile String linkText;
    /** 悬停提示，{url} 换成完整网址；留空则不显示提示。 */
    private volatile String linkHover;
    /** 识别网址的正则；写错就用默认的那条。 */
    private volatile Pattern linkPattern;
    private Toml config;
    private static File file;
    private volatile List<String> messagecmd;
    private volatile List<String> joincmd;
    private volatile List<String> leavecmd;
    private volatile List<String> kickcmd;
    private volatile List<String> changecmd;
    // 改版：Custom-Meta 支持任意多个槽位，key = 占位符名(对应 #key#)，value = LuckPerms meta 键
    private volatile Map<String, String> customMeta;
    private volatile Toml aliases;
    /**
     * 发给「解析不了 PAPI」的子服的另一套格式（那些服没装 PAPIProxyBridge-Bukkit）。
     * 为空表示不另配 —— 那些服收到的仍是主 format，只是 %xxx% 会被抹掉。
     */
    private volatile String noPapiFormat;
    /** 上面那套格式适用哪些服；存小写，用 velocity.toml 里注册的服务器名。 */
    private volatile Set<String> noPapiServers = new LinkedHashSet<>();
    /** 是否自动沿用 PAPIProxyBridge settings.yml 里的黑名单。 */
    private volatile boolean readBridgeBlacklist;
    /** 代理的 plugins 目录（用来找 PAPIProxyBridge 的配置），可能为 null。 */
    private volatile Path pluginsDir;
    /** 改了 config.toml 之后自动重载，不用敲命令（默认关）。 */
    private volatile boolean autoReload;
    /** 自动重载的检查间隔（秒）。 */
    private volatile long autoReloadIntervalSeconds;
    /** 上一次 reload 失败的原因（TOML 语法错误），读一次就清掉。 */
    private volatile String lastError;

    Configuration(Toml config) {
        // file 由 load() 先设好：plugins/vmessage/config.toml → 上两级就是 plugins/
        if (file != null) {
            pluginsDir = file.toPath().toAbsolutePath().getParent().getParent();
        }
        apply(config);
    }

    /**
     * 把一份 TOML 灌进所有字段。
     * 构造和 reload 走的是同一段代码 —— 以前两处各写一遍，新增字段时 reload 漏抄过一次
     * （reload 后仍是启动时的值），现在不会再犯。
     */
    private void apply(final Toml config) {
        messageFormat = config.getString("Message.format", "");
        joinFormat = config.getString("Join.format", "");
        leaveFormat = config.getString("Leave.format", "");
        kickFormat = config.getString("Kick.format", "");
        changeFormat = config.getString("Server-change.format", "");

        messageEnabled = config.getBoolean("Message.enabled", false);
        joinEnabled = config.getBoolean("Join.enabled", false);
        leaveEnabled = config.getBoolean("Leave.enabled", false);
        kickEnabled = config.getBoolean("Kick.enabled", false);
        changeEnabled = config.getBoolean("Server-change.enabled", false);

        aliases = config.getTable("Aliases");

        messagecmd = config.getList("Message.commands");
        joincmd = config.getList("Join.commands");
        leavecmd = config.getList("Leave.commands");
        kickcmd = config.getList("Kick.commands");
        changecmd = config.getList("Server-change.commands");
        minimessage = config.getBoolean("Message-format.minimessage");
        all = config.getBoolean("Message.all", false);

        papiEnabled = config.getBoolean("Message.papiproxybridge", true);
        papiCacheMillis = config.getLong("Message.papi-cache-millis", 30000L);
        papiTimeoutMillis = config.getLong("Message.papi-timeout-millis", 1500L);
        papiRetryTimes = config.getLong("Message.papi-retry-times", 0L).intValue();

        // 玩家聊天内容里的颜色码怎么处理：strip=剥掉 / parse=解析 / keep=原样显示
        final String mode = config.getString("Message.message-colors", "strip");
        messageColors = normalizeColorMode(mode);
        namedColors = readNamedColors(config);

        // ---- 聊天里的网址 ----
        linkEnabled = config.getBoolean("Link.enabled", true);
        linkText = config.getString("Link.text", "&9[链接]");
        linkHover = config.getString("Link.hover", "&7点击打开：&f{url}");
        linkPattern = compilePattern(config.getString("Link.pattern", Linkify.DEFAULT_PATTERN_SOURCE));

        customMeta = readCustomMeta(config);

        // ---- 给「没有 PAPI 桥接」的子服用的备用格式 ----
        noPapiFormat = trimToNull(config.getString("Message.no-papi-format", ""));
        readBridgeBlacklist = config.getBoolean("Message.read-bridge-blacklist", true);
        // 整个集合替换，不在原集合上 clear+addAll —— 免得别的线程读到「清空了但还没填」的中间态
        noPapiServers = buildNoPapiServers(config);

        // ---- 热重载 ----
        autoReload = config.getBoolean("auto-reload", false);
        autoReloadIntervalSeconds = Math.max(1L, config.getLong("auto-reload-interval-seconds", 5L));

        this.config = config;
    }

    private Set<String> buildNoPapiServers(final Toml config) {
        final Set<String> names = readServerList(config, "Message.no-papi-servers");
        if (!readBridgeBlacklist || pluginsDir == null) {
            return names;
        }
        // 与 PAPIProxyBridge settings.yml 里的黑名单取并集（只加不减，config.toml 里写的永远生效）
        final Set<String> merged = new LinkedHashSet<>(names);
        merged.addAll(PapiBlacklist.read(pluginsDir));
        return merged;
    }

    /** 读一个字符串数组（允许写成 no-papi-servers = ["killer"] 或多行）。 */
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
    public List<String> getChangecmd(){
        return this.changecmd;
    }
    public Toml getAliases() {
        return aliases;
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
    /** CMI 风格命名色 {#名字} → hex（不含 #），小写键。 */
    public Map<String, String> getNamedColors() {
        return this.namedColors;
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
     * 给「解析不了 PAPI 变量」的子服用的备用格式 —— 里面只能写 Vmessage 自己的占位符
     * （#player# #prefix# #suffix# #server# #message# 以及任意 #meta#），%xxx% 一律不解析。
     * 没配（null）表示这些服仍用主 format，只是 %xxx% 被抹成空。
     */
    public String getNoPapiFormat() {
        return this.noPapiFormat;
    }

    /**
     * 该服是否要用 {@link #getNoPapiFormat()}。
     * 传 velocity.toml 里注册的服务器名（不是 [Aliases] 的中文别名），大小写不敏感。
     */
    public boolean isNoPapiServer(final String serverName) {
        return serverName != null && noPapiServers.contains(serverName.toLowerCase(Locale.ROOT));
    }

    /** 当前生效的 no-papi 名单（小写），只在日志里用。 */
    public Set<String> getNoPapiServers() {
        return Collections.unmodifiableSet(noPapiServers);
    }

    /** 运行时追加 no-papi 名单（外部调用，比如命令或后续扩展）。 */
    public void addNoPapiServers(final Collection<String> serverNames) {
        if (serverNames == null) {
            return;
        }
        for (final String name : serverNames) {
            if (name != null && !name.trim().isEmpty()) {
                noPapiServers.add(name.trim().toLowerCase(Locale.ROOT));
            }
        }
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
        } catch (RuntimeException e) {
            // TOML 语法错误会抛 IllegalStateException 的包装 —— 不能让它把配置打回默认值
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
