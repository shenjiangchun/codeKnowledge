package com.huawei.hisi.knowledgegraph.controller;

import com.huawei.hisi.knowledgegraph.model.CallChainGraphResponse;
import com.huawei.hisi.knowledgegraph.model.GraphEdge;
import com.huawei.hisi.knowledgegraph.model.GraphNode;
import com.huawei.hisi.model.ApiResponse;
import com.huawei.hisi.neo4j.model.MethodNode;
import com.huawei.hisi.neo4j.repository.Neo4jMethodNodeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * B7 回归：buildDownstreamGraph 的"折叠共享 + 祖先防环"语义。
 *
 * <p>历史缺陷：重访一律当环--菱形共享（A->B->D、A->C->D，D 被第二条路径重访）被误标 inCycle，
 * 前端 FlowDag 显示假"环"。
 */
@ExtendWith(MockitoExtension.class)
class CalleesTreeGraphTest {

    private static final String PROJECT = "D:/proj";

    @Mock
    private Neo4jMethodNodeRepository methodRepo;

    @InjectMocks
    private KnowledgeGraphController controller;

    /** 菱形共享：D 被两条路径重访，不得标环；4 个节点 4 条边全部保留。 */
    @Test
    void diamondShareShouldNotBeMarkedAsCycle() {
        stubGraph("a", new String[][]{{"a", "b"}, {"a", "c"}, {"b", "d"}, {"c", "d"}});

        ApiResponse<CallChainGraphResponse> resp =
                controller.getCalleesTree("Svc", "a", PROJECT, null, 10);

        CallChainGraphResponse graph = resp.getData();
        assertEquals(4, graph.getNodes().size(), "A/B/C/D 四个节点都应在");
        assertEquals(4, graph.getEdges().size(), "B->D 与 C->D 两条边都必须保留（共享折叠不丢边）");
        assertTrue(graph.getNodesInCycle().isEmpty(), "菱形汇合不是环");
        graph.getNodes().forEach(n -> assertFalse(n.getInCycle(), "菱形汇合节点不得标环: " + n.getId()));
        graph.getEdges().forEach(e -> assertFalse(e.getIsCycleEdge(), "菱形无环边"));
    }

    /** 真环 A->B->C->A：环节点标 inCycle，回边 C->A 标 isCycleEdge。 */
    @Test
    void trueCycleShouldBeMarked() {
        stubGraph("a", new String[][]{{"a", "b"}, {"b", "c"}, {"c", "a"}});

        ApiResponse<CallChainGraphResponse> resp =
                controller.getCalleesTree("Svc", "a", PROJECT, null, 10);

        CallChainGraphResponse graph = resp.getData();
        assertEquals(3, graph.getNodes().size());
        assertTrue(graph.getNodesInCycle().contains("a"), "A 在环上");
        assertTrue(graph.getNodes().stream().filter(n -> "a".equals(n.getId()))
                .allMatch(GraphNode::getInCycle), "A 节点 inCycle 必须真正回写（历史恒 false）");
        GraphEdge backEdge = graph.getEdges().stream()
                .filter(e -> "c".equals(e.getSource()) && "a".equals(e.getTarget()))
                .findFirst().orElseThrow();
        assertTrue(backEdge.getIsCycleEdge(), "回边 C->A 应标 isCycleEdge");
    }

    // ==================== 桩 ====================

    /** 用"起点 + 边列表"搭一张图：caller -> callee 累积，避免单边桩互相覆盖。 */
    private void stubGraph(String rootId, String[][] edges) {
        when(methodRepo.findByProjectPathsAndClassName(anyList(), anyString()))
                .thenReturn(List.of(methodOf(rootId, "Svc", rootId)));
        lenient().when(methodRepo.findCalleesWithRelation(anyString())).thenReturn(List.of());
        lenient().when(methodRepo.findByNodeId(anyString()))
                .thenAnswer(inv -> Optional.of(methodOf(inv.getArgument(0), "Svc", inv.getArgument(0))));

        java.util.Map<String, List<Neo4jMethodNodeRepository.CalleeWithRelation>> byCaller = new java.util.HashMap<>();
        for (String[] e : edges) {
            byCaller.computeIfAbsent(e[0], k -> new java.util.ArrayList<>())
                    .add(new Neo4jMethodNodeRepository.CalleeWithRelation(
                            e[1], "Svc", e[1], "sig", "DIRECT", 1, null, null, null, null));
        }
        byCaller.forEach((caller, rels) -> when(methodRepo.findCalleesWithRelation(caller)).thenReturn(rels));
    }

    private MethodNode methodOf(String nodeId, String className, String methodName) {
        return MethodNode.builder()
                .nodeId(nodeId)
                .className(className)
                .methodName(methodName)
                .projectPath(PROJECT)
                .build();
    }
}
