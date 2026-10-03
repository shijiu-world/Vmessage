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

public final class Configuration {
    private String messageFormat;
    private String joinFormat;
    private String leaveFormat;
    private String kickFormat;
    private String changeFormat;
    private boolean messageEnabled;
    private boolean joinEnabled;
    private boolean leaveEnabled;
    private boolean kickEnabled;
    private boolean changeEnabled;
    private boolean minimessage;
    private boolean all;
    private boolean papiEnabled;
    private long papiCacheMillis;
    private long papiTimeoutMillis;
    private int papiRetryTimes;
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

    private String messageColors;
    private Map<String, String> namedColors = Collections.emptyMap();
    private Toml config;
    private static File file;
    private List<String> messagecmd;
    private List<String> joincmd;
    private List<String> leavecmd;
    private List<String> kickcmd;
    private List<String> changecmd;
    // 改版：Custom-Meta 支持任意多个槽位，key = 占位符名(对应 #key#)，value = LuckPerms meta 键
    private Map<String, String> customMeta;
    private Toml aliases;
    /**
     * 发给「解析不了 PAPI」的子服的另一套格式（那些服没装 PAPIProxyBridge-Bukkit）。
     * 为空表示不另配 —— 那些服收到的仍是主 format，只是 %xxx% 会被抹掉。
     */
    private String noPapiFormat;
    /** 上面那套格式适用哪些服；存小写，用 velocity.toml 里注册的服务器名。 */
    private final Set<String> noPapiServers = new LinkedHashSet<>();
    /** 是否自动沿用 PAPIProxyBridge settings.yml 里的黑名单。 */
    private boolean readBridgeBlacklist;
    /** 代理的 plugins 目录（用来找 PAPIProxyBridge 的配置），可能为 null。 */
    private Path pluginsDir;

    Configuration(Toml config) {
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

        customMeta = readCustomMeta(config);

        // ---- 给「没有 PAPI 桥接」的子服用的备用格式 ----
        noPapiFormat = trimToNull(config.getString("Message.no-papi-format", ""));
        readBridgeBlacklist = config.getBoolean("Message.read-bridge-blacklist", true);
        noPapiServers.clear();
        noPapiServers.addAll(readServerList(config, "Message.no-papi-servers"));
        mergeBridgeBlacklist();

        this.config = config;
    }

    /** 把 PAPIProxyBridge 黑名单里的服并进 no-papi 名单（只加不减，config.toml 里写的永远生效）。 */
    private void mergeBridgeBlacklist() {
        if (!readBridgeBlacklist || pluginsDir == null) {
            return;
        }
        noPapiServers.addAll(PapiBlacklist.read(pluginsDir));
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
            Toml config = new Toml().read(file);
            final Configuration configuration = new Configuration(config);
            // plugins/vmessage 的上一级就是 plugins/，拿它去找 PAPIProxyBridge 的配置
            configuration.pluginsDir = dataDirectory.toAbsolutePath().getParent();
            configuration.mergeBridgeBlacklist();
            return configuration;
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
	
    void reload(){
        config = config.read(file);
        this.messageFormat = config.getString("Message.format");
        this.joinFormat = config.getString("Join.format");
        this.leaveFormat = config.getString("Leave.format");
        this.kickFormat = config.getString("Kick.format");
        this.changeFormat = config.getString("Server-change.format");

        this.messageEnabled = config.getBoolean("Message.enabled");
        this.joinEnabled = config.getBoolean("Join.enabled");
        this.leaveEnabled = config.getBoolean("Leave.enabled");
        this.kickEnabled = config.getBoolean("Kick.enabled");
        this.changeEnabled = config.getBoolean("Server-change.enabled");

        this.aliases = config.getTable("Aliases");

        this.messagecmd = config.getList("Message.commands");
        this.joincmd = config.getList("Join.commands");
        this.leavecmd = config.getList("Leave.commands");
        this.kickcmd = config.getList("Kick.commands");
        this.changecmd = config.getList("Server-change.commands");

        this.minimessage = config.getBoolean("Message-format.minimessage");
        this.all = config.getBoolean("Message.all", false);

        this.papiEnabled = config.getBoolean("Message.papiproxybridge", true);
        this.papiCacheMillis = config.getLong("Message.papi-cache-millis", 30000L);
        this.papiTimeoutMillis = config.getLong("Message.papi-timeout-millis", 1500L);
        this.papiRetryTimes = config.getLong("Message.papi-retry-times", 0L).intValue();

        this.messageColors = normalizeColorMode(config.getString("Message.message-colors", "strip"));
        this.namedColors = readNamedColors(config);

        this.customMeta = readCustomMeta(config);

        this.noPapiFormat = trimToNull(config.getString("Message.no-papi-format", ""));
        this.readBridgeBlacklist = config.getBoolean("Message.read-bridge-blacklist", true);
        this.noPapiServers.clear();
        this.noPapiServers.addAll(readServerList(config, "Message.no-papi-servers"));
        mergeBridgeBlacklist();
    }
}
