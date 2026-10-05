package me.feusalamander.vmessage;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 聊天格式串的收尾清理：占位符取不到值时，除了把占位符本身删掉，
 * 还要把它留下的多余空格一并处理掉，否则会看到这种东西：
 *
 * <pre>
 *   格式   &amp;r%playerTitle_use% [&amp;6%legendaryguild_guild%&amp;r] #prefix#&amp;f#player#&amp;7: &amp;r#message#
 *   全空   [生存]   Steve: 大家好      ← 中间三个空格
 * </pre>
 *
 * 直接把连续空格折叠成一个是不行的 —— 变量自己的值里可能就有空格
 * （比如称号 {@code &f[ 大佬 &f]}），折叠会把它破坏掉。
 * 所以这里用哨兵记下"这一段被删了"这个位置，只在这些位置上动空格：
 *
 * <pre>
 *   ① PAPI 解析【之前】在 %xxx% 两侧套标记，记住"这是一个变量的边界"
 *   ② PAPI 解析【之后】按标记取回变量值；值里只有颜色码/空格 = 这个变量没内容 → 换成哨兵
 *   ③ 占位符 / 空掉的方括号   → 哨兵（不直接删，位置信息还有用）
 *   ④ 哨兵吸收自己两侧的空格、以及紧贴它左边的颜色码（那是给"已经没了的那一段"用的）
 *   ⑤ 连续的哨兵合并成一个
 *   ⑥ 哨兵两边都还有内容 → 换成单个空格；否则直接删掉
 * </pre>
 *
 * <p>为什么必须加 ①②：变量【解析成功但是空串】时，{@code %xxx%} 已经不在字符串里了，
 * 靠"还有没有 %xxx% 字面量"判断不出这段是空的 —— 于是格式里它两侧写的空格一个都删不掉，
 * 表现为 {@code [server1]  [无公会]}（中间俩空格）。标记是唯一能记住边界的办法。
 */
public final class FormatCleaner {

    /**
     * 哨兵字符：NUL，正常聊天里不可能出现。
     * 用 (char)0 而不是 "\u0000" 字面量 —— 后者会被 javac 在词法阶段直接换成 NUL 字符塞进源码。
     */
    public static final String HOLE = String.valueOf((char) 0);

    /**
     * 变量边界标记：Unicode 私用区字符，配置/聊天/称号值里都不会出现。
     * ⚠️ 不用 NUL（哨兵）—— 标记是要【跟着字符串过一遍 PAPIProxyBridge】的，
     *    跟哨兵用同一个字符会在后续步骤里被当成"空段"吃掉。
     */
    private static final char MARK_CHAR = (char) 0xE000;
    private static final String MARK = String.valueOf(MARK_CHAR);

    /** 颜色码：{@code &c} / {@code §c} / {@code &#RRGGBB} / {@code &x&F&F&F&0&0&0} */
    private static final String COLOR = "[&§](?:#[0-9a-fA-F]{6}"
            + "|#[0-9a-fA-F]{3}(?![0-9a-fA-F])"
            + "|x(?:[&§][0-9a-fA-F]){6}"
            + "|[0-9a-fA-Fk-oK-OrR])";

    /** PAPI 变量 {@code %xxx%}。解析前要拿它套标记，解析后残留的说明没解析出来。 */
    private static final Pattern PAPI_VAR = Pattern.compile("%[A-Za-z0-9_.\\-]{1,64}%");

    /** 一对标记之间的内容 = 一个变量解析后的值 */
    private static final Pattern MARKED = Pattern.compile(
            MARK + "([^" + MARK + "]*)" + MARK);

    /** "看不见"的字符：空白 + 颜色码。变量值只剩这些东西时，就等于没有值。 */
    private static final Pattern INVISIBLE = Pattern.compile("[ \\t]|" + COLOR);

    /**
     * 括号里除了颜色码和哨兵之外什么都没有 —— 说明它包裹的变量没解析出来，
     * 例如 {@code [&6%legendaryguild_guild%&r]} → {@code [&6&r]}，留着会显示成一对空的 []。
     * 括号前的那个空格一起吃掉，不然删完会留下双空格。
     */
    private static final Pattern EMPTY_BRACKETS = Pattern.compile(
            "[ \\t]?\\[(?:[ \\t]*(?:[&§](?:[0-9a-fA-Fk-oK-OrR]|#[0-9a-fA-F]{6})|\\x00))*[ \\t]*\\]");

    /** 哨兵连同它两侧的空格 */
    private static final Pattern HOLE_SPACES = Pattern.compile("[ \\t]*\\x00[ \\t]*");

    /**
     * 哨兵 + 紧贴它左边的颜色码。
     * 那些颜色码是给"已经没了的那一段"写的（{@code &r%title% }），段没了它们也没意义；
     * 不一起吃掉的话，{@code ] &r [} 会被判成"两边都有内容"而保留两个空格
     * （{@code &r} 是看不见的，玩家只看到俩空格）。
     * ⚠️ 只吃左边：右边的颜色码属于【下一段】（{@code %title% &f#player#} 里的 {@code &f}），吃了名字会变色。
     */
    private static final Pattern HOLE_COLOR_LEFT = Pattern.compile("(?:" + COLOR + ")*\\x00");

