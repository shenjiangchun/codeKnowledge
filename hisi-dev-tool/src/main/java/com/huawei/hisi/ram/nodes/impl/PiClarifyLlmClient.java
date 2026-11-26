package com.huawei.hisi.ram.nodes.impl;

import com.huawei.hisi.agentbase.AgentRegistry;
import com.huawei.hisi.agentbase.ReActSession;
import com.huawei.hisi.ram.nodes.ClarifyLlmClient;
import com.huawei.hisi.ram.nodes.CodeContextItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 澄清阶段的 pi 实现：会话交给统一运行时（声明 {@code ram-clarify}——只读工具清单 + skill + clarify.output 契约，
 * 不合契约自动修正），提示词与结果归一化沿用父类（同一份业务逻辑，只换执行方式）。
 */
@Slf4j
@Primary
@Component
public class PiClarifyLlmClient extends ClaudeClarifyLlmClient implements ClarifyLlmClient {

    private final ReActSession session;

    public PiClarifyLlmClient(ReActSession session, StubClarifyLlmClient fallback) {
        super(fallback);
        this.session = session;
    }

    @Override
    public Map<String, Object> extractRequirements(String userRequest, Map<String, Object> hints) {
        return extractRequirements(userRequest, hints, List.of());
    }

    @Override
    public Map<String, Object> extractRequirements(String userRequest,
                                                   Map<String, Object> hints,
                                                   List<CodeContextItem> codeContext) {
        List<String> hintPaths = extractProjectPaths(hints);
        log.info("[RAM][PiClarifyLlmClient] extractRequirements userRequest.len={} hintPaths={}",
                userRequest == null ? 0 : userRequest.length(), hintPaths);

        boolean hasClarifyHistory = hints != null
                && hints.get("clarify_history") instanceof List<?> histList && !histList.isEmpty();
        boolean useTools = !hintPaths.isEmpty() && !hasClarifyHistory;
        String systemPrompt;
        if (useTools) {
            systemPrompt = SYSTEM_PROMPT_WITH_TOOLS;
        } else if (hasClarifyHistory && !hintPaths.isEmpty()) {
            systemPrompt = SYSTEM_PROMPT_WITH_HISTORY;
        } else if (codeContext != null && !codeContext.isEmpty()) {
            systemPrompt = SYSTEM_PROMPT_WITH_CODE_CONTEXT;
        } else {
            systemPrompt = SYSTEM_PROMPT;
        }
        String userPrompt = buildUserPrompt(userRequest, hintPaths, hints, codeContext);
        // 会话的工作目录：pi 的只读工具按它定位项目（与被分析项目一致，只读）
        Path workdir = workdir(hintPaths);
        try {
            ReActSession.Result result = session.run(
                    AgentRegistry.agent("ram-clarify").orElseThrow(),
                    "ram-clarify-" + UUID.randomUUID().toString().substring(0, 12),
                    workdir, systemPrompt, userPrompt, null, () -> false, null);
            if (result.json() == null) {
                log.error("[RAM][PiClarifyLlmClient] pi 未返回结果：{} — 回退桩实现", result.note());
                return fallback.extractRequirements(userRequest, hints);
            }
            log.info("[RAM][PiClarifyLlmClient] returned keys={} needs_clarification={}",
                    result.json().keySet(), result.json().get("needs_clarification"));
            return normalize(result.json(), userRequest, hintPaths, hints);
        } catch (Exception ex) {
            log.error("[RAM][PiClarifyLlmClient] 会话执行失败 — 回退桩实现: {}", ex.toString(), ex);
            return fallback.extractRequirements(userRequest, hints);
        }
    }

    /** 会话工作目录：第一个项目路径；没有就退到进程目录（一次性判断，不影响工具定位）。 */
    static Path workdir(List<String> hintPaths) {
        if (hintPaths == null || hintPaths.isEmpty()) {
            return Path.of(System.getProperty("user.dir"));
        }
        Path path = Path.of(hintPaths.get(0));
        return Files.isDirectory(path) ? path : Path.of(System.getProperty("user.dir"));
    }
}
