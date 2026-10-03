package me.feusalamander.vmessage;

import net.kyori.adventure.text.Component;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 玩家聊天内容里的颜色码处理。
 *
 * <p>后端（CMI / CMILib）认的语法不止 {@code &4} 一种，CMIChatColor 里实际支持：
 * <ul>
 *   <li>{@code &0}-{@code &f}、{@code &k}-{@code &o}、{@code &r}（16 色 + 格式码）</li>
 *   <li>{@code &#RRGGBB} / {@code &#RGB}（CMILib 叫 quirky hex）</li>
 *   <li>{@code &x&R&R&G&G&B&B}（1.16 原生 hex 写法）</li>
 *   <li>{@code {#RRGGBB}} / {@code {#RGB}} / {@code {#颜色名}}（CMI 写法）</li>
 *   <li>{@code {@字体名}}（自定义字体）</li>
 *   <li>{@code {#RRGGBB>}文字{#RRGGBB<}}（渐变）</li>
 * </ul>
 * 但跨服消息是代理直接发给客户端的，后端插件看不到，这些语法到代理端就成了纯文本。
 * 这里把它们归一化成 adventure 的 legacy 写法（{@code &} 码 + {@code &#RRGGBB}）再解析；
 * legacy 表达不了的（字体、真渐变）就退化处理，绝不把标记原样丢给玩家。
 */
public final class ChatColors {

    /** 渐变起始标记 {@code {#RRGGBB>}} —— 取它的颜色给后面的文字（代理端算不出真渐变，退化为起始色） */
    private static final Pattern GRADIENT_START =
            Pattern.compile("\\{#([0-9a-fA-F]{6}|[0-9a-fA-F]{3}|[A-Za-z_][A-Za-z0-9_]{2,})>\\}");
    /** 渐变结束标记 {@code {#RRGGBB<}} / {@code {#RRGGBB<>}} */
    private static final Pattern GRADIENT_END = Pattern.compile("\\{#[^\\{\\}]*?<(>?)\\}");
    /** {@code {@字体}} —— legacy 序列化器表达不了字体，只能摘掉 */
    private static final Pattern FONT = Pattern.compile("\\{@[^\\{\\}]*\\}");
    private static final Pattern HEX_BRACE_6 = Pattern.compile("\\{#[0-9a-fA-F]{6}\\}");
    private static final Pattern HEX_BRACE_3 = Pattern.compile("\\{#([0-9a-fA-F])([0-9a-fA-F])([0-9a-fA-F])\\}");
    /** {@code {#颜色名}} —— 查配置表，查不到就摘掉 */
    private static final Pattern NAMED = Pattern.compile("\\{#([A-Za-z_][A-Za-z0-9_]{2,})\\}");
    private static final Pattern AMP_X = Pattern.compile("[&§]x(?:[&§][0-9a-fA-F]){6}");
    /** 3 位简写 {@code &#RGB}：后面不能再跟 hex 字符，否则会误吃掉 {@code &#FF0000} 的前三位 */
    private static final Pattern AMP_HEX_3 = Pattern.compile("[&§]#([0-9a-fA-F])([0-9a-fA-F])([0-9a-fA-F])(?![0-9a-fA-F])");

    /** strip 模式要摘掉的全部标记，按最长优先排列 */
    private static final Pattern STRIP = Pattern.compile(
            "[&§]x(?:[&§][0-9a-fA-F]){6}"                    // &x&F&F&0&0&0&0
                    + "|[&§]#[0-9a-fA-F]{6}"                  // &#RRGGBB
                    + "|[&§]#[0-9a-fA-F]{3}(?![0-9a-fA-F])"   // &#RGB
                    + "|[&§][0-9a-fA-Fk-oK-OrR]"              // &4 &l &r ...
                    + "|\\{#[^\\{\\}]*?[<>][>]?\\}"           // 渐变起始/结束
                    + "|\\{@[^\\{\\}]*\\}"                    // 字体
                    + "|\\{#[A-Za-z0-9_]*\\}");               // {#RRGGBB} {#RGB} {#名字}

    private ChatColors() {
    }

    /**
     * 摘掉聊天内容里所有颜色标记，只留文字。对应 CMI 的 {@code Colors.CleanUp}：
     * 没颜色权限的玩家，颜色码是被移除，而不是把 {@code &4} 显示给他看。
     */
    public static String strip(final String s) {
        return s == null ? "" : STRIP.matcher(s).replaceAll("");
    }

    /**
     * 把 CMI 那套写法归一化成 adventure legacy 能吃的 {@code &} 码串。
     * 只做字符串变换，不解析 —— 便于单独测试。
     */
    public static String normalize(String s, final Map<String, String> namedColors) {
        if (s == null) {
            return "";
        }
        // § 和 & 是同一套东西，先统一
        s = s.replace('§', '&');
        // 渐变：起始标记留下它的颜色，结束标记摘掉（代理端没有逐字染色的能力）
        s = GRADIENT_START.matcher(s).replaceAll("&#$1");
        s = GRADIENT_END.matcher(s).replaceAll("");
        s = FONT.matcher(s).replaceAll("");
        s = normalizeBraces(s);
        s = HEX_BRACE_3.matcher(s).replaceAll("&#$1$1$2$2$3$3");
        s = replaceNamed(s, namedColors);
        s = replaceAmpX(s);
        s = AMP_HEX_3.matcher(s).replaceAll("&#$1$1$2$2$3$3");
        return s;
    }

    /** 按模式把聊天内容变成组件：parse 解析颜色，strip / keep 都当纯文本。 */
    public static Component component(final String mode, final String raw, final Map<String, String> namedColors) {
        if ("parse".equals(mode)) {
            return Listeners.SERIALIZER.deserialize(normalize(raw, namedColors));
        }
        return Component.text(raw == null ? "" : raw);
    }

    /** {@code {#RRGGBB}} → {@code &#RRGGBB}（只动花括号包裹的 hex，不碰普通花括号） */
    private static String normalizeBraces(final String s) {
        final Matcher m = HEX_BRACE_6.matcher(s);
        final StringBuilder sb = new StringBuilder(s.length());
        while (m.find()) {
            m.appendReplacement(sb, "&#" + m.group().substring(2, 8));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** {@code {#名字}} → 配置表里查得到就换成 hex，查不到就摘掉 */
    private static String replaceNamed(final String s, final Map<String, String> namedColors) {
        final Matcher m = NAMED.matcher(s);
        final StringBuilder sb = new StringBuilder(s.length());
        while (m.find()) {
            final String hex = namedColors == null
                    ? null : namedColors.get(m.group(1).toLowerCase(Locale.ROOT));
            m.appendReplacement(sb, hex == null ? "" : Matcher.quoteReplacement("&#" + hex));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** {@code &x&F&F&0&0&0&0} → {@code &#FF0000} */
    private static String replaceAmpX(final String s) {
        final Matcher m = AMP_X.matcher(s);
        final StringBuilder sb = new StringBuilder(s.length());
        while (m.find()) {
            m.appendReplacement(sb, "&#" + m.group().replaceAll("[&§xX]", ""));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
