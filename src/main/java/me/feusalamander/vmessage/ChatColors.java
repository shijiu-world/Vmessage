package me.feusalamander.vmessage;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.format.TextColor;

import java.util.ArrayList;
import java.util.List;
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
 * legacy 表达不了的（字体）就摘掉，绝不把标记原样丢给玩家。
 *
 * <p>唯一需要单独处理的是渐变 {@code {#RRGGBB>}文字{#RRGGBB<}}：legacy 串只能给一整段上一种颜色，
 * 所以渐变不走字符串变换，而是把这段文字抠出来、逐字插值染色，再拼回消息里（见 {@link #component}）。
 */
public final class ChatColors {

    /** 成对出现的渐变：{@code {#RRGGBB>}文字{#RRGGBB<}}（结束标记也可能是 {@code {#RRGGBB<>}}）
     *  ⚠️ 两端写成什么都先兜住（含中文这类认不出来的），认不出来时退化成纯文字 —— 绝不把标记漏给玩家 */
    private static final Pattern GRADIENT = Pattern.compile(
            "\\{#([^\\{\\}<>]+)>\\}(.*?)\\{#([^\\{\\}<>]+)<(>?)\\}");
    /** 渐变起始标记 {@code {#RRGGBB>}} —— 没有配对的结束标记时，退化成「用这个颜色染后面所有字」；
     *  认不出来的写法直接摘掉，不留标记 */
    private static final Pattern GRADIENT_START =
            Pattern.compile("\\{#([0-9a-fA-F]{6}|[0-9a-fA-F]{3}|[A-Za-z_][A-Za-z0-9_]{2,})>\\}"
                    + "|\\{#[^\\{\\}<>]*>\\}");
    /** 孤立出现的渐变结束标记 {@code {#RRGGBB<}} */
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
        s = replaceGradientStart(s);
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
        if (!"parse".equals(mode)) {
            return Component.text(raw == null ? "" : raw);
        }
        final String text = raw == null ? "" : raw;
        final List<Gradient> gradients = new ArrayList<>();
        // 渐变没法用一串 & 码表达，先把它们抠出来留个哨兵，其余部分照旧走 legacy 解析，
        // 解析完再把哨兵换成逐字染好色的组件。
        final String holed = extractGradients(text, namedColors, gradients);
        Component result = Listeners.SERIALIZER.deserialize(normalize(holed, namedColors));
        for (int i = 0; i < gradients.size(); i++) {
            result = result.replaceText(TextReplacementConfig.builder()
                    .matchLiteral(sentinel(i))
                    .replacement(gradients.get(i).render())
                    .build());
        }
        return result;
    }

    /**
     * 把 {@code {#A>}文字{#B<}} 换成哨兵，文字和两个端点色记进 {@code out}。
     * 没有配对结束标记的 {@code {#A>}} 不动 —— 它会走 normalize 退化成「染成 A 色」。
     */
    private static String extractGradients(final String s, final Map<String, String> namedColors,
                                           final List<Gradient> out) {
        final Matcher m = GRADIENT.matcher(s);
        final StringBuilder sb = new StringBuilder(s.length());
        while (m.find()) {
            final Integer from = toRgb(m.group(1), namedColors);
            final Integer to = toRgb(m.group(3), namedColors);
            if (from == null || to == null) {
                // 颜色名查不到 / 写法不认识：整段退化成纯文字，标记摘掉
                m.appendReplacement(sb, Matcher.quoteReplacement(
                        STRIP.matcher(m.group(2)).replaceAll("")));
                continue;
            }
            // 段内的其它颜色标记一并摘掉：逐字染色时它们没法表达，留着只会变成字面量
            final String plain = STRIP.matcher(m.group(2)).replaceAll("");
            out.add(new Gradient(from, to, plain));
            m.appendReplacement(sb, Matcher.quoteReplacement(sentinel(out.size() - 1)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 哨兵：NUL 包一个索引，玩家不可能在聊天里打出 NUL。⚠️ 别写 {@code "\u0000"} 字面量，javac 会把它变成真 NUL 字符。 */
    private static String sentinel(final int index) {
        return HOLE + "G" + index + HOLE;
    }

    private static final String HOLE = String.valueOf((char) 0);

    /** 一段渐变的两个端点色 + 文字。 */
    private static final class Gradient {
        private final int from;
        private final int to;
        private final String text;

        private Gradient(final int from, final int to, final String text) {
            this.from = from;
            this.to = to;
            this.text = text;
        }

        /** 逐字插值染色：第 i 个字取 from→to 之间 i/(n-1) 处的颜色。 */
        private Component render() {
            final int[] cps = text.codePoints().toArray();
            if (cps.length == 0) {
                return Component.empty();
            }
            final TextComponent.Builder builder = Component.text();
            for (int i = 0; i < cps.length; i++) {
                final float t = cps.length == 1 ? 0f : (float) i / (cps.length - 1);
                builder.append(Component.text(new String(Character.toChars(cps[i])),
                        TextColor.color(lerp(from, to, t))));
            }
            return builder.build();
        }
    }

    /** 线性插值一个 RGB（按通道算，跟 CMI 的渐变观感一致）。 */
    private static int lerp(final int from, final int to, final float t) {
        final int r = (int) (((from >> 16) & 255) + (((to >> 16) & 255) - ((from >> 16) & 255)) * t);
        final int g = (int) (((from >> 8) & 255) + (((to >> 8) & 255) - ((from >> 8) & 255)) * t);
        final int b = (int) ((from & 255) + ((to & 255) - (from & 255)) * t);
        return (r << 16) | (g << 8) | b;
    }

    /** {@code RRGGBB} / {@code RGB} / 颜色名 → RGB 整数；认不出来返回 null。 */
    private static Integer toRgb(final String token, final Map<String, String> namedColors) {
        if (token == null) {
            return null;
        }
        if (token.matches("[0-9a-fA-F]{6}")) {
            return Integer.parseInt(token, 16);
        }
        if (token.matches("[0-9a-fA-F]{3}")) {
            return Integer.parseInt(
                    "" + token.charAt(0) + token.charAt(0) + token.charAt(1) + token.charAt(1)
                            + token.charAt(2) + token.charAt(2), 16);
        }
        final String hex = namedColors == null ? null
                : namedColors.get(token.toLowerCase(Locale.ROOT));
        if (hex == null || !hex.matches("[0-9a-fA-F]{6}")) {
            return null;
        }
        return Integer.parseInt(hex, 16);
    }

    /**
     * 孤立的渐变起始标记 {@code {#RRGGBB>}}（没有配对的结束标记）：退化成「后面都染成这个颜色」。
     * 认不出颜色的写法（比如打了中文）直接摘掉 —— ⚠️ 不能用 {@code replaceAll("&#$1")}，
     * 那个分支的 group 是 null，会插出一个孤零零的 {@code &#}。
     */
    private static String replaceGradientStart(final String s) {
        final Matcher m = GRADIENT_START.matcher(s);
        final StringBuilder sb = new StringBuilder(s.length());
        while (m.find()) {
            final String hex = m.group(1);
            m.appendReplacement(sb, hex == null ? "" : Matcher.quoteReplacement("&#" + hex));
        }
        m.appendTail(sb);
        return sb.toString();
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
