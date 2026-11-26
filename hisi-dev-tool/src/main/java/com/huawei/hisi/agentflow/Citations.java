package com.huawei.hisi.agentflow;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 从模型输出里提取 {@code [[E n]]} 形式的证据引用。 */
public final class Citations {

    /** 引用写成 [[E1]]、[[E 12]]、[[ E3 ]] 均可识别；模型输出里的空白不必严格。 */
    private static final Pattern CITATION = Pattern.compile("\\[\\[\\s*E\\s*(\\d{1,3})\\s*]]");

    /** 模型引用了证据包里不存在的编号时，替换成的标记。 */
    public static final String UNVERIFIED_MARK = "[[未验证]]";

    private Citations() {
    }

    /** 按出现顺序返回去重后的证据编号，如 [E3, E1]。 */
    public static List<String> extract(String... texts) {
        Set<String> ids = new LinkedHashSet<>();
        if (texts == null) {
            return List.of();
        }
        for (String text : texts) {
            if (text == null || text.isEmpty()) {
                continue;
            }
            Matcher m = CITATION.matcher(text);
            while (m.find()) {
                ids.add("E" + Integer.parseInt(m.group(1)));
            }
        }
        return new ArrayList<>(ids);
    }

    /** 把其中不在证据包内的引用替换为 {@link #UNVERIFIED_MARK}，其余内容保持不变。 */
    public static String markUnknown(String text, Predicate<String> known) {
        if (text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }
        Matcher m = CITATION.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String replacement = known.test("E" + Integer.parseInt(m.group(1))) ? m.group() : UNVERIFIED_MARK;
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 去掉所有引用标记（含未验证标记），得到纯文本。 */
    public static String strip(String text) {
        if (text == null) {
            return "";
        }
        return CITATION.matcher(text).replaceAll("").replace(UNVERIFIED_MARK, "");
    }

    /** 未验证标记在成文里的写法，与证据清单里「编号 E1」的写法一致，便于阅读。 */
    public static final String UNVERIFIED_DISPLAY = "[未验证引用]";

    /**
     * 把引用标记转成给人看的写法：{@code [[E3]]} → {@code [E3]}，{@code [[未验证]]} → {@code [未验证引用]}。
     * 校验已经在转写之前做完，这里只影响最终呈现。
     */
    public static String toDisplay(String text) {
        if (text == null) {
            return "";
        }
        return CITATION.matcher(text).replaceAll("[E$1]").replace(UNVERIFIED_MARK, UNVERIFIED_DISPLAY);
    }
}
