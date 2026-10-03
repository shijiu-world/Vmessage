package me.feusalamander.vmessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 PAPIProxyBridge（代理端）的 settings.yml 里读出「不发变量请求」的服务器名单。
 *
 * <p>子服没装 PAPIProxyBridge-Bukkit 时（比如 killer 连 PlaceholderAPI 都没有），
 * 标准做法是在代理端 settings.yml 里把它列进黑名单：
 * <pre>
 *   serverListMode: BLACKLIST
 *   serverList:
 *     - killer
 * </pre>
 * Vmessage 需要知道同一份名单 —— 这些服收不到 %xxx% 的解析结果，
 * 应该改用 [Message] 里的 no-papi-format。这里直接读 PPB 的配置，
 * 免得同一个名单要在两个插件里各维护一遍。
 *
 * <p>读不到（没装 PPB、目录名/文件名不同、模式不是 BLACKLIST）就返回空集合，
 * 不影响插件正常运行 —— 那时名单只来自 config.toml 的 no-papi-servers。
 *
 * <p>只认最朴素的 YAML 写法，不引第三方 YAML 库：
 * {@code key: value} 和 {@code - value} 列表，也接受 {@code key: [a, b]}。
 */
public final class PapiBlacklist {
    private static final Logger LOGGER = LoggerFactory.getLogger("vmessage");

    /** settings.yml 里那段：serverListMode: BLACKLIST｜WHITELIST */
    private static final Pattern MODE = Pattern.compile(
            "^[ \\t]*serverListMode[ \\t]*:[ \\t]*([A-Za-z_]+)", Pattern.MULTILINE);

    /** serverList: 这一行，后面可能直接跟内联数组 */
    private static final Pattern LIST_HEAD = Pattern.compile(
            "^([ \\t]*)serverList[ \\t]*:[ \\t]*(.*)$", Pattern.MULTILINE);

    /** 列表项：- killer / -"killer" / - 'killer' */
    private static final Pattern ITEM = Pattern.compile(
            "^[ \\t]*-[ \\t]*[\"']?([^\\s\"'#,]+)[\"']?[ \\t]*(?:#.*)?$");

    private PapiBlacklist() {
    }

    /**
     * @param pluginsDir 代理的 plugins 目录（Vmessage 数据目录的上一级）
     * @return 黑名单里的服务器名，全小写；读不到就是空集合
     */
    public static Set<String> read(final Path pluginsDir) {
        if (pluginsDir == null) {
            return Collections.emptySet();
        }
        final Path settings = findSettings(pluginsDir);
        if (settings == null) {
            return Collections.emptySet();
        }
        try {
            final String text = new String(Files.readAllBytes(settings), StandardCharsets.UTF_8);
            final Matcher mode = MODE.matcher(text);
            if (!mode.find() || !mode.group(1).equalsIgnoreCase("BLACKLIST")) {
                LOGGER.info("[vmessage] PAPIProxyBridge 的 serverListMode 不是 BLACKLIST，"
                        + "不自动沿用它的名单（可改 config.toml 的 read-bridge-blacklist = false 关掉这条提示）。");
                return Collections.emptySet();
            }
            final Set<String> names = parseList(text);
            if (!names.isEmpty()) {
                LOGGER.info("[vmessage] 沿用 PAPIProxyBridge 黑名单：" + String.join(", ", names)
                        + " —— 这些服将使用 no-papi-format。");
            }
            return names;
        } catch (IOException e) {
            LOGGER.warn("[vmessage] 读取 " + settings + " 失败：" + e.getMessage());
            return Collections.emptySet();
        }
    }

    /** 在 plugins/ 下找 PAPIProxyBridge 的数据目录（大小写不敏感）里的 settings.yml。 */
    private static Path findSettings(final Path pluginsDir) {
        if (!Files.isDirectory(pluginsDir)) {
            return null;
        }
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(pluginsDir)) {
            for (final Path dir : dirs) {
                final String name = dir.getFileName().toString();
                if (!Files.isDirectory(dir) || !name.equalsIgnoreCase("papiproxybridge")) {
                    continue;
                }
                for (final String fileName : new String[]{"settings.yml", "settings.yaml", "config.yml"}) {
                    final Path candidate = dir.resolve(fileName);
                    if (Files.isRegularFile(candidate)) {
                        return candidate;
                    }
                }
            }
        } catch (IOException ignored) {
            // 目录读不了就当没装
        }
        return null;
    }

    /**
     * 解析 serverList 段：支持
     * <pre>
     *   serverList:
     *     - killer
     * </pre>
     * 也支持 {@code serverList: [killer, bedwars]}。
     */
    private static Set<String> parseList(final String text) {
        final Set<String> names = new LinkedHashSet<>();
        final Matcher head = LIST_HEAD.matcher(text);
        if (!head.find()) {
            return names;
        }
        final String inline = head.group(2).trim();
        if (inline.startsWith("[")) {
            final int end = inline.indexOf(']');
            final String body = end < 0 ? inline.substring(1) : inline.substring(1, end);
            for (final String part : body.split(",")) {
                final String name = clean(part);
                if (!name.isEmpty()) {
                    names.add(name.toLowerCase(Locale.ROOT));
                }
            }
            return names;
        }
        // 逐行往下吃，直到出现一个既不是列表项、也不是空行/注释的行
        final List<String> lines = List.of(text.split("\r?\n"));
        for (int i = indexOfLine(text, head.start()) + 1; i < lines.size(); i++) {
            final String line = lines.get(i);
            if (line.isBlank() || line.trim().startsWith("#")) {
                continue;
            }
            final Matcher item = ITEM.matcher(line);
            if (!item.matches()) {
                break;
            }
            names.add(item.group(1).toLowerCase(Locale.ROOT));
        }
        return names;
    }

    /** 给定字符偏移，算出它在第几行（从 0 开始）。 */
    private static int indexOfLine(final String text, final int offset) {
        int line = 0;
        for (int i = 0; i < offset && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static String clean(final String raw) {
        return raw.trim().replace("\"", "").replace("'", "");
    }
}
