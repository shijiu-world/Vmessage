package me.feusalamander.vmessage;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.slf4j.LoggerFactory;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 给**转发出去的聊天消息**挂上悬停提示和点击动作 —— 别的子服的玩家看到这条消息时，
 * 鼠标放在「前缀 + 名字」那一段上能看见发送时间，点一下会把命令（默认 /msg 发送者）填进聊天框。
 *
 * <p>三档挂载点，各有各的效果（adventure 里**子节点自己设过的事件优先**，不会继承父的）：
 * <ol>
 *   <li>聊天的**整条**（根组件）：悬停 = 发送时间，点击 = 填命令；{@link #apply(Component, Configuration, String, String)}</li>
 *   <li>聊天的**正文**（#message# 那一截）：悬停 = 复制提示，点击 = 复制到剪贴板；
 *       它自己设了事件，所以**不会**继承根上的「发送时间 / 填 /msg」；{@link #copy(Component, Configuration, String)}</li>
 *   <li>五类广播的整条：**只要悬停，不要点击**（广播没有「给谁发消息」的含义）；
 *       {@link #applyHover(Component, Configuration, String, String)}</li>
 * </ol>
 *
 * <p>⚠️ 为什么是给组件的**根**挂事件：adventure 的事件跟样式一样是沿组件树往下继承的，
 *    自己没设过的子文本会继承根上的那份，而正文里的「[链接]」自己带了 openUrl 和网址提示，
 *    不会被这里覆盖（点链接照旧打开浏览器）。
 */
public final class ChatTooltip {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&')
            .hexColors()
            .build();
    /** 挂事件失败只报一次，别每条聊天都刷屏。 */
    private static final AtomicBoolean WARNED = new AtomicBoolean(true);

    /** 悬停提示的默认值：&e发送时间: &6{time} */
    static final String DEFAULT_HOVER = "&e发送时间: &6{time}";
    /** 点击填入聊天框的命令默认值（末尾留一个空格，玩家接着就能输入内容）。 */
    static final String DEFAULT_SUGGEST = "/msg {player} ";
    /** {time} 默认按 24 小时制的 时:分:秒 显示。 */
    static final String DEFAULT_TIME_PATTERN = "HH:mm:ss";
    /** 鼠标放在正文上时默认的提示。 */
    static final String DEFAULT_COPY_HOVER = "&7复制该文本";
    /** 时间格式写错时的兜底。 */
    static final DateTimeFormatter FALLBACK_TIME = DateTimeFormatter.ofPattern(DEFAULT_TIME_PATTERN);

    private ChatTooltip() {
    }

    /**
     * 这个功能当不当 effective：总开关开着，且 hover / suggest 里至少有一个写了东西。
     */
    static boolean enabled(final Configuration cfg) {
        return cfg != null && cfg.isTooltipEnabled()
                && (hasText(cfg.getTooltipHover()) || hasText(cfg.getTooltipSuggest()));
    }

    /** 正文的「复制」这一档当不当生效：总开关 + copy 开关都开着。 */
    static boolean copyEnabled(final Configuration cfg) {
        return cfg != null && cfg.isTooltipEnabled() && cfg.isTooltipCopyEnabled();
    }

    /**
     * 给一条已经拼好的聊天组件挂上悬停提示和点击动作（**转发的聊天**用这一档）。
     *
     * @param message 发给目标子服的消息组件
     * @param player  发送者名字（填 {player}）
     * @param server  发送者所在子服的显示名（走 [Aliases] 别名，填 {server}）
     * @return 挂好事件的组件；挂不上时原样返回（消息本身必须发出去）
     */
    static Component apply(final Component message, final Configuration cfg,
                           final String player, final String server) {
        return decorate(message, cfg, player, server, true);
    }

    /**
     * 五类广播用这一档：**只挂悬停，不挂点击** —— 广播里没有「给谁发消息」的含义，
     * 点一下填 /msg 只会让人莫名其妙（尤其是 Leave / Kick 那些人已经不在线了）。
     */
    static Component applyHover(final Component message, final Configuration cfg,
                                final String player, final String server) {
        return decorate(message, cfg, player, server, false);
    }

    private static Component decorate(final Component message, final Configuration cfg,
                                      final String player, final String server,
                                      final boolean withClick) {
        // enabled() 里已经把「开关 + 有没有内容」都判过了 —— 单独调用时也要认这个开关
        if (message == null || !enabled(cfg)) {
            return message;
        }
        final String hover = cfg.getTooltipHover();
        final String suggest = withClick ? cfg.getTooltipSuggest() : "";
        if (!hasText(hover) && !hasText(suggest)) {
            return message;
        }
        Component out = message;
        try {
            if (hasText(hover)) {
                out = out.hoverEvent(HoverEvent.showText(
                        LEGACY.deserialize(fill(hover, cfg, player, server))));
            }
            if (hasText(suggest)) {
                out = out.clickEvent(ClickEvent.suggestCommand(fill(suggest, cfg, player, server)));
            }
        } catch (final RuntimeException e) {
            // 组件不支持挂事件时（比如 MiniMessage 模式解析出了非文本节点）退化没有悬停/点击，
            // 也比整条聊天发不出去强
            warnOnce(e);
            return message;
        }
        return out;
    }

    /**
     * 给**聊天正文**（#message# 那一截）挂上「复制」：悬停显示 copy-hover，点一下复制到剪贴板。
     *
     * <p>⚠️ 这里必须**显式**挂上事件（哪怕提示是空的）—— 子节点自己设过事件就不会继承父的，
     * 而父（整条消息）挂的是「悬停看发送时间 / 点击填 /msg」，正文不覆盖掉就会连它一起继承，
     * 那玩家在正文上也会看到发送时间、点一下也变成填 /msg，正是要避免的。
     *
     * @param body 正文组件（已经做完颜色和网址处理）
     * @param text 点一下要复制到剪贴板的纯文本
     */
    static Component copy(final Component body, final Configuration cfg, final String text) {
        if (body == null || !copyEnabled(cfg) || text == null || text.isEmpty()) {
            return body;
        }
        final String hover = cfg.getTooltipCopyHover();
        try {
            // 提示留空也要 showText 一个空组件：空 = 什么都不显示，但能挡住父组件的悬停继承下来
            final Component tip = hasText(hover) ? LEGACY.deserialize(hover) : Component.empty();
            return body.hoverEvent(HoverEvent.showText(tip))
                    .clickEvent(ClickEvent.copyToClipboard(text));
        } catch (final RuntimeException e) {
            warnOnce(e);
            return body;
        }
    }

    /**
     * 组件 → 纯文本（复制时用）。
     *
     * <p>⚠️ 不能用 `PlainComponentSerializer`：它不在 Velocity 4.x 的 jar 里（编译能过、运行没有），
     * 所以自己走一遍组件树。
     */
    static String plain(final Component c) {
        final StringBuilder sb = new StringBuilder();
        plain(sb, c);
        return sb.toString();
    }

    private static void plain(final StringBuilder sb, final Component c) {
        if (c instanceof TextComponent) {
            final String content = ((TextComponent) c).content();
            if (content != null) {
                sb.append(content);
            }
        }
        for (final Component child : c.children()) {
            plain(sb, child);
        }
    }

    /** 把 {time} / {player} / {server} 三个占位符换成真值。 */
    private static String fill(final String text, final Configuration cfg,
                               final String player, final String server) {
        if (text.indexOf('{') < 0) {
            return text;
        }
        String out = text.replace("{player}", player == null ? "" : player)
                .replace("{server}", server == null ? "" : server);
        if (out.contains("{time}")) {
            out = out.replace("{time}", now(cfg));
        }
        return out;
    }

    /** 现在这一刻按配置里的格式/时区渲染出来的文本。 */
    private static String now(final Configuration cfg) {
        DateTimeFormatter format = cfg.getTooltipTimeFormat();
        if (format == null) {
            format = FALLBACK_TIME;
        }
        ZoneId zone = cfg.getTooltipZone();
        if (zone == null) {
            zone = ZoneId.systemDefault();
        }
        try {
            return ZonedDateTime.now(zone).format(format);
        } catch (final RuntimeException e) {
            // 格式串对某些时刻渲染不出来（极少见）：退回纯 时:分:秒，至少时间还在
            warnOnce(e);
            return ZonedDateTime.now(zone).format(FALLBACK_TIME);
        }
    }

    private static boolean hasText(final String s) {
        return s != null && !s.trim().isEmpty();
    }

    /** 时间格式写错时的兜底 —— Configuration 读配置时用。 */
    static DateTimeFormatter compileTimeFormat(final String pattern) {
        final String p = pattern == null ? "" : pattern.trim();
        if (p.isEmpty()) {
            return FALLBACK_TIME;
        }
        try {
            return DateTimeFormatter.ofPattern(p);
        } catch (final IllegalArgumentException e) {
            LoggerFactory.getLogger("vmessage").warn(
                    "[vmessage] Tooltip.time-format 写法有误（" + p + "），已按 HH:mm:ss 处理。"
                            + "参考 Java 的时间格式：yyyy-MM-dd HH:mm:ss");
            return FALLBACK_TIME;
        }
    }

    /** 时区名写错时的兜底 —— Configuration 读配置时用。 */
    static ZoneId parseZone(final String id) {
        if (id == null || id.trim().isEmpty()) {
            return ZoneId.systemDefault();
        }
        final String name = id.trim();
        try {
            return ZoneId.of(name);
        } catch (final RuntimeException e) {
            LoggerFactory.getLogger("vmessage").warn(
                    "[vmessage] Tooltip.time-zone 认不出这个时区（" + name + "），已按服务器系统时区处理。"
                            + "参考写法：Asia/Shanghai");
            return ZoneId.systemDefault();
        }
    }

    private static void warnOnce(final RuntimeException e) {
        if (WARNED.compareAndSet(true, false)) {
            LoggerFactory.getLogger("vmessage").warn(
                    "[vmessage] 给聊天消息挂悬停/点击事件失败，已按普通消息发出（之后不再重复提示）：" + e);
        }
    }
}
