package com.huawei.hisi.knowledgegraph.aggregation.stage;

import com.huawei.hisi.knowledgegraph.aggregation.AggregationCheckpointManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.Value;
import org.neo4j.driver.Values;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.*;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("MultiDimensionCommunityDetector 领域检测测试（LLM 全局归纳 + BELONGS_TO 边）")
class MultiDimensionCommunityDetectorTest {

    @Mock private Driver driver;
    @Mock private CommunityDetector communityDetector;
    @Mock private AggregationCheckpointManager checkpointManager;
    @Mock private ChatClient extractionChatClient;
    @Mock private Session session;
    private MultiDimensionCommunityDetector detector;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        detector = new MultiDimensionCommunityDetector(driver, SessionConfig.forDatabase("neo4j"), communityDetector, checkpointManager, extractionChatClient);
        lenient().when(driver.session(any(SessionConfig.class))).thenReturn(session);
        Result result = mock(Result.class);
        org.neo4j.driver.summary.ResultSummary summary = mock(org.neo4j.driver.summary.ResultSummary.class);
        org.neo4j.driver.summary.SummaryCounters counters = mock(org.neo4j.driver.summary.SummaryCounters.class);
        lenient().when(result.consume()).thenReturn(summary);
        lenient().when(summary.counters()).thenReturn(counters);
        lenient().when(counters.propertiesSet()).thenReturn(1);
        lenient().when(session.run(anyString(), anyMap())).thenReturn(result);
    }

    /** 构建一行记录（支持 null 值，Map.of 不支持 null） */
    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static Result resultOf(List<Map<String, Object>> rows) {
        Result result = mock(Result.class);
        List<Record> recs = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Record rec = mock(Record.class);
            for (var e : row.entrySet()) {
                Object v = e.getValue();
                Value val = v == null ? Values.NULL : Values.value(v);
                when(rec.get(e.getKey())).thenReturn(val);
            }
            recs.add(rec);
        }
        Iterator<Record> it = recs.iterator();
        when(result.hasNext()).thenAnswer(inv -> it.hasNext());
        when(result.next()).thenAnswer(inv -> it.next());
        return result;
    }

    private void stubLlm(List<MultiDimensionCommunityDetector.DomainClassList> domains) {
        ChatClient.ChatClientRequestSpec reqSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        when(extractionChatClient.prompt()).thenReturn(reqSpec);
        when(reqSpec.user(anyString())).thenReturn(reqSpec);
        when(reqSpec.call()).thenReturn(callSpec);
        // 模拟中转把思考散文（thinking 块）与 JSON（text 块）拆成两个 Generation，thinking 在前
        String json = toJson(domains);
        ChatResponse chatResponse = new ChatResponse(List.of(
            new Generation(new AssistantMessage("用户要求将类按业务领域分类…（思考散文）")),
            new Generation(new AssistantMessage(json))));
        when(callSpec.chatResponse()).thenReturn(chatResponse);
    }

    /** 首次返回截断 JSON（提取失败），第二次返回完整 JSON —— 验证压缩上下文重试 */
    private void stubLlmTruncatedThenSuccess(List<MultiDimensionCommunityDetector.DomainClassList> domains) {
        ChatClient.ChatClientRequestSpec reqSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        when(extractionChatClient.prompt()).thenReturn(reqSpec);
        when(reqSpec.user(anyString())).thenReturn(reqSpec);
        when(reqSpec.call()).thenReturn(callSpec);
        // 第一次：JSON 未闭合（截断），RobustJsonExtractor 提取失败返回 null
        ChatResponse truncated = new ChatResponse(List.of(
            new Generation(new AssistantMessage("思考散文")),
            new Generation(new AssistantMessage("{\"domains\":[{\"domainName\":\"订单\",\"classNames\":[\"com.huawei.hisi.order.OrderService\""))));
        // 第二次：完整 JSON
        ChatResponse complete = new ChatResponse(List.of(
            new Generation(new AssistantMessage("思考散文")),
            new Generation(new AssistantMessage(toJson(domains)))));
        when(callSpec.chatResponse()).thenReturn(truncated, complete);
    }

    private static String toJson(List<MultiDimensionCommunityDetector.DomainClassList> domains) {
        StringBuilder sb = new StringBuilder("{\"domains\":[");
        for (int i = 0; i < domains.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"domainName\":\"").append(domains.get(i).domainName()).append("\",\"classNames\":[");
            for (int j = 0; j < domains.get(i).classNames().size(); j++) {
                if (j > 0) sb.append(',');
                sb.append('"').append(domains.get(i).classNames().get(j)).append('"');
            }
            sb.append("]}");
        }
        return sb.append("]}").toString();
    }

    @Test
    @DisplayName("LLM 全局归纳成功 → 写 DomainNode + BELONGS_TO 边 + 清理旧 DomainNode")
    void llmGlobalInduction_writesDomainsAndEdges() {
        Result classResult = resultOf(List.of(
            row("cls", "com.huawei.hisi.order.OrderService", "method", "placeOrder", "desc", "处理订单下单", "sig", "OrderRequest"),
            row("cls", "com.huawei.hisi.order.OrderController", "method", "createOrder", "desc", null, "sig", "OrderRequest"),
            row("cls", "com.huawei.hisi.pay.PaymentService", "method", "pay", "desc", "执行支付", "sig", "")));
        when(session.run(contains("m.className IS NOT NULL"), anyMap())).thenReturn(classResult);
        Result emptyCommunity = resultOf(List.of());
        when(session.run(contains("communityId IS NOT NULL"), anyMap())).thenReturn(emptyCommunity);

        stubLlm(List.of(
            new MultiDimensionCommunityDetector.DomainClassList("订单",
                List.of("com.huawei.hisi.order.OrderService", "com.huawei.hisi.order.OrderController")),
            new MultiDimensionCommunityDetector.DomainClassList("支付",
                List.of("com.huawei.hisi.pay.PaymentService"))));

        detector.detect("/test");

        verify(communityDetector).detect("/test");
        verify(session, atLeastOnce()).run(contains("BELONGS_TO"), anyMap());
        verify(session).run(contains("DETACH DELETE d"), anyMap());
        verify(checkpointManager).markSuccess(eq("/test"), eq("Community"), contains("domains=2"));
    }

    @Test
    @DisplayName("LLM 失败 → 降级，不写 DomainNode、不清理")
    void llmFails_degrades() {
        Result classResult = resultOf(List.of(
            row("cls", "com.huawei.hisi.order.OrderService", "method", "placeOrder", "desc", "下单", "sig", "")));
        when(session.run(contains("m.className IS NOT NULL"), anyMap())).thenReturn(classResult);
        when(extractionChatClient.prompt()).thenThrow(new RuntimeException("LLM 欠费"));

        detector.detect("/test");

        verify(communityDetector).detect("/test");
        verify(session, never()).run(contains("BELONGS_TO"), anyMap());
        verify(session, never()).run(contains("DETACH DELETE d"), anyMap());
        verify(checkpointManager).markSuccess(eq("/test"), eq("Community"), contains("semantic-degraded"));
    }

    @Test
    @DisplayName("LLM 首次返回截断 JSON → 压缩上下文重试 → 第二次成功产出领域")
    void llmTruncated_thenRetrySucceeds() {
        Result classResult = resultOf(List.of(
            row("cls", "com.huawei.hisi.order.OrderService", "method", "placeOrder", "desc", "处理订单下单", "sig", "OrderRequest"),
            row("cls", "com.huawei.hisi.order.OrderController", "method", "createOrder", "desc", null, "sig", "OrderRequest")));
        when(session.run(contains("m.className IS NOT NULL"), anyMap())).thenReturn(classResult);
        Result emptyCommunity = resultOf(List.of());
        when(session.run(contains("communityId IS NOT NULL"), anyMap())).thenReturn(emptyCommunity);

        stubLlmTruncatedThenSuccess(List.of(
            new MultiDimensionCommunityDetector.DomainClassList("订单",
                List.of("com.huawei.hisi.order.OrderService", "com.huawei.hisi.order.OrderController"))));

        detector.detect("/test");

        verify(checkpointManager).markSuccess(eq("/test"), eq("Community"), contains("domains=1"));
    }
}
