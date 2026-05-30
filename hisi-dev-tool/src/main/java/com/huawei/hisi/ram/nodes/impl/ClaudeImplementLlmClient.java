package com.huawei.hisi.ram.nodes.impl;

import com.huawei.hisi.ram.nodes.ImplementLlmClient;
import com.huawei.hisi.ram.sdk.SendOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Real Claude-backed {@link ImplementLlmClient}. Active only when
 * {@code anthropic.api-key} is set; otherwise {@link StubImplementLlmClient}
 * remains the only candidate.
 */
@Slf4j
@Primary
@Component
public class ClaudeImplementLlmClient implements ImplementLlmClient {

    private static final String SYSTEM_PROMPT = """
            你是一名资深技术架构师，负责根据影响分析结果和验收标准，编写"需求实现方案"。

            你必须返回一个 JSON 对象（不要 prose、不要 markdown fences），结构如下：

            {
              "biz_plan": {
                "steps": ["<有序实现步骤>", ...],
                "data_flow": "<一段话描述数据流向>",
                "acceptance_mapping": {
                  "AC1": ["Step1", "Step2"],
                  "AC2": ["Step3"]
                }
              },
              "api_changes": [
                {
                  "endpoint": "POST /api/req/deliver",
                  "current_behavior": "当前行为描述",
                  "new_behavior": "修改后行为描述",
                  "method_ref": "ReqController#deliver"
                }
              ],
              "state_machine_changes": [
                {
                  "enum_type": "ReqStatus",
                  "old_values": ["初始","设计","已发行"],
                  "new_values": ["初始","设计","开发","测试"],
                  "migration_note": "存量'已发行'→'设计'，历史快照不处理"
                }
              ],
              "data_model_changes": [
                {
                  "entity": "Requirement",
                  "field": "status",
                  "change_type": "ENUM_UPDATE",
                  "detail": "枚举值替换"
                }
              ],
              "config_changes": [
                {
                  "key": "req.status.flow.initial-transition",
                  "old_value": "初始→已发行",
                  "new_value": "初始→设计"
                }
              ]
            }

            规则：
            - biz_plan.steps: 3-7个具体的、有序的实现步骤，覆盖所有AC
            - biz_plan.data_flow: 一段话描述数据流向和关键角色
            - biz_plan.acceptance_mapping: 每个AC映射到覆盖它的步骤编号（步骤引用steps中的条目子串）
            - api_changes: 每个受影响的API端点，必须包含current_behavior和new_behavior的对比
            - state_machine_changes: 枚举/状态值变更，包含旧值→新值对比和迁移说明；无状态变更则为空数组
            - data_model_changes: 实体/字段变更（类型、约束、枚举更新等）；无则为空数组
            - config_changes: 配置项变更（properties/yml/常量）；无则为空数组
            - 不要输出 tech_plan 或 ui_plan，这两个结构已废弃
            - 所有自然语言值使用简体中文
            - JSON key、文件路径、类名/方法名、HTTP路由、SQL/列名保持原样
            """;

    private final RamClaudeJsonClient claude;
    private final StubImplementLlmClient fallback;

    public ClaudeImplementLlmClient(RamClaudeJsonClient claude, StubImplementLlmClient fallback) {
        this.claude = claude;
        this.fallback = fallback;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> draft(Map<String, Object> impactOutput,
                                     List<String> acceptanceCriteria,
                                     String model) {
        log.info("[RAM][ClaudeImplementLlmClient] draft impact.keys={} acs={} model={}",
                impactOutput == null ? "null" : impactOutput.keySet(),
                acceptanceCriteria == null ? 0 : acceptanceCriteria.size(),
                model);

        if (!claude.isAvailable()) {
            log.error("[RAM][ClaudeImplementLlmClient] Claude UNAVAILABLE (anthropic.api-key empty) — falling back to Stub. THIS IS WHY OUTPUT IS POOR.");
            return fallback.draft(impactOutput, acceptanceCriteria, model);
        }

        String prompt = buildUserPrompt(impactOutput, acceptanceCriteria);
        String effectiveModel = (model == null || model.isBlank()) ? claude.defaultModel() : model;
        try {
            Map<String, Object> raw = claude.callJson(
                    SYSTEM_PROMPT, prompt,
                    new SendOptions(effectiveModel, 4096, 0.3, null));
            log.info("[RAM][ClaudeImplementLlmClient] Claude returned keys={}",
                    raw == null ? "null" : raw.keySet());
            return normalize(raw, impactOutput, acceptanceCriteria, model);
        } catch (Exception ex) {
            log.error("[RAM][ClaudeImplementLlmClient] Claude call FAILED — falling back to Stub. err={}", ex.toString(), ex);
            return fallback.draft(impactOutput, acceptanceCriteria, model);
        }
    }

    private String buildUserPrompt(Map<String, Object> impactOutput, List<String> acs) {
        StringBuilder sb = new StringBuilder();
        sb.append("Impact analysis output:\n");
        sb.append(impactOutput == null ? "{}" : impactOutput.toString()).append("\n\n");
        sb.append("Acceptance criteria:\n");
        if (acs == null || acs.isEmpty()) {
            sb.append("(none provided)\n");
        } else {
            for (String c : acs) sb.append("- ").append(c).append("\n");
        }
        sb.append("\nReturn the JSON object now.");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> normalize(Map<String, Object> raw,
                                          Map<String, Object> impactOutput,
                                          List<String> acs,
                                          String model) {
        if (raw == null) {
            return fallback.draft(impactOutput, acs, model);
        }
        Map<String, Object> out = new LinkedHashMap<>();

        // biz_plan (required)
        Map<String, Object> biz = asMap(raw.get("biz_plan"));
        Map<String, Object> normBiz = new LinkedHashMap<>();
        normBiz.put("steps", asList(biz.get("steps")));
        normBiz.put("data_flow", biz.get("data_flow") instanceof String s ? s : "");
        normBiz.put("acceptance_mapping", biz.get("acceptance_mapping") instanceof Map m ? m : new LinkedHashMap<>());
        out.put("biz_plan", normBiz);

        // api_changes (required)
        out.put("api_changes", asList(raw.get("api_changes")));

        // state_machine_changes (optional, default empty)
        out.put("state_machine_changes", asList(raw.get("state_machine_changes")));

        // data_model_changes (optional, default empty)
        out.put("data_model_changes", asList(raw.get("data_model_changes")));

        // config_changes (optional, default empty)
        out.put("config_changes", asList(raw.get("config_changes")));

        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<>();
    }

    private List<Object> asList(Object o) {
        return o instanceof List<?> l ? List.copyOf(l) : List.of();
    }
}
