package com.huawei.hisi.agent.tools;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * grep 工具的行匹配：模式是合法正则时按正则找，否则按字面量找。
 *
 * <p>工具说明写的是「text/regex matches」，模型也确实会传 {@code skill-market|createSkill} 这样的正则；
 * 以前一律按字面量匹配，这种模式永远 0 条命中，模型会被「刚才还能搜到、现在搜不到」绕进去。
 */
public final class GrepMatcher {

    private GrepMatcher() {
    }

    public static boolean matches(String line, String pattern, boolean caseSensitive) {
        if (line == null || pattern == null || pattern.isEmpty()) {
            return false;
        }
        Pattern regex = compile(pattern, caseSensitive);
        if (regex != null) {
            return regex.matcher(line).find();
        }
        return caseSensitive ? line.contains(pattern) : line.toLowerCase().contains(pattern.toLowerCase());
    }

    /** 合法正则返回编译结果，非法的（比如没配对的括号）返回 null，调用方按字面量处理。 */
    static Pattern compile(String pattern, boolean caseSensitive) {
        try {
            return Pattern.compile(pattern, caseSensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        } catch (PatternSyntaxException e) {
            return null;
        }
    }
}
