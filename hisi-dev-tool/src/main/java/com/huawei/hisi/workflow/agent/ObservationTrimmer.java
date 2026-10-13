package com.huawei.hisi.workflow.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 工具观察结果截断器：把任意对象序列化为 JSON，超 {@code maxChars} 时截断，
 * 防止 ego-net / 下游调用树等大对象撑爆 ReAct 上下文。
 */
@Slf4j
@Component
public class ObservationTrimmer {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 序列化并截断；null 返回空串，序列化失败回退为 String.valueOf。 */
    public String trim(Object observation, int maxChars) {
        if (observation == null) {
            return "";
        }
        String json;
        try {
            json = objectMapper.writeValueAsString(observation);
        } catch (Exception e) {
            log.debug("[ObservationTrimmer] serialize failed: {}", e.getMessage());
            json = String.valueOf(observation);
        }
        if (json.length() <= maxChars) {
            return json;
        }
        return json.substring(0, maxChars) + "…(截断)";
    }
}
