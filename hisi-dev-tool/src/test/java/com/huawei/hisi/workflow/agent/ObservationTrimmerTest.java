package com.huawei.hisi.workflow.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 观察截断器单测：短文本原样序列化返回，超长截断；null 与序列化失败兜底。
 */
class ObservationTrimmerTest {

    private final ObservationTrimmer trimmer = new ObservationTrimmer();

    @Test
    @DisplayName("短观察：原样 JSON 序列化返回，不截断")
    void shortObservationUntouched() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("healthScore", 72);
        data.put("grade", "C");
        assertThat(trimmer.trim(data, 2000)).isEqualTo("{\"healthScore\":72,\"grade\":\"C\"}");
    }

    @Test
    @DisplayName("长观察：超 maxChars 截断并追加标记")
    void longObservationTruncated() {
        String json = trimmer.trim(Map.of("items", List.of("a".repeat(3000))), 100);
        assertThat(json).hasSize(105);
        assertThat(json).endsWith("…(截断)");
    }

    @Test
    @DisplayName("null：返回空串")
    void nullObservation() {
        assertThat(trimmer.trim(null, 2000)).isEmpty();
    }
}
