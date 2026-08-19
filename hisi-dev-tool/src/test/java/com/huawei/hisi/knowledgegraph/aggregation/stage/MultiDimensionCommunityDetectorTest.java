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
import org.neo4j.driver.Value;
import org.neo4j.driver.Values;
import org.springframework.ai.chat.client.ChatClient;

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
        detector = new MultiDimensionCommunityDetector(driver, communityDetector, checkpointManager, extractionChatClient);
        lenient().when(driver.session()).thenReturn(session);
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
        when(callSpec.entity(MultiDimensionCommunityDetector.DomainGrouping.class))
            .thenReturn(new MultiDimensionCommunityDetector.DomainGrouping(domains));
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
}
