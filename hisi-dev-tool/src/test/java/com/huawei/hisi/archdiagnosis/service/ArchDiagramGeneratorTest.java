package com.huawei.hisi.archdiagnosis.service;

import com.huawei.hisi.knowledgegraph.controller.KnowledgeGraphController;
import com.huawei.hisi.knowledgegraph.model.CallChainGraphResponse;
import com.huawei.hisi.knowledgegraph.model.GraphEdge;
import com.huawei.hisi.knowledgegraph.model.GraphNode;
import com.huawei.hisi.model.ApiResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

/**
 * 图生成器单测：给定 chapters 样本确定性产出合法 mermaid DSL；
 * 数据不足的图被跳过，KG 查询失败不抛异常。
 */
@ExtendWith(MockitoExtension.class)
class ArchDiagramGeneratorTest {

    @Mock
    private KnowledgeGraphController kg;

    private ArchDiagramGenerator generator() {
        return new ArchDiagramGenerator(kg);
    }

    private static Map<String, Object> fullReport() {
        Map<String, Object> project = new LinkedHashMap<>();
        project.put("domains", List.of(
                domain("订单域", List.of("CONTROLLER", "SERVICE", "REPOSITORY"), 12),
                domain("用户域", List.of("CONTROLLER", "SERVICE"), 7)));
        project.put("serviceTopology", Map.of(
                "services", List.of(
                        Map.of("name", "order-service", "methodCount", 120),
                        Map.of("name", "user-service", "methodCount", 80)),
                "edges", List.of(Map.of("source", "order-service", "target", "user-service", "type", "FEIGN"))));

        Map<String, Object> module = new LinkedHashMap<>();
        module.put("dsm", Map.of(
                "modules", List.of("com.huawei.order.controller", "com.huawei.order.service", "com.huawei.order.repository"),
                "cells", List.of(
                        Map.of("sourceIdx", 0, "targetIdx", 1, "weight", 5),
                        Map.of("sourceIdx", 1, "targetIdx", 2, "weight", 3))));
        module.put("cycles", List.of(Map.of("nodes", List.of("com.a", "com.b", "com.c"), "level", "CROSS_LAYER")));
        module.put("layerViolations", List.of(Map.of(
                "source", "com.huawei.order.repository", "target", "com.huawei.order.service",
                "sourceLayer", "REPOSITORY", "targetLayer", "SERVICE")));

        Map<String, Object> classChapter = new LinkedHashMap<>();
        classChapter.put("layerViolations", List.of());

        Map<String, Object> method = new LinkedHashMap<>();
        method.put("hotspots", List.of(Map.of(
                "className", "OrderService", "methodName", "createOrder",
                "riskScore", 0.9, "complexity", 18)));

        Map<String, Object> chapters = new LinkedHashMap<>();
        chapters.put("project", project);
        chapters.put("module", module);
        chapters.put("class", classChapter);
        chapters.put("method", method);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("layerOrder", List.of("CONTROLLER", "SERVICE", "REPOSITORY", "MODEL", "UTILITY"));
        report.put("chapters", chapters);
        return report;
    }

    private static Map<String, Object> domain(String name, List<String> roles, int memberCount) {
        return Map.of("name", name, "domainWord", name, "roles", roles, "memberCount", memberCount, "complete", true);
    }

