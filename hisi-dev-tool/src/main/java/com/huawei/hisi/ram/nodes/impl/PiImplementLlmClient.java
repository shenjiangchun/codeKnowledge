package com.huawei.hisi.ram.nodes.impl;

import com.huawei.hisi.agentbase.AgentRegistry;
import com.huawei.hisi.agentbase.ReActSession;
import com.huawei.hisi.ram.nodes.ImplementLlmClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 实现方案阶段的 pi 实现：声明 {@code ram-design}（只读工具 + skill + implement.output 契约，不合契约自动修正）。
 * 提示词与归一化沿用父类，只换执行方式。
 */
@Slf4j
@Primary
@Component
public class PiImplementLlmClient extends ClaudeImplementLlmClient implements ImplementLlmClient {

    private final ReActSession session;

    public PiImplementLlmClient(ReActSession session, StubImplementLlmClient fallback) {
        super(fallback);
        this.session = session;
    }

    @Override
    public Map<String, Object> draft(Map<String, Object> impactOutput,
                                     List<String> acceptanceCriteria,
                                     String model) {
        log.info("[RAM][PiImplementLlmClient] draft impact.keys={} acs={}",
                impactOutput == null ? "null" : impactOutput.keySet(),
                acceptanceCriteria == null ? 0 : acceptanceCriteria.size());
        String userPrompt = buildUserPrompt(impactOutput, acceptanceCriteria);
        try {
            ReActSession.Result result = session.run(
                    AgentRegistry.agent("ram-design").orElseThrow(),
                    "ram-design-" + UUID.randomUUID().toString().substring(0, 12),
                    workdir(impactOutput), SYSTEM_PROMPT, userPrompt, null, () -> false, null);
            if (result.json() == null) {
                log.error("[RAM][PiImplementLlmClient] pi 未返回结果：{} — 回退桩实现", result.note());
                return fallback.draft(impactOutput, acceptanceCriteria, model);
            }
            return normalize(result.json(), impactOutput, acceptanceCriteria, model);
        } catch (Exception ex) {
            log.error("[RAM][PiImplementLlmClient] 会话执行失败 — 回退桩实现: {}", ex.toString(), ex);
            return fallback.draft(impactOutput, acceptanceCriteria, model);
        }
    }

    /** 会话工作目录：影响面里的项目路径；没有就退到进程目录。 */
    static Path workdir(Map<String, Object> impactOutput) {
        Object paths = impactOutput == null ? null : impactOutput.get("project_paths");
        if (paths instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof String first) {
            Path path = Path.of(first);
            if (Files.isDirectory(path)) {
                return path;
            }
        }
        return Path.of(System.getProperty("user.dir"));
    }
}
