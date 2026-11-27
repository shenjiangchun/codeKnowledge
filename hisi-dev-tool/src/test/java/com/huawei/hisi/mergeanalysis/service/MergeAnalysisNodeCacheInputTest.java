package com.huawei.hisi.mergeanalysis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.mergeanalysis.model.DiffResult;
import com.huawei.hisi.mergeanalysis.model.ImpactResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 回归：合入分析「重跑某节点」失败——
 * {@code Cannot construct instance of DiffResult (no Creators, like default constructor, exist)}。
 *
 * <p>重跑时上游节点走缓存，它的输出是从事件表读回的 JSON（{@code Map}），不是内存里的对象；
 * {@code ImpactAnalysisDagNode} 于是走 {@code objectMapper.convertValue(map, DiffResult.class)}，
 * 而模型只有 {@code @Builder}（隐藏了默认构造器），Jackson 无法实例化。
 * 这个失败此前被节点间确认门挡在前面（重跑根本走不到这一步），确认门修好后才暴露。
 */
@DisplayName("合入分析：缓存回读的 Map 输入必须能还原为模型对象")
class MergeAnalysisNodeCacheInputTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 模拟事件表读回：对象先序列化成 JSON，再读成 Map（与 DagExecutor 的实际路径一致）。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> asStoredJson(Object model) {
        return objectMapper.convertValue(model, Map.class);
    }

    @Test
    @DisplayName("DiffResult：Map 输入可还原，impact 节点正常执行到服务调用")
    void impactNode_rebuildsDiffResult_fromMapInput() throws Exception {
        DiffResult diff = DiffResult.builder()
                .sourceBranch("feature-b").targetBranch("master")
                .totalFiles(1).totalAdditions(3).totalDeletions(1)
                .files(List.of(DiffResult.FileDiff.builder()
                        .filePath("src/A.java").changeType("MODIFY")
                        .additions(3).deletions(1).patch("@@").build()))
                .build();
        ImpactAnalysisService service = mock(ImpactAnalysisService.class);
        ImpactResult impact = ImpactResult.builder()
                .affectedEntryPoints(List.of()).callChainEdges(List.of())
                .businessImpactSummary("s").riskLevel("LOW").build();
        when(service.analyze(any(), any())).thenReturn(impact);

        Map<String, Object> output = new ImpactAnalysisDagNode(service, objectMapper).execute(Map.of(
                "projectPath", "/p",
                "diffResult", asStoredJson(diff)));

        assertThat(output.get("impactResult")).isSameAs(impact);
        verify(service).analyze(eq("/p"), any(DiffResult.class));
    }

    @Test
    @DisplayName("三个模型：序列化→Map→还原 往返不丢字段（嵌套类也要能还原）")
    void models_roundTripThroughMap() {
        DiffResult diff = DiffResult.builder().sourceBranch("a").targetBranch("b")
                .totalFiles(2).totalAdditions(10).totalDeletions(4)
                .files(List.of(DiffResult.FileDiff.builder().filePath("f").changeType("ADD")
                        .additions(10).deletions(0).patch("p").build())).build();
        assertThat(objectMapper.convertValue(asStoredJson(diff), DiffResult.class)).isEqualTo(diff);

        ImpactResult impact = ImpactResult.builder()
                .affectedEntryPoints(List.of(ImpactResult.AffectedEntryPoint.builder()
                        .nodeId("n1").entryType("CONTROLLER").httpMethod("GET").urlPattern("/x")
                        .className("C").methodName("m").build()))
                .callChainEdges(List.of(ImpactResult.CallChainEdge.builder()
                        .callerId("c").callerName("cn").calleeId("e").calleeName("en").callType("DIRECT").build()))
                .businessImpactSummary("sum").riskLevel("HIGH").build();
        assertThat(objectMapper.convertValue(asStoredJson(impact), ImpactResult.class)).isEqualTo(impact);

        com.huawei.hisi.mergeanalysis.model.TestScopeResult scope =
                com.huawei.hisi.mergeanalysis.model.TestScopeResult.builder()
                        .groups(List.of(com.huawei.hisi.mergeanalysis.model.TestScopeResult.TestCaseGroup.builder()
                                .entryPointName("e").urlPattern("/x").urlRoot("/").coveredEntryCount(1)
                                .coveredMethods("m").riskLevel("LOW")
                                .testCases(List.of(com.huawei.hisi.mergeanalysis.model.TestScopeResult.TestCase.builder()
                                        .description("d").riskLevel("LOW").reason("r").build())).build()))
                        .regressionSuggestions(List.of("s")).build();
        assertThat(objectMapper.convertValue(asStoredJson(scope),
                com.huawei.hisi.mergeanalysis.model.TestScopeResult.class)).isEqualTo(scope);
    }
}
