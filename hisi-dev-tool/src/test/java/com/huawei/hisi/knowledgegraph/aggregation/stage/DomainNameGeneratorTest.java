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
import org.neo4j.driver.Values;

import java.util.*;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("DomainNameGenerator 领域交互边测试（BELONGS_TO 边驱动）")
class DomainNameGeneratorTest {

    @Mock private Driver driver;
    @Mock private AggregationCheckpointManager checkpointManager;
    @Mock private Session session;
    private DomainNameGenerator generator;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        generator = new DomainNameGenerator(driver, checkpointManager);
        lenient().when(driver.session()).thenReturn(session);
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
    @DisplayName("基于 BELONGS_TO 边构建 INTERACTS_WITH 交互边 + checkpoint")
    void buildsInteractionsAndCheckpoint() {
        Result countResult = resultOf(List.of(Map.of("cnt", 2L)));
        when(session.run(contains("count(d) AS cnt"), anyMap())).thenReturn(countResult);

        Result interResult = resultOf(List.of(
            Map.of("src", "/test:domain:订单", "tgt", "/test:domain:支付", "weight", 5)));
        when(session.run(contains("BELONGS_TO"), anyMap())).thenReturn(interResult);

        generator.generate("/test", false);

        verify(session).run(contains("INTERACTS_WITH"), anyMap());
        verify(checkpointManager).markSuccess(eq("/test"), eq("DomainName"), contains("domains=2"));
    }
}
