package com.huawei.hisi.ram.nodes.impl;

import com.huawei.hisi.ram.nodes.TechPlanLlmClient;
import com.huawei.hisi.ram.sdk.SendOptions;
import com.huawei.hisi.ram.sdk.ToolDefinition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Claude-backed {@link TechPlanLlmClient} that uses tool-enhanced analysis
 * (8 tools from {@link KgToolRegistry}: 5 KG + 3 FS) to produce a
 * complete technical plan with method specs, diagrams, test scope, and risks.
 */
@Slf4j
@Component
public class ClaudeTechPlanLlmClient implements TechPlanLlmClient {

    private static final String SYSTEM_PROMPT = """
            你是一名资深技术架构师，负责根据影响分析和实现方案，输出完整的技术方案文档。
            你可以使用提供的工具来深入分析代码结构、调用链、配置等，确保技术方案的准确性和完整性。

            你必须返回一个 JSON 对象（不要 prose、不要 markdown fences），结构如下：

            {
              "target_methods_detail": [
                {
                  "method": "RequireStatusServiceImpl#syncReqStatus",
                  "file": "com/hisilicon/rms/service/impl/RequireStatusServiceImpl.java",
                  "lines": "120-185",
                  "current_logic": "当前逻辑描述",
                  "change_spec": "需要修改的规格说明",
                  "pseudocode": "if (downstream > upstream) { setStatus(downstream) }"
                }
              ],
              "sequence_diagrams": [
                {
                  "name": "需求状态回卷时序",
                  "mermaid": "sequenceDiagram\\n  participant Scheduler\\n  ..."
                }
              ],
              "flow_diagrams": [
                {
                  "name": "需求状态变更决策流程",
                  "mermaid": "flowchart TD\\n  A[有子项?] -->|是| B[看子项卷积]..."
                }
              ],
              "test_scope": {
                "unit_tests": ["方法名_场景_预期结果"],
                "integration_tests": ["功能场景_预期结果"],
                "data_migration": ["UPDATE ..."]
              },
              "risk_mitigations": [
                {
                  "risk": "风险描述",
                  "mitigation": "缓解措施"
                }
              ],
              "reasoning": "分析推理过程",
              "markdown_report": "格式化的技术方案报告"
            }

            核心原则：
            1. 使用工具深入分析代码，不要猜测
            2. target_methods_detail 每个方法必须包含 current_logic 和 change_spec
            3. pseudocode 使用 Java-like 伪代码
            4. sequence_diagrams 和 flow_diagrams 使用 Mermaid 语法
            5. test_scope 包含单元测试、集成测试、数据迁移三类
            6. risk_mitigations 每个风险必须有对应的缓解措施
            7. 所有自然语言值使用简体中文
            8. JSON key、文件路径、类名/方法名保持原样
            """;

    private final RamClaudeJsonClient claude;
    private final KgToolRegistry kgToolRegistry;

    public ClaudeTechPlanLlmClient(RamClaudeJsonClient claude, KgToolRegistry kgToolRegistry) {
        this.claude = claude;
        this.kgToolRegistry = kgToolRegistry;
    }

