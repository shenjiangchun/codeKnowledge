package com.huawei.hisi.knowledgegraph.controller;

import com.huawei.hisi.model.ApiResponse;
import com.huawei.hisi.service.KnowledgeGraphTaskService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.Value;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P1 回归：/status/batch 的分组计数改为 Driver 直查后，
 * 应正确解析每项目的 projectPath + cnt 两列并回填到状态项。
 */
@ExtendWith(MockitoExtension.class)
class BatchStatusTest {

    private static final String PROJECT = "D:/proj";

    @Mock
    private Driver driver;
    @Mock
    private SessionConfig sessionConfig;
    @Mock
    private Session session;
    @Mock
    private KnowledgeGraphTaskService taskService;

    @InjectMocks
    private KnowledgeGraphController controller;

    @Test
    void groupedCountsShouldBeParsedFromDriverResult() {
        when(driver.session(any(SessionConfig.class))).thenReturn(session);
        // 三个 GROUP BY 查询依次返回：方法数=6、关系数=2、入口点数=2
        // 先构造 result/record（内部含 stubbing），再统一 stub session.run，避免嵌套 stubbing 冲突
        Result methodResult = resultOf(record(PROJECT, 6));
        Result relationResult = resultOf(record(PROJECT, 2));
        Result entryResult = resultOf(record(PROJECT, 2));
        when(session.run(anyString(), anyMap()))
                .thenReturn(methodResult, relationResult, entryResult);
        when(taskService.getTaskStatus(anyList())).thenReturn(List.of());

        ApiResponse<List<Map<String, Object>>> resp = controller.getBatchStatus(List.of(PROJECT));

        List<Map<String, Object>> items = resp.getData();
        assertEquals(1, items.size(), "应返回 1 个项目的状态");
        Map<String, Object> item = items.get(0);
        assertEquals(6L, ((Number) item.get("methodNodeCount")).longValue(), "方法数应正确解析");
        assertEquals(2L, ((Number) item.get("callRelationCount")).longValue(), "关系数应正确解析");
        assertEquals(2L, ((Number) item.get("entryPointCount")).longValue(), "入口点数应正确解析");
        assertEquals("generated", item.get("status"), "计数 >0 时应判定为 generated");
    }

    private Result resultOf(Record record) {
        Result result = mock(Result.class);
        when(result.hasNext()).thenReturn(true, false);
        when(result.next()).thenReturn(record);
        return result;
    }

    private Record record(String projectPath, long cnt) {
        Record record = mock(Record.class);
        Value pathValue = mock(Value.class);
        Value cntValue = mock(Value.class);
        when(pathValue.asString(anyString())).thenReturn(projectPath);
        when(cntValue.asLong(0L)).thenReturn(cnt);
        when(record.get("projectPath")).thenReturn(pathValue);
        when(record.get("cnt")).thenReturn(cntValue);
        return record;
    }
}
