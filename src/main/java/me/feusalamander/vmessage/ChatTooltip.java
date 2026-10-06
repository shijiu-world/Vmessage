package me.feusalamander.vmessage;

import net.kyori.adventure.text.Component;
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
 * 鼠标放上去能看见发送时间，点一下会把命令（默认 /msg 发送者）填进聊天框。
 *
 * <p>只作用在 [Message] 转发的聊天上：Join/Leave/Kick 那些广播没有「给谁发消息」的含义。
 *
 * <p>⚠️ 为什么是给整条消息的**根组件**挂事件：adventure 的事件跟样式一样是沿组件树往下继承的，
 *    自己没设过的子文本会继承根上的那份，而消息里的「[链接]」自己带了 openUrl 和网址提示，
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

    /**
     * 给一条已经拼好的聊天组件挂上悬停提示和点击动作。
     *
     * @param message 发给目标子服的消息组件
     * @param player  发送者名字（填 {player}）
     * @param server  发送者所在子服的显示名（走 [Aliases] 别名，填 {server}）
     * @return 挂好事件的组件；挂不上时原样返回（消息本身必须发出去）
     */
    static Component apply(final Component message, final Configuration cfg,
                           final String player, final String server) {
        // enabled() 里已经把「开关 + 有没有内容」都判过了 —— 单独调用 apply 时也要认这个开关
        if (message == null || !enabled(cfg)) {
            return message;
        }
        final String hover = cfg.getTooltipHover();
        final String suggest = cfg.getTooltipSuggest();
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
