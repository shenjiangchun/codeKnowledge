package com.huawei.hisi.knowledgegraph.aggregation.stage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LLM 分层 SYSTEM_PROMPT 约束单测：按类自身职责判断，DTO/record 等数据对象不因所在 service 包归 SERVICE。
 */
class LayerRoleLlmServicePromptTest {

    @Test
    @DisplayName("SYSTEM_PROMPT 包含按自身职责判断约束，DTO 不因 service 包误判")
    void systemPromptContainsSelfResponsibilityConstraint() {
        String prompt = LayerRoleLlmService.SYSTEM_PROMPT;
        assertThat(prompt).contains("按类自身职责判断");
        assertThat(prompt).contains("DTO");
        assertThat(prompt).contains("不因所在 service 包归 SERVICE");
    }
}
