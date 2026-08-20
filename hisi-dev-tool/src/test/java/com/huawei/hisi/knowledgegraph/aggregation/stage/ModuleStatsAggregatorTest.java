package com.huawei.hisi.knowledgegraph.aggregation.stage;

import com.huawei.hisi.neo4j.repository.ModuleNodeRepository;
import com.huawei.hisi.neo4j.repository.Neo4jMethodNodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ModuleStatsAggregator 的 CONTAINS 边写入验证（DSM 下钻的数据基础）。
 */
@DisplayName("ModuleStatsAggregator CONTAINS 边")
class ModuleStatsAggregatorTest {

    @Mock private Neo4jMethodNodeRepository methodNodeRepository;
    @Mock private ModuleNodeRepository moduleNodeRepository;
    @Mock private Driver driver;
    @Mock private Session session;
    private ModuleStatsAggregator aggregator;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        aggregator = new ModuleStatsAggregator(methodNodeRepository, moduleNodeRepository, driver, SessionConfig.forDatabase("neo4j"));
        lenient().when(driver.session(any(SessionConfig.class))).thenReturn(session);
        lenient().when(session.run(anyString(), anyMap())).thenReturn(mock(Result.class));
    }

    @Test
    @DisplayName("聚合后建立 ModuleNode -[:CONTAINS]-> MethodNode 边")
    void buildsContainsRelations() {
        aggregator.aggregate("/test", null);

        verify(session, times(1)).run(contains("CONTAINS"), anyMap());
    }

    @Test
    @DisplayName("完整聚合流程执行 DEPENDS_ON 与 CONTAINS 两类边查询")
    void runsDependencyAndContainsQueries() {
        aggregator.aggregate("/test", null);

        verify(session, times(1)).run(contains("DEPENDS_ON"), anyMap());   // buildModuleDependencies
        verify(session, times(1)).run(contains("CONTAINS"), anyMap());      // buildContainsRelations
    }
}
