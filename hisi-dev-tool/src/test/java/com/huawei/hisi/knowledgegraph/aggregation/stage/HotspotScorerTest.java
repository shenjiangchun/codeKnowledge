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
import org.neo4j.driver.Values;

import java.util.*;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("HotspotScorer 文件级热点评分测试")
class HotspotScorerTest {

    @Mock private Driver driver;
    @Mock private AggregationCheckpointManager checkpointManager;
    @Mock private Session session;
    private HotspotScorer scorer;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        scorer = new HotspotScorer(driver, SessionConfig.forDatabase("neo4j"), checkpointManager);
        lenient().when(driver.session(any(SessionConfig.class))).thenReturn(session);
        lenient().when(session.run(anyString(), anyMap())).thenReturn(mock(Result.class));
    }

    private Result resultOf(List<Map<String, Object>> rows) {
        Result result = mock(Result.class);
        List<Record> recs = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Record rec = mock(Record.class);
            for (var e : row.entrySet()) {
                when(rec.get(e.getKey())).thenReturn(Values.value(e.getValue()));
            }
            recs.add(rec);
        }
        Iterator<Record> it = recs.iterator();
        when(result.hasNext()).thenAnswer(inv -> it.hasNext());
        when(result.next()).thenAnswer(inv -> it.next());
        return result;
    }

    @Test
    @DisplayName("文件级聚合 → riskScore 写 ChurnNode，不写 MethodNode")
    void score_writesFileLevelRiskToChurnNode() {
        Result methods = resultOf(List.of(
            Map.of("nodeId", "a.OrderService.place", "filePath", "src/OrderService.java",
                "complexity", 30, "inDegree", 5),
            Map.of("nodeId", "a.OrderService.cancel", "filePath", "src/OrderService.java",
                "complexity", 10, "inDegree", 3),
            Map.of("nodeId", "a.PaymentService.pay", "filePath", "src/PaymentService.java",
                "complexity", 20, "inDegree", 7)));
        Result churn = resultOf(List.of(
            Map.of("filePath", "src/OrderService.java", "cnt", 40)));
        Result cycles = resultOf(List.of());

        when(session.run(contains("m.complexity IS NOT NULL"), anyMap())).thenReturn(methods);
        when(session.run(contains("commitCount90d"), anyMap())).thenReturn(churn);
        when(session.run(contains("[r:CALLS]"), anyMap())).thenReturn(cycles);

        scorer.score("/test", null);

        // 2 个文件 → 2 次 ChurnNode MERGE
        verify(session, times(2)).run(contains("MERGE (c:ChurnNode"), anyMap());
        // 不再写 MethodNode.riskScore
        verify(session, never()).run(contains("SET m.riskScore"), anyMap());
    }

    @Test
    @DisplayName("无方法 → 不写任何节点")
    void score_noMethods_noop() {
        Result empty = resultOf(List.of());
        when(session.run(contains("m.complexity IS NOT NULL"), anyMap())).thenReturn(empty);

        scorer.score("/test", null);

        verify(session, never()).run(contains("MERGE (c:ChurnNode"), anyMap());
    }
}
