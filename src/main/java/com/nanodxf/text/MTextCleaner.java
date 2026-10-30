package com.nanodxf.text;

/**
 * MTEXT 格式控制码清洗工具。
 *
 * <p>MTEXT 的 group code 1（文本内容）包含格式控制码，直接存储会影响 GIS 属性查询，
 * 必须剥离格式、保留可读文本。
 *
 * <p>处理顺序：
 * <ol>
 *   <li>保护转义字面量：{@code \\} / {@code \&#123;} / {@code \&#125;} → 私有区占位符</li>
 *   <li>{@code \U+XXXX} → Unicode 字符</li>
 *   <li>花括号格式块（{@code &#123;\cmd;content&#125;}）→ 保留 content，剥离 cmd</li>
 *   <li>换行控制码：{@code \P} / {@code \p} → {@code \n}</li>
 *   <li>不换行空格：{@code \~} → 空格</li>
 *   <li>特殊符号替换：{@code %%d} → °，{@code %%p} → ±，{@code %%c} → ⌀</li>
 *   <li>移除剩余单字母控制序列（如 {@code \L}、{@code \l}、{@code \O}、{@code \o}）</li>
 *   <li>还原被保护的转义字面量</li>
 * </ol>
 *
 * <p>第 1 步是为了与 {@code DXFWriter} 的 MTEXT 转义对称：写出时会把字面量
 * {@code \}、{@code &#123;}、{@code &#125;} 转义，若读侧不保护，{@code \&#123;字面括号\&#125;}
 * 会被误判为格式块从而导致文本往返丢失。
 *
 * <p>花括号处理算法基于栈：
 * <ul>
 *   <li>{@code &#123;\...;content&#125;} → 遇到 {@code &#123;\} 开始，跳过直到 {@code ;}，之后输出内容</li>
 *   <li>{@code &#123;content&#125;} → 无格式命令，内容直接输出（删除括号本身）</li>
 *   <li>嵌套花括号递归处理，不依赖正则（正则只能处理单层嵌套）</li>
 * </ul>
 */
public final class MTextCleaner {

    /** 转义字面量的私有区占位符（正常 MTEXT 文本不会出现这些码位）。 */
    private static final char ESC_BACKSLASH = '\uE000';
    private static final char ESC_LBRACE    = '\uE001';
    private static final char ESC_RBRACE    = '\uE002';

    private MTextCleaner() {}

    /**
     * 清洗 MTEXT 原始字符串。
     *
     * @param raw group code 1 的原始值（含格式控制码）
     * @return 可读的纯文本，首尾空白已去除；null 或空串返回空串
     */
    public static String clean(String raw) {
        if (raw == null || raw.isEmpty()) return "";

        String s = raw;
        s = protectEscapes(s);     // \\ \{ \} → 占位符（先于其它规则，避免被当成控制码）
        s = resolveUnicode(s);     // \U+XXXX → Unicode
        s = stripBraces(s);        // {\\cmd;content} → content
        s = s.replace("\\P", "\n") // 段落换行
             .replace("\\p", "\n")
             .replace("\\~", " "); // 不换行空格 → 普通空格
        s = replaceSymbols(s);     // %%d → °  %%p → ±  %%c → ⌀
        // 移除剩余单字母转义（\L \l \O \o \k \K 等），不带参数的格式码
        s = s.replaceAll("\\\\[A-Za-z]", "");
        s = restoreEscapes(s);
        return s.strip();
    }

    /** 把 {@code \\}、{@code \&#123;}、{@code \&#125;} 替换为私有区占位符。 */
    static String protectEscapes(String s) {
        if (s.indexOf('\\') < 0) return s;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                switch (s.charAt(i + 1)) {
                    case '\\' -> { sb.append(ESC_BACKSLASH); i++; continue; }
                    case '{'  -> { sb.append(ESC_LBRACE);    i++; continue; }
                    case '}'  -> { sb.append(ESC_RBRACE);    i++; continue; }
                    default   -> { }
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** 占位符还原为字面量 {@code \}、{@code &#123;}、{@code &#125;}。 */
    static String restoreEscapes(String s) {
        if (s.indexOf(ESC_BACKSLASH) < 0 && s.indexOf(ESC_LBRACE) < 0
                && s.indexOf(ESC_RBRACE) < 0) return s;
        return s.replace(ESC_BACKSLASH, '\\')
                .replace(ESC_LBRACE, '{')
                .replace(ESC_RBRACE, '}');
    }

    // -------------------------------------------------------------------------
    // 内部实现
    // -------------------------------------------------------------------------

    /**
     * 将 {@code \U+XXXX} 形式的 Unicode 转义替换为对应 Unicode 字符。
     * AutoCAD R2007+ 使用此机制编码非 ASCII 字符（即使文件已是 UTF-8）。
     */
    static String resolveUnicode(String s) {
        if (!s.contains("\\U+") && !s.contains("\\u+")) return s;
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            // 匹配 \U+XXXX（大小写不限）
            if (i + 6 < s.length()
                    && s.charAt(i) == '\\'
                    && Character.toUpperCase(s.charAt(i + 1)) == 'U'
                    && s.charAt(i + 2) == '+') {
                try {
                    int codePoint = Integer.parseInt(s.substring(i + 3, i + 7), 16);
                    sb.appendCodePoint(codePoint);
                    i += 7;
                } catch (NumberFormatException e) {
                    sb.append(s.charAt(i++));
                }
            } else {
                sb.append(s.charAt(i++));
            }
        }
        return sb.toString();
    }

    /**
     * 用栈处理嵌套花括号，剥离格式命令（分号之前的部分），保留内容文本。
     *
     * <p>规则：
     * <ul>
     *   <li>{@code &#123;\fArial|b0;text&#125;} → "text"（格式命令以 \ 开头）</li>
     *   <li>{@code &#123;plain text&#125;} → "plain text"（无格式命令）</li>
     *   <li>嵌套 {@code &#123;\f...;&#123;\\H2;inner&#125;&#125;} → "inner"</li>
     * </ul>
     */
    static String stripBraces(String s) {
        StringBuilder result = new StringBuilder(s.length());
        boolean skipUntilSemi = false; // 正在跳过格式命令部分（{\到;之间）

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);

            if (c == '{') {
                // 如果 { 后紧跟 \，是格式块，跳过直到 ;
                if (i + 1 < s.length() && s.charAt(i + 1) == '\\') {
                    skipUntilSemi = true;
                }
                // { 本身不输出
            } else if (c == '}') {
                // } 本身不输出，也不影响 skipUntilSemi（; 已经关闭了它）
            } else if (skipUntilSemi && c == ';') {
                skipUntilSemi = false; // 格式命令结束，后续内容正常输出
            } else if (!skipUntilSemi) {
                result.append(c);
            }
        }
        return result.toString();
    }

    /** 替换 AutoCAD 特殊符号速记。 */
    static String replaceSymbols(String s) {
        return s.replace("%%d", "°").replace("%%D", "°")
                .replace("%%p", "±").replace("%%P", "±")
                .replace("%%c", "⌀").replace("%%C", "⌀");
    }
}
