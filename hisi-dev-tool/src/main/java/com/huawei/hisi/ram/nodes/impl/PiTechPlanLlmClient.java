package com.huawei.hisi.ram.nodes.impl;

import com.huawei.hisi.agentbase.AgentRegistry;
import com.huawei.hisi.agentbase.ReActSession;
import com.huawei.hisi.ram.nodes.TechPlanLlmClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 技术方案阶段的 pi 实现：声明 {@code ram-techplan}（只读工具 + skill + tech_plan.output 契约，
 * 不合契约自动修正）。提示词与归一化沿用父类，只换执行方式。
 */
@Slf4j
@Primary
@Component
public class PiTechPlanLlmClient extends ClaudeTechPlanLlmClient implements TechPlanLlmClient {

    private final ReActSession session;

    public PiTechPlanLlmClient(ReActSession session) {
        this.session = session;
    }

    @Override
    public boolean isAvailable() {
        return true; // 会话层未就绪时由调用方走降级
    }

    @Override
    public Map<String, Object> generate(Map<String, Object> impactOutput,
                                        Map<String, Object> implementOutput,
                                        String intent,
                                        String projectPath) {
        log.info("[RAM][PiTechPlanLlmClient] generate intent={} projectPath={}", intent, projectPath);
        String userPrompt = buildUserPrompt(impactOutput, implementOutput, intent);
        try {
            ReActSession.Result result = session.run(
                    AgentRegistry.agent("ram-techplan").orElseThrow(),
                    "ram-techplan-" + UUID.randomUUID().toString().substring(0, 12),
                    workdir(projectPath), SYSTEM_PROMPT, userPrompt, null, () -> false, null);
            if (result.json() == null) {
                log.error("[RAM][PiTechPlanLlmClient] pi 未返回结果：{}", result.note());
                return minimalOutput(intent);
            }
            return normalize(result.json(), List.of());
        } catch (Exception ex) {
            log.error("[RAM][PiTechPlanLlmClient] 会话执行失败: {}", ex.getMessage(), ex);
            return minimalOutput(intent);
        }
    }

    static Path workdir(String projectPath) {
        if (projectPath != null && !projectPath.isBlank()) {
            Path path = Path.of(projectPath);
            if (Files.isDirectory(path)) {
                return path;
            }
        }
        return Path.of(System.getProperty("user.dir"));
    }
}