    private static CallChainGraphResponse callTree() {
        List<GraphNode> nodes = List.of(
                GraphNode.builder().id("m1").name("createOrder").className("OrderService").depth(0).build(),
                GraphNode.builder().id("m2").name("save").className("OrderRepository").depth(1).build());
        List<GraphEdge> edges = List.of(
                GraphEdge.builder().source("m1").target("m2").callType("DIRECT").build());
        return CallChainGraphResponse.builder()
                .entryId("m1").entryType("METHOD").entryKey("OrderService.createOrder")
                .maxDepth(1).totalNodes(2).nodes(nodes).edges(edges)
                .cycles(new ArrayList<>()).cycleCount(0).nodesInCycle(Set.of())
                .build();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> byId(List<Map<String, Object>> diagrams, String id) {
        return diagrams.stream()
                .filter(d -> id.equals(d.get("id")))
                .findFirst()
                .orElse(null);
    }

    @Test
    @DisplayName("完整数据 + 调用链可查：产出 6 类图")
    void generatesSixDiagramsWhenDataPresent() {
        when(kg.getCalleesTree(anyString(), anyString(), isNull(), anyList(), eq(4)))
                .thenReturn(ApiResponse.success(callTree()));

        List<Map<String, Object>> diagrams = generator().generate(fullReport(), List.of("p1"));

        assertThat(diagrams).hasSize(6);
        for (Map<String, Object> d : diagrams) {
            assertThat((String) d.get("mermaid")).isNotBlank();
            assertThat((String) d.get("name")).isNotBlank();
        }
        assertThat((String) byId(diagrams, "layered_architecture").get("mermaid")).contains("flowchart TB");
        assertThat((String) byId(diagrams, "key_call_chain").get("mermaid")).contains("flowchart TD");
    }

    @Test
    @DisplayName("空报告：仅产出分层架构骨架（无违规边），其余图跳过，不抛异常")
    void returnsOnlyLayeredSkeletonForEmptyReport() {
        List<Map<String, Object>> diagrams = generator().generate(Map.of(), List.of("p1"));

        assertThat(diagrams).hasSize(1);
        assertThat(byId(diagrams, "layered_architecture")).isNotNull();
        assertThat((String) byId(diagrams, "layered_architecture").get("mermaid")).doesNotContain("违规");
    }

    @Test
    @DisplayName("KG 调用链查询抛异常：跳过调用链图，其余图正常产出")
    void skipsCallChainWhenKgFails() {
        when(kg.getCalleesTree(anyString(), anyString(), isNull(), anyList(), eq(4)))
                .thenThrow(new RuntimeException("neo4j down"));

        List<Map<String, Object>> diagrams = generator().generate(fullReport(), List.of("p1"));

        assertThat(byId(diagrams, "key_call_chain")).isNull();
        assertThat(diagrams).hasSize(5);
    }

    @Test
    @DisplayName("分层违规边去重限量：不超过 8 条")
    void limitsViolationEdges() {
        Map<String, Object> report = fullReport();
        @SuppressWarnings("unchecked")
        Map<String, Object> module = (Map<String, Object>) ((Map<String, Object>) report.get("chapters")).get("module");
        List<Map<String, Object>> violations = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            violations.add(Map.of(
                    "sourceLayer", "REPOSITORY", "targetLayer", "SERVICE",
                    "source", "src" + i, "target", "tgt" + i));
        }
        module.put("layerViolations", violations);
        when(kg.getCalleesTree(anyString(), anyString(), isNull(), anyList(), eq(4)))
                .thenReturn(ApiResponse.success(callTree()));

        String layered = (String) byId(generator().generate(report, List.of("p1")), "layered_architecture").get("mermaid");

        long violationEdges = layered.lines().filter(l -> l.contains("违规")).count();
        assertThat(violationEdges).isLessThanOrEqualTo(8);
    }

    @Test
    @DisplayName("mermaid 节点文本转义：特殊结构字符转全角，避免破坏语法")
    void escReplacesMermaidSpecialChars() {
        assertThat(ArchDiagramGenerator.esc("a[b]c(d)e<f>g|h&i;j#k\"l`m\nn"))
                .isEqualTo("a【b】c（d）e〈f〉g｜h＆i；j＃k'l'm n");
    }

    @Test
    @DisplayName("单体项目（无跨服务边）：跳过部署拓扑图")
    void skipsDeploymentTopologyForMonolith() {
        Map<String, Object> report = fullReport();
        @SuppressWarnings("unchecked")
        Map<String, Object> project = (Map<String, Object>) ((Map<String, Object>) report.get("chapters")).get("project");
        project.put("serviceTopology", Map.of("services", List.of(), "edges", List.of()));
        when(kg.getCalleesTree(anyString(), anyString(), isNull(), anyList(), eq(4)))
                .thenReturn(ApiResponse.success(callTree()));

        List<Map<String, Object>> diagrams = generator().generate(report, List.of("p1"));

        assertThat(byId(diagrams, "deployment_topology")).isNull();
        assertThat(diagrams).hasSize(5);
    }
}