    @Override
    public Map<String, Object> generate(Map<String, Object> impactOutput,
                                         Map<String, Object> implementOutput,
                                         String intent,
                                         String projectPath) {
        log.info("[RAM][ClaudeTechPlanLlmClient] generate intent={} projectPath={}", intent, projectPath);

        if (!claude.isAvailable()) {
            log.warn("[RAM][ClaudeTechPlanLlmClient] Claude unavailable — returning minimal output");
            return minimalOutput(intent);
        }

        String userPrompt = buildUserPrompt(impactOutput, implementOutput, intent);

        try {
            List<ToolDefinition> tools = kgToolRegistry.buildToolDefinitions(projectPath);
            Map<String, Function<Map<String, Object>, Object>> handlers =
                    kgToolRegistry.buildToolHandlers(projectPath);

            SendOptions opts = new SendOptions(claude.defaultModel(), 8192, 0.2, SYSTEM_PROMPT);

            RamClaudeJsonClient.JsonCallResult result =
                    claude.callJsonWithToolsAndReasoning(SYSTEM_PROMPT, userPrompt, tools, handlers, opts);

            Map<String, Object> raw = result.json();
            log.info("[RAM][ClaudeTechPlanLlmClient] Claude returned keys={}, tool rounds={}",
                    raw == null ? "null" : raw.keySet(),
                    result.reasoning().size());

            return normalize(raw, result.reasoning());
        } catch (Exception ex) {
            log.error("[RAM][ClaudeTechPlanLlmClient] Claude call FAILED: {}", ex.getMessage(), ex);
            return minimalOutput(intent);
        }
    }

    @Override
    public boolean isAvailable() {
        return claude.isAvailable();
    }

    private String buildUserPrompt(Map<String, Object> impactOutput,
                                   Map<String, Object> implementOutput,
                                   String intent) {
        log.info("[RAM][ClaudeTechPlanLlmClient] buildUserPrompt impact={} implement={}",
                impactOutput != null ? ("keys=" + impactOutput.keySet()) : "null",
                implementOutput != null ? ("keys=" + implementOutput.keySet()) : "null");
        StringBuilder sb = new StringBuilder();
        sb.append("## 需求描述\n").append(intent != null ? intent : "").append("\n\n");
        sb.append("## 影响分析结果\n");
        sb.append(impactOutput == null ? "（无影响分析数据）" : formatMap(impactOutput)).append("\n\n");
        sb.append("## 实现方案\n");
        sb.append(implementOutput == null ? "（无实现方案数据）" : formatMap(implementOutput)).append("\n\n");
        sb.append("\n请使用工具深入分析代码，然后返回完整的技术方案 JSON。");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> normalize(Map<String, Object> raw, List<String> reasoning) {
        if (raw == null) return minimalOutput("");

        Map<String, Object> out = new LinkedHashMap<>();

        out.put("target_methods_detail", asList(raw.get("target_methods_detail")));
        out.put("sequence_diagrams", asList(raw.get("sequence_diagrams")));
        out.put("flow_diagrams", asList(raw.get("flow_diagrams")));

        // test_scope
        if (raw.get("test_scope") instanceof Map<?, ?> ts) {
            Map<String, Object> normTs = new LinkedHashMap<>();
            normTs.put("unit_tests", asList(ts.get("unit_tests")));
            normTs.put("integration_tests", asList(ts.get("integration_tests")));
            normTs.put("data_migration", asList(ts.get("data_migration")));
            out.put("test_scope", normTs);
        } else {
            out.put("test_scope", Map.of(
                    "unit_tests", List.of(),
                    "integration_tests", List.of(),
                    "data_migration", List.of()));
        }

        out.put("risk_mitigations", asList(raw.get("risk_mitigations")));
        out.put("reasoning", raw.get("reasoning") instanceof String s ? s : String.join("\n", reasoning));
        out.put("markdown_report", raw.get("markdown_report") instanceof String s ? s : "");

        return out;
    }

    private Map<String, Object> minimalOutput(String intent) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("target_methods_detail", List.of());
        out.put("sequence_diagrams", List.of());
        out.put("flow_diagrams", List.of());
        out.put("test_scope", Map.of(
                "unit_tests", List.of(),
                "integration_tests", List.of(),
                "data_migration", List.of()));
        out.put("risk_mitigations", List.of());
        out.put("reasoning", "Claude不可用，无法生成技术方案");
        out.put("markdown_report", "");
        return out;
    }

    private List<Object> asList(Object o) {
        if (o instanceof List<?> l) return List.copyOf(l);
        return List.of();
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    private String formatMap(Map<String, Object> map) {
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(map);
        } catch (Exception e) {
            return map.toString();
        }
    }
}
