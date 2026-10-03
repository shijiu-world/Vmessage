package me.feusalamander.vmessage;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 聊天格式串的收尾清理：占位符取不到值时，除了把占位符本身删掉，
 * 还要把它留下的多余空格一并处理掉，否则会看到这种东西：
 *
 * <pre>
 *   格式   &r%playerTitle_use% [&6%legendaryguild_guild%&r] #prefix#&amp;f#player#&amp;7: &amp;r#message#
 *   全空   [生存]   Steve: 大家好      ← 中间三个空格
 * </pre>
 *
 * 直接把连续空格折叠成一个是不行的 —— 变量自己的值里可能就有空格
 * （比如称号 {@code §f[ 大佬 §f]}），折叠会把它破坏掉。
 * 所以这里用哨兵记下"这一段被删了"这个位置，只在这些位置上动空格：
 *
 * <pre>
 *   ① 占位符 / 空掉的方括号   → 哨兵（不直接删，位置信息还有用）
 *   ② 哨兵吸收自己两侧的空格
 *   ③ 连续的哨兵合并成一个
 *   ④ 哨兵两边都还有内容 → 换成单个空格；否则直接删掉
 * </pre>
 */
public final class FormatCleaner {

    /**
     * 哨兵字符：NUL，正常聊天里不可能出现。
     * 用 (char)0 而不是 "\u0000" 字面量 —— 后者会被 javac 在词法阶段直接换成 NUL 字符塞进源码。
     */
    public static final String HOLE = String.valueOf((char) 0);

    /** 没解析掉的 PAPI 变量，例如 %playerTitle_use% */
    private static final Pattern UNRESOLVED = Pattern.compile("%[A-Za-z0-9_.\\-]{1,64}%");

    /**
     * 括号里除了颜色码和哨兵之外什么都没有 —— 说明它包裹的变量没解析出来，
     * 例如 [&6%legendaryguild_guild%&r] → [&6&r]，留着会显示成一对空的 []。
     * 括号前的那个空格一起吃掉，不然删完会留下双空格。
     */
    private static final Pattern EMPTY_BRACKETS = Pattern.compile(
            "[ \\t]?\\[(?:[ \\t]*(?:[&§](?:[0-9a-fA-Fk-oK-OrR]|#[0-9a-fA-F]{6})|\\x00))*[ \\t]*\\]");

    /** 哨兵连同它两侧的空格 */
    private static final Pattern HOLE_SPACES = Pattern.compile("[ \\t]*\\x00[ \\t]*");

    /** 挨在一起的多个哨兵 */
    private static final Pattern HOLE_RUN = Pattern.compile("\\x00+");

    /** 左右都还有可见内容的哨兵 —— 那两个段之间该留一个空格 */
    private static final Pattern HOLE_BETWEEN = Pattern.compile("(?<=\\S)\\x00(?=\\S)");

    private static final Pattern HOLE_ANY = Pattern.compile("\\x00");

    private FormatCleaner() {
    }

    /**
     * 第一步：把没取到值的占位符和空掉的方括号换成哨兵。
     * 这里不直接删，是为了让 {@link #collapse} 知道"这里原本有一段东西"。
     */
    public static String stripUnresolved(final String s) {
        if (s == null) {
            return "";
        }
        final String holes = UNRESOLVED.matcher(s).replaceAll(Matcher.quoteReplacement(HOLE));
        return EMPTY_BRACKETS.matcher(holes).replaceAll(Matcher.quoteReplacement(HOLE));
    }

    /**
     * 第二步：处理哨兵两侧的空格，再把哨兵删掉。
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
        String t = HOLE_SPACES.matcher(s).replaceAll(Matcher.quoteReplacement(HOLE));
        t = HOLE_RUN.matcher(t).replaceAll(Matcher.quoteReplacement(HOLE));
        t = HOLE_BETWEEN.matcher(t).replaceAll(" ");
        t = HOLE_ANY.matcher(t).replaceAll("");
        // 段全空时开头会剩一个空格（CMI 格式里 %playerTitle_use% 就是第一个字符）
        return t.trim();
    }

    /** 抹占位符 + 折叠空格，一条龙。只在 #message# 替换之前调用。 */
    public static String finish(final String s) {
        return collapse(stripUnresolved(s));
    }

    /**
     * 只把哨兵删掉，不做空格折叠。
     * 给 messagecmd 这类"不是聊天消息"的场景用 —— 那里拼的是命令，不该动空格。
     */
    public static String removeHoles(final String s) {
        return s == null ? "" : HOLE_ANY.matcher(s).replaceAll("");
    }
}
