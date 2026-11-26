package com.huawei.hisi.piagent.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.huawei.hisi.piagent.PiAgentProperties;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
/**
 * 上下文预算——所有流提交给 pi 的提示词在发出前都先过这里，与具体 agent 无关。
 *
 * <p>pi 自带的会话压缩解决的是「会话越跑越大」：上下文达到「窗口 − 保留量」时把较早的轮次总结掉，
 * 溢出时压缩后重试。但它不会动用户消息本身——一条就占掉大半窗口的首条消息没有更早的轮次可裁，
 * pi 只能把输出预算压到 1 个 token，模型什么结论都写不出来。
 *
 * <p>所以底座在提交前做预算检查：估算 system + user 的 token；超出预算时对提示词里的结构化载荷做
 * <em>保形压缩</em>——结构原样保留，只截短过长的列表与字段，并标出还有多少条没带上；
 * 完整原文落盘到会话目录，agent 需要细节时可以用 read 工具分段读取。
 * 预算按「越靠近根的列表越重要」分配，放不下就整体按比例缩小，直到放得下。
 * 两层机制合起来才是完整的上下文管理：这一层管输入，pi 那一层管运行过程中长出来的部分。
 */
@Slf4j
public final class PromptBudget {

    /** 预算再紧也给每个列表留这么多字符，低于它就不再缩（至少放得下一条）。 */
    static final int MIN_LIST_BUDGET = 400;
    /** 给「原文已落盘」的提示留的字符数。 */
    private static final int NOTICE_RESERVE = 600;
    /** 估算 token 的「字符 / token」：中文与 JSON 混排实测约 3.4（41 万字符的证据包约 11.6 万 token），取 3 偏保守。 */
    static final double CHARS_PER_TOKEN = 3.0;
    /** 长列表的起始预算（字符），放不下时逐步整体缩小。 */
    static final int LIST_BUDGET_CHARS = 8000;
    /** 单个字符串字段最多保留的字符数。 */
    static final int STRING_BUDGET_CHARS = 400;
    static final String NOTICE_FIELD = "_contextNotice";

    /**
     * 一次预算检查的结果。{@code compacted} 为真表示提示词被改动过；
     * {@code structured} 为真表示是保形压缩，为假表示只能保留开头。
     */
    public record Report(boolean compacted, boolean structured, int beforeChars, int afterChars,
                         int estimatedTokensBefore, int estimatedTokensAfter, int listBudgetChars,
                         int truncatedLists, Path fullPromptFile) {
    }

    public record Fitted(String systemPrompt, String userPrompt, Report report) {
    }

    private final PiAgentProperties.Context config;

    public PromptBudget(PiAgentProperties.Context config) {
        this.config = config;
    }

    /** 提交前调用：超预算就压缩，完整原文落盘到 {@code <sessionDir>/context/}。 */
    public Fitted fit(Path sessionDir, String systemPrompt, String userPrompt) {
        String system = systemPrompt == null ? "" : systemPrompt;
        String user = userPrompt == null ? "" : userPrompt;
        int beforeTokens = estimateTokens(system, user);
        if (beforeTokens <= config.getMaxInputTokens()) {
            return new Fitted(systemPrompt, userPrompt,
                    new Report(false, true, user.length(), user.length(), beforeTokens, beforeTokens, 0, 0, null));
        }

        Optional<JsonPayload> payload = JsonPayload.locate(user);
        if (payload.isEmpty()) {
            return keepHead(sessionDir, system, systemPrompt, user, beforeTokens);
        }
        JsonPayload found = payload.get();
        int listBudget = LIST_BUDGET_CHARS;
        for (;;) {
            JsonCompactor.Result result = new JsonCompactor(listBudget, STRING_BUDGET_CHARS).compact(found.node());
            String candidate = found.replace(result.node());
            if (fitsWithNotice(system, candidate) || listBudget <= MIN_LIST_BUDGET) {
                Path file = saveFullPrompt(sessionDir, user);
                candidate = withNotice(found, result.node(), user.length(), result.truncatedLists(), file);
                return new Fitted(systemPrompt, candidate, new Report(true, true, user.length(), candidate.length(),
                        beforeTokens, estimateTokens(system, candidate), listBudget, result.truncatedLists(), file));
            }
            listBudget = Math.max(MIN_LIST_BUDGET, listBudget * 6 / 10);
        }
    }

    public int estimateTokens(String... texts) {
        int chars = 0;
        for (String text : texts) {
            chars += text == null ? 0 : text.length();
        }
        return (int) Math.ceil(chars / CHARS_PER_TOKEN);
    }

    /** 压缩后的载荷后面还要附一段「原文已落盘」的说明，判断是否放得下时给它预留字符。 */
    private boolean fitsWithNotice(String system, String candidate) {
        return estimateTokens(system, candidate, NOTICE_RESERVE) <= config.getMaxInputTokens();
    }

    private int estimateTokens(String system, String candidate, int extraChars) {
        return (int) Math.ceil((system.length() + candidate.length() + extraChars) / CHARS_PER_TOKEN);
    }

    /** 没有可保形压缩的结构：原文落盘，只保留放得下的开头。 */
    private Fitted keepHead(Path sessionDir, String system, String systemPrompt, String user, int beforeTokens) {
        Path file = saveFullPrompt(sessionDir, user);
        int room = (int) (config.getMaxInputTokens() * CHARS_PER_TOKEN) - system.length() - NOTICE_RESERVE;
        String head = room > 0 ? user.substring(0, Math.min(user.length(), room)) : "";
        String candidate = head + "\n\n【上下文预算】本次输入共 " + user.length()
                + " 字符，超出单次输入预算且没有可压缩的结构，这里只保留了开头；完整原文：" + file;
        return new Fitted(systemPrompt, candidate, new Report(true, false, user.length(), candidate.length(),
                beforeTokens, estimateTokens(system, candidate), 0, 0, file));
    }

    private String withNotice(JsonPayload payload, JsonNode compacted, int originalChars, int truncatedLists, Path file) {
        String notice = "【上下文预算】本次输入共 " + originalChars + " 字符，超出单次输入预算，其中 " + truncatedLists
                + " 个过长的列表/字段已截短（带 _truncated 标记）。完整原文已保存：" + file
                + "。结论依赖未展示的细节时，用 read 工具按该路径分段读取，不要把截断后的内容当作全部。";
        if (payload.wholeText() && compacted instanceof ObjectNode object) {
            ObjectNode withNotice = object.deepCopy();
            withNotice.put(NOTICE_FIELD, notice);
            return payload.replace(withNotice);
        }
        return payload.replace(compacted) + "\n\n" + notice;
    }

    /** 完整原文落盘（只读留档）；写不进去不阻断流程，只是提示词里不再给出路径。 */
    private Path saveFullPrompt(Path sessionDir, String userPrompt) {
        if (sessionDir == null) {
            return null;
        }
        try {
            Path dir = sessionDir.resolve("context");
            Files.createDirectories(dir);
            Path file = dir.resolve("prompt-full-" + System.currentTimeMillis() + ".txt");
            Files.writeString(file, userPrompt, StandardCharsets.UTF_8);
            return file;
        } catch (IOException e) {
            log.warn("[PiAgent] 完整提示词落盘失败，agent 将无法回看被截掉的内容: {}", e.getMessage());
            return null;
        }
    }
}
