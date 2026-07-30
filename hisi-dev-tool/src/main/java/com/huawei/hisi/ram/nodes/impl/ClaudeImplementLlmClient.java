package com.huawei.hisi.ram.nodes.impl;

import com.huawei.hisi.ram.nodes.ImplementLlmClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ChatClient-backed {@link ImplementLlmClient} — replaces raw
 * {@link RamClaudeJsonClient} with Spring AI {@link ChatClient}.
 */
@Slf4j
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
            - biz_plan.acceptance_mapping: 每个AC映射到覆盖它的步骤编号
            - api_changes: 每个受影响的API端点，必须包含current和new behavior对比
            - state_machine_changes: 枚举/状态值变更；无则为空数组
            - data_model_changes: 实体/字段变更；无则为空数组
            - config_changes: 配置项变更；无则为空数组
            - 所有自然语言值使用简体中文
            - JSON key、文件路径、类名/方法名、HTTP路由保持原样
            """;

    private final ChatClient agentChatClient;
    private final StubImplementLlmClient fallback;

    public ClaudeImplementLlmClient(ChatClient agentChatClient,
                                     StubImplementLlmClient fallback) {
        this.agentChatClient = agentChatClient;
        this.fallback = fallback;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> draft(Map<String, Object> impactOutput,
                                     List<String> acceptanceCriteria,
                                     String model) {
        log.info("[RAM][ClaudeImplementLlmClient] draft impact.keys={} acs={}",
                impactOutput == null ? "null" : impactOutput.keySet(),
                acceptanceCriteria == null ? 0 : acceptanceCriteria.size());

        String prompt = buildUserPrompt(impactOutput, acceptanceCriteria);
        try {
            Map<String, Object> raw = agentChatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(prompt)
                    .call()
                    .entity(new ParameterizedTypeReference<Map<String, Object>>() {});

            log.info("[RAM][ClaudeImplementLlmClient] returned keys={}",
                    raw == null ? "null" : raw.keySet());
            return normalize(raw, impactOutput, acceptanceCriteria, model);
        } catch (Exception ex) {
            log.error("[RAM][ClaudeImplementLlmClient] call FAILED: {}", ex.toString(), ex);
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
        Map<String, Object> biz = asMap(raw.get("biz_plan"));
        Map<String, Object> normBiz = new LinkedHashMap<>();
        normBiz.put("steps", asList(biz.get("steps")));
        normBiz.put("data_flow", biz.get("data_flow") instanceof String s ? s : "");
        normBiz.put("acceptance_mapping",
                biz.get("acceptance_mapping") instanceof Map m ? m : new LinkedHashMap<>());
        out.put("biz_plan", normBiz);
        out.put("api_changes", asList(raw.get("api_changes")));
        out.put("state_machine_changes", asList(raw.get("state_machine_changes")));
        out.put("data_model_changes", asList(raw.get("data_model_changes")));
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
