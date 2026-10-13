package com.huawei.hisi.knowledgegraph.controller;

import com.huawei.hisi.model.ApiResponse;
import com.huawei.hisi.neo4j.model.EntryPointNode;
import com.huawei.hisi.neo4j.model.MethodNode;
import com.huawei.hisi.neo4j.repository.Neo4jEntryPointNodeRepository;
import com.huawei.hisi.neo4j.repository.Neo4jMethodNodeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * B8 回归：getBlastRadius 的 affectedEntryPoints 应统计"真实受影响入口点"，
 * 而非上游调用者数量（历史实现两者混淆）。
 */
@ExtendWith(MockitoExtension.class)
class BlastRadiusTest {

    private static final String PROJECT = "D:/proj";

    @Mock
    private Neo4jMethodNodeRepository methodRepo;
    @Mock
    private Neo4jEntryPointNodeRepository entryPointRepo;

    @InjectMocks
    private KnowledgeGraphController controller;

    @Test
    void affectedEntryPointsShouldCountRealEntries() {
        String centerId = "D:/proj:Svc.controller.execute";
        MethodNode center = methodOf(centerId, "controller");
        MethodNode entryCaller = methodOf("D:/proj:Svc.controller.api", "api");   // 有 EntryPoint 指向
        MethodNode plainCaller = methodOf("D:/proj:Svc.util.helper", "helper");   // 无 EntryPoint

        when(methodRepo.findByNodeId(centerId)).thenReturn(Optional.of(center));
        when(methodRepo.findCalleesUpToDepth(eq(centerId), anyInt())).thenReturn(List.of());
        when(methodRepo.findCallersUpToDepth(eq(centerId), anyInt())).thenReturn(List.of(entryCaller, plainCaller));
        when(entryPointRepo.findByMethodNodeIds(anyString(), anyList()))
                .thenReturn(List.of(entryPoint("ep1", entryCaller.getNodeId())));

        ApiResponse<Map<String, Object>> resp = controller.getBlastRadius(centerId, 5, PROJECT, null);

        Map<String, Object> data = resp.getData();
        assertEquals(1, ((Number) data.get("affectedEntryPoints")).intValue(),
                "3 个上游方法中只有 1 个是入口，entryCount 应为 1（历史误报为 2）");
    }

    @Test
    void suggestedTests_nonEmptyWhenEntryPointsExist() {
        String centerId = "D:/proj:Svc.controller.execute";
        MethodNode center = methodOf(centerId, "execute");
        MethodNode entryCaller = methodOf("D:/proj:Svc.controller.api", "api");

        when(methodRepo.findByNodeId(centerId)).thenReturn(Optional.of(center));
        when(methodRepo.findCalleesUpToDepth(eq(centerId), anyInt())).thenReturn(List.of());
        when(methodRepo.findCallersUpToDepth(eq(centerId), anyInt())).thenReturn(List.of(entryCaller));
        when(entryPointRepo.findByMethodNodeIds(anyString(), anyList()))
                .thenReturn(List.of(entryPoint("ep1", entryCaller.getNodeId())));

        ApiResponse<Map<String, Object>> resp = controller.getBlastRadius(centerId, 5, PROJECT, null);

        @SuppressWarnings("unchecked")
        List<String> tests = (List<String>) resp.getData().get("suggestedTests");
        assertFalse(tests.isEmpty(), "有受影响入口点时 suggestedTests 应非空");
        assertTrue(tests.stream().anyMatch(t -> t.contains("单元测试")), "应包含单元测试建议");
        assertTrue(tests.stream().anyMatch(t -> t.contains("端到端测试")), "应包含端到端测试建议");
    }

    @Test
    void suggestedTests_emptyWhenNoEntryPoints() {
        String centerId = "D:/proj:Svc.util.helper";
        MethodNode center = methodOf(centerId, "helper");

        when(methodRepo.findByNodeId(centerId)).thenReturn(Optional.of(center));
        when(methodRepo.findCalleesUpToDepth(eq(centerId), anyInt())).thenReturn(List.of());
        when(methodRepo.findCallersUpToDepth(eq(centerId), anyInt())).thenReturn(List.of());
        when(entryPointRepo.findByMethodNodeIds(anyString(), anyList())).thenReturn(List.of());

        ApiResponse<Map<String, Object>> resp = controller.getBlastRadius(centerId, 5, PROJECT, null);

        @SuppressWarnings("unchecked")
        List<String> tests = (List<String>) resp.getData().get("suggestedTests");
        assertTrue(tests.isEmpty(), "无受影响入口点时 suggestedTests 应为空数组");
    }

    private MethodNode methodOf(String nodeId, String methodName) {
        return MethodNode.builder()
                .nodeId(nodeId)
                .className("Svc")
                .methodName(methodName)
                .projectPath(PROJECT)
                .build();
    }

    private EntryPointNode entryPoint(String entryId, String methodNodeId) {
        return EntryPointNode.builder()
                .entryId(entryId)
                .entryType("HTTP")
                .methodNodeId(methodNodeId)
                .projectPath(PROJECT)
                .build();
    }
}
