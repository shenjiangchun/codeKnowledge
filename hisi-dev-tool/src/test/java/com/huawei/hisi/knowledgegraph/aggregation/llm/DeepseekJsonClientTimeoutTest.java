package com.huawei.hisi.knowledgegraph.aggregation.llm;

import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * B4 回归：DeepseekJsonClient 必须带超时。
 *
 * <p>历史缺陷：裸 new RestTemplate() 无超时，一次网络挂起让串行队列任务无限期 RUNNING。
 */
class DeepseekJsonClientTimeoutTest {

    @Test
    void restTemplateShouldHaveTimeouts() {
        DeepseekJsonClient client = new DeepseekJsonClient(
                "http://gateway.example", "key", "deepseek-test", 10_000, 300_000);

        Object restTemplate = ReflectionTestUtils.getField(client, "restTemplate");
        Object requestFactory = ReflectionTestUtils.invokeMethod(restTemplate, "getRequestFactory");
        SimpleClientHttpRequestFactory factory =
                assertInstanceOf(SimpleClientHttpRequestFactory.class, requestFactory);

        // Spring 6 移除了 getter，读内部字段（int，未设置为 -1）
        assertEquals(10_000, ((Number) ReflectionTestUtils.getField(factory, "connectTimeout")).intValue());
        assertEquals(300_000, ((Number) ReflectionTestUtils.getField(factory, "readTimeout")).intValue());
    }
}
