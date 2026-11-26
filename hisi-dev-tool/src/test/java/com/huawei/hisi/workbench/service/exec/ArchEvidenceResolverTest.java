package com.huawei.hisi.workbench.service.exec;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 架构证据引用 → 证据包条目。引用是人读的短句，证据包是分章的大对象；
 * 这里用一个缩小的证据包验证各种引用写法都能对上具体的类、方法和文件。
 */
class ArchEvidenceResolverTest {

    private static Map<String, Object> pack() {
        return Map.of("healthScore", 10, "grade", "D", "chapters", Map.of(
                "project", Map.of(
                        "risks", List.of(
                                Map.of("severity", "HIGH", "type", "cyclic", "message", "知识图谱 ↔ 语义搜索 存在循环依赖"),
                                Map.of("severity", "HIGH", "type", "cyclic", "message", "知识图谱 ↔ 代码仓库 存在循环依赖")),
                        "globalCycles", Map.of("domainCycles", 2, "packageCycles", 2, "totalCycles", 4),
                        "layeredViolations", List.of(
                                Map.of("source", "com.huawei.hisi.config", "target", "com.huawei.hisi.model", "type", "utility_upward"),
                                Map.of("source", "com.huawei.hisi.agent", "target", "com.huawei.hisi.orchestrator", "type", "utility_upward"))),
                "method", Map.of(
                        "longMethods", Map.of("items", List.of(
                                Map.of("className", "com.huawei.hisi.skill.SkillServiceImpl", "methodName", "initSkillDefinitions",
                                        "filePath", "src/main/java/com/huawei/hisi/skill/SkillServiceImpl.java", "loc", 350),
                                Map.of("className", "com.huawei.hisi.knowledgegraph.service.KnowledgeGraphBuilder",
                                        "methodName", "resolveStaticMethodCall", "loc", 120))),
                        "tooManyParams", Map.of("items", List.of(
                                Map.of("className", "com.huawei.hisi.knowledgegraph.service.KnowledgeGraphBuilder",
                                        "methodName", "<init>", "paramCount", 16))))));
    }

    @Test
    void aLongMethodRefResolvesToTheMethodEntryWithItsFile() {
        List<ArchEvidenceResolver.Hit> hits = ArchEvidenceResolver.resolve(pack(),
                "longMethods SkillServiceImpl.initSkillDefinitions 350行", 3, 2000);

        assertEquals("/chapters/method/longMethods/items[0]", hits.get(0).where());
        assertTrue(hits.get(0).json().contains("src/main/java/com/huawei/hisi/skill/SkillServiceImpl.java"));
        assertTrue(hits.get(0).json().contains("\"loc\":350"));
    }

    @Test
    void anIndexedRefPicksThatExactEntry() {
        List<ArchEvidenceResolver.Hit> hits = ArchEvidenceResolver.resolve(pack(), "risks[1] 知识图谱↔代码仓库 cyclic", 3, 2000);

        assertEquals("/chapters/project/risks[1]", hits.get(0).where());
        assertTrue(hits.get(0).json().contains("代码仓库"));
    }

    @Test
    void aPackageEdgeRefResolvesToTheViolatingEdge() {
        List<ArchEvidenceResolver.Hit> hits = ArchEvidenceResolver.resolve(pack(), "com.huawei.hisi.config→model", 3, 2000);

        assertEquals("/chapters/project/layeredViolations[0]", hits.get(0).where());
    }

    @Test
    void aCountRefResolvesToTheSummaryObject() {
        List<ArchEvidenceResolver.Hit> hits = ArchEvidenceResolver.resolve(pack(), "globalCycles.domainCycles=2", 3, 2000);

        assertEquals("/chapters/project/globalCycles", hits.get(0).where());
    }

    @Test
    void aRefThatMatchesNothingYieldsNoHitsInsteadOfNoise() {
        assertTrue(ArchEvidenceResolver.resolve(pack(), "完全无关的词 xyzzy", 3, 2000).isEmpty());
        assertTrue(ArchEvidenceResolver.resolve(null, "risks[0]", 3, 2000).isEmpty());
        assertTrue(ArchEvidenceResolver.resolve(pack(), "", 3, 2000).isEmpty());
    }

    @Test
    void hitsStayWithinTheRequestedBudget() {
        List<ArchEvidenceResolver.Hit> hits = ArchEvidenceResolver.resolve(pack(),
                "KnowledgeGraphBuilder resolveStaticMethodCall <init>", 1, 300);

        assertEquals(1, hits.size(), "最多 maxHits 条");
    }

    @Test
    void snakeCaseRefsMatchCamelCaseKeysAndDottedIdentifiersKeepTheirDots() {
        assertEquals(List.of("package_cycles", "service", "neo4j.service", "neo4j"),
                ArchEvidenceResolver.tokens("package_cycles[1] service↔neo4j.service"));

        List<ArchEvidenceResolver.Hit> hits = ArchEvidenceResolver.resolve(pack(), "tooManyParams KnowledgeGraphBuilder.<init> 16参数", 2, 2000);
        assertEquals("/chapters/method/tooManyParams/items[0]", hits.get(0).where());
    }
}
