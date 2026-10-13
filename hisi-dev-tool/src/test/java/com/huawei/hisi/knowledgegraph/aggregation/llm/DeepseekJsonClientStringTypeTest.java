package com.huawei.hisi.knowledgegraph.aggregation.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 回归：chatJson 以 {@code String.class} 为反序列化目标时，应原样返回 LLM 的 JSON 文本，
 * 而不是把它当 JSON 对象反序列化成 String（历史缺陷：ArchRootCauseNode / ArchStatusReportNode
 * 因此拿到 null → 根因分析误降级）。
 */
class DeepseekJsonClientStringTypeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String RAW_JSON = "{\"issues\":[{\"ref\":\"i-1\",\"title\":\"循环依赖\",\"severity\":\"HIGH\"}]}";

    @Test
    void chatJsonWithStringTypeReturnsRawJsonText() throws Exception {
        DeepseekJsonClient client = new DeepseekJsonClient(
                "http://gateway.example", "key", "deepseek-test", 10_000, 300_000);
        RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(client, "restTemplate");
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();

        // 网关返回 OpenAI 兼容体，content 是 LLM 产出的 JSON 对象文本
        ObjectNode root = MAPPER.createObjectNode();
        root.putArray("choices").addObject().putObject("message").put("content", RAW_JSON);
        String body = MAPPER.writeValueAsString(root);

        server.expect(requestTo("http://gateway.example/v1/chat/completions"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        String result = client.chatJson("sys", "请输出 json", String.class);

        assertThat(result).isEqualTo(RAW_JSON);
        server.verify();
    }
}
