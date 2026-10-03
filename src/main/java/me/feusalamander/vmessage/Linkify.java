package me.feusalamander.vmessage;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.regex.Pattern;

/**
 * 把聊天内容里的网址变成可点击的短文本（像 Markdown 的 [链接](url) 那样）——
 * 只显示「[链接]」，点一下在浏览器打开，鼠标悬停能看到完整网址。
 *
 * 只作用在**消息内容**上：格式串（称号、公会那些）里不会有网址，也不用被点击。
 */
public final class Linkify {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&')
            .hexColors()
            .build();
    /**
     * 网址末尾常被句号、逗号、右括号这类标点粘住（`看这个 https://a.cn。`）——
     * `\S+` 会把它们一起吃掉，交给 openUrl 就成了打不开的链接，所以先剥掉。
     */
    private static final Pattern TRAILING = Pattern.compile("[.,;:!?\\]\\)}'\"。，、；：！？）】》\"]+$");
    /**
     * 默认只认 RFC 3986 里允许出现在网址里的字符。
     * ⚠️ 不能简单用 `https?://\S+`：中文句子里没有空格，
     *    "看这个 https://a.cn。后面还有话" 会被整句当成链接。
     *    限定字符集后，中文（以及中文标点）天然就是链接的边界。
     */
    private static final Pattern DEFAULT_PATTERN =
            Pattern.compile("https?://[A-Za-z0-9\\-._~:/?#\\[\\]@!$&'()*+,;=%]+");

    private Linkify() {
    }

    /**
     * 默认正则的源码（配置里没写 / 写坏了就用它）。
     * 只认 RFC 3986 允许出现在网址里的字符 —— 中文天然是链接边界，
     * 不会像 `\S+` 那样把「https://a.cn。后面还有话」整句吃进去。
     */
    static final String DEFAULT_PATTERN_SOURCE =
            "https?://[A-Za-z0-9\\-._~:/?#\\[\\]@!$&'()*+,;=%]+";

    /** 正则写错时用它兜底，免得整个聊天挂掉。 */
    static Pattern defaultPattern() {
        return DEFAULT_PATTERN;
    }

    /**
     * @param input 消息内容组件（已经处理完颜色码）
     * @param cfg   当前配置；链接功能关掉时原样返回
     */
    static Component apply(final Component input, final Configuration cfg) {
        if (input == null || cfg == null || !cfg.isLinkEnabled()) {
            return input;
        }
        final Pattern pattern = cfg.getLinkPattern() == null ? DEFAULT_PATTERN : cfg.getLinkPattern();
        final String label = cfg.getLinkText();
        final String hover = cfg.getLinkHover();
        return input.replaceText(TextReplacementConfig.builder()
                .match(pattern)
                .replacement(result -> label(result.content(), label, hover))
                .build());
    }

    /** 造出「[链接]」这个可点击的组件；网址不合法时退回纯文本，不抛异常（玩家输入不可信）。 */
    private static Component label(final String raw, final String label, final String hover) {
        // 尾部的标点不算链接的一部分，但也不能丢掉 —— 剥出来补在链接后面
        final String tail = trailing(raw);
        final String url = raw.substring(0, raw.length() - tail.length());
        if (url.isEmpty()) {
            return Component.text(raw);
        }
        Component text = LEGACY.deserialize(label == null || label.isEmpty() ? "&9[链接]" : label);
        try {
            final ClickEvent click = ClickEvent.openUrl(url);
            text = text.clickEvent(click);
            if (hover != null && !hover.isEmpty()) {
                text = text.hoverEvent(HoverEvent.showText(
                        LEGACY.deserialize(hover.replace("{url}", url))));
            }
        } catch (final IllegalArgumentException e) {
            // 网址里有 openUrl 不接受的东西：退化成普通文本，至少消息还能发出来
            return Component.text(raw);
        }
        return tail.isEmpty() ? text : text.append(Component.text(tail));
    }

    private static String trailing(final String url) {
        final java.util.regex.Matcher m = TRAILING.matcher(url);
        return m.find() ? m.group() : "";
    }
}