    /** 挨在一起的多个哨兵 */
    private static final Pattern HOLE_RUN = Pattern.compile("\\x00+");

    /** 左右都还有可见内容的哨兵 —— 那两个段之间该留一个空格 */
    private static final Pattern HOLE_BETWEEN = Pattern.compile("(?<=\\S)\\x00(?=\\S)");

    private static final Pattern HOLE_ANY = Pattern.compile("\\x00");

    /** 兜底：桥接没原样把标记送回来（成对性被破坏）时，至少别把标记字符发给玩家 */
    private static final Pattern MARK_ANY = Pattern.compile(MARK);

    private FormatCleaner() {
    }

    /**
     * 第 ① 步：PAPI 解析【之前】调用 —— 给每个 {@code %xxx%} 两侧套上标记。
     *
     * <p>标记紧贴占位符、不包含在 {@code %...%} 里面，所以不影响 PlaceholderAPI 的匹配，
     * 也不影响 PAPIProxyBridge 的缓存（同一个格式串每次都套出同样的标记）。
     */
    public static String mark(final String s) {
        if (s == null) {
            return "";
        }
        // $0 = 整个 %xxx%；MARK 里没有 $ 和 \，不用 quoteReplacement
        return PAPI_VAR.matcher(s).replaceAll(MARK + "$0" + MARK);
    }

    /**
     * 第 ② 步：PAPI 解析【之后】调用 —— 按标记把变量值取回来，空值换成哨兵。
     *
     * <p>"空"的判定是【去掉颜色码和空格之后什么都不剩】：
     * 称号插件返回空串、返回 {@code &r}、返回几个空格，都算没有称号。
     * 值没解析出来（原样 {@code %xxx%}）时这里原样留着，交给 {@link #stripUnresolved} 变哨兵。
     */
    public static String resolveMarks(final String s) {
        if (s == null) {
            return "";
        }
        if (s.indexOf(MARK_CHAR) < 0) {
            return s;
        }
        final Matcher m = MARKED.matcher(s);
        final StringBuffer sb = new StringBuffer();
        while (m.find()) {
            final String inner = m.group(1);
            final boolean empty = INVISIBLE.matcher(inner).replaceAll("").isEmpty();
            m.appendReplacement(sb, Matcher.quoteReplacement(empty ? HOLE : inner));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * 第 ③ 步：把没取到值的占位符和空掉的方括号换成哨兵。
     * 这里不直接删，是为了让 {@link #collapse} 知道"这里原本有一段东西"。
     */
    public static String stripUnresolved(final String s) {
        if (s == null) {
            return "";
        }
        final String holes = PAPI_VAR.matcher(s).replaceAll(Matcher.quoteReplacement(HOLE));
        return EMPTY_BRACKETS.matcher(holes).replaceAll(Matcher.quoteReplacement(HOLE));
    }

    /**
     * 第 ④⑤⑥ 步：处理哨兵两侧的空格和颜色码，再把哨兵删掉。
     *
     * <ul>
     *   <li>哨兵两侧都还有内容 → 留一个空格（两个都还在的段之间需要分隔）</li>
     *   <li>哨兵在开头 / 结尾，或相邻段也是空的 → 一个空格都不留</li>
     * </ul>
     */
    public static String collapse(final String s) {
        if (s == null) {
            return "";
        }
        // 走完 resolveMarks 还剩下的标记 = 成对性被破坏了（桥接没原样送回来）。
        // 当空段处理，别把标记字符漏给玩家，也别留下空格。
        String t = MARK_ANY.matcher(s).replaceAll(Matcher.quoteReplacement(HOLE));
        t = HOLE_SPACES.matcher(t).replaceAll(Matcher.quoteReplacement(HOLE));
        t = HOLE_COLOR_LEFT.matcher(t).replaceAll(Matcher.quoteReplacement(HOLE));
        t = HOLE_RUN.matcher(t).replaceAll(Matcher.quoteReplacement(HOLE));
        t = HOLE_BETWEEN.matcher(t).replaceAll(" ");
        t = HOLE_ANY.matcher(t).replaceAll("");
        // 段全空时开头会剩一个空格（CMI 格式里 %playerTitle_use% 就是第一个字符）
        return t.trim();
    }

    /** 抹占位符 + 折叠空格，一条龙。只在 #message# 替换之前调用。 */
    public static String finish(final String s) {
        return collapse(stripUnresolved(resolveMarks(s)));
    }

    /**
     * 只把哨兵删掉，不做空格折叠。
     * 给 messagecmd 这类"不是聊天消息"的场景用 —— 那里拼的是命令，不该动空格。
     */
    public static String removeHoles(final String s) {
        if (s == null) {
            return "";
        }
        return MARK_ANY.matcher(HOLE_ANY.matcher(s).replaceAll("")).replaceAll("");
    }
}
