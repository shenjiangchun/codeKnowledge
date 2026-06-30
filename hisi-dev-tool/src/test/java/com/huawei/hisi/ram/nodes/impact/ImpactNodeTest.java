package com.huawei.hisi.ram.nodes.impact;

import com.huawei.hisi.ram.kg.KgMcpClient;
import com.huawei.hisi.ram.kg.dto.CallTreeNode;
import com.huawei.hisi.ram.kg.dto.Entry;
import com.huawei.hisi.ram.kg.dto.Seed;
import com.huawei.hisi.ram.nodes.impact.AffectedEntriesAnnotator.AnnotatedEntries;
import com.huawei.hisi.ram.nodes.impact.AffectedEntriesAnnotator.AnnotatedEntry;
import com.huawei.hisi.ram.nodes.impact.MethodTargetResolver.MethodTarget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImpactNodeTest {

    @Mock
    KgMcpClient kg;
    @Mock
    MethodTargetResolver methodTargetResolver;
    @Mock
    InvolvedRingResolver involvedRingResolver;
    @Mock
    ScopeNarrowingService scopeNarrowingService;
    @Mock
    AffectedEntriesAnnotator affectedEntriesAnnotator;
    @Mock
    RiskScorer riskScorer;
    @Mock
    DeterministicValidator deterministicValidator;

    @Test
    void execute_withTargetMethods_producesNewOutputStructure() {
        List<MethodTarget> targets = List.of(
                new MethodTarget("n1", "OrderService", "createOrder", "direct target"));
        List<Entry> upstream = List.of(
                new Entry("e1", "OrderController", "postOrder", "HTTP"));
        AnnotatedEntries annotated = new AnnotatedEntries(
                List.of(AnnotatedEntry.shallow("e1", "OrderController", "postOrder", "HTTP", "DIRECT", "直接相关")),
                List.of());
        RiskScore risk = new RiskScore(3.5, RiskLevel.LOW);
        DeterministicValidator.ValidationOutcome outcome =
                new DeterministicValidator.ValidationOutcome(true, List.of());

        when(methodTargetResolver.resolve(any(), any(), anyString())).thenReturn(targets);
        when(kg.rootEntryAncestors(any(), anyList(), anyInt())).thenReturn(upstream);
        when(affectedEntriesAnnotator.annotate(anyString(), any(), anyString(), anyString())).thenReturn(annotated);
        when(riskScorer.score(any(), any(), any())).thenReturn(risk);
        when(deterministicValidator.validate(any(), any(), any(), anyString())).thenReturn(outcome);

        ImpactNode node = new ImpactNode(kg, methodTargetResolver, involvedRingResolver,
                scopeNarrowingService, affectedEntriesAnnotator, riskScorer, deterministicValidator);

        Map<String, Object> out = node.execute(Map.of(
                "intent", "add payment retry",
                "projectHints", List.of("/p"),
                "project_paths", List.of("/p"),
                "target_methods", List.of("OrderService#createOrder")));

        // Verify new output keys
        assertThat(out.keySet()).containsAll(List.of(
                "methods_to_modify", "affected_entries", "risk", "validation", "reasoning", "markdown_report"));
        // Old keys must NOT be present
        assertThat(out.keySet()).doesNotContain("involved", "modified", "impacted");

        // methods_to_modify
        @SuppressWarnings("unchecked")
        List<Map<String, String>> methodsToModify = (List<Map<String, String>>) out.get("methods_to_modify");
        assertThat(methodsToModify).hasSize(1);
        assertThat(methodsToModify.get(0).get("className")).isEqualTo("OrderService");
        assertThat(methodsToModify.get(0).get("methodName")).isEqualTo("createOrder");

        // affected_entries
        @SuppressWarnings("unchecked")
        Map<String, Object> affectedEntries = (Map<String, Object>) out.get("affected_entries");
        @SuppressWarnings("unchecked")
        List<Map<String, String>> direct = (List<Map<String, String>>) affectedEntries.get("direct");
        assertThat(direct).hasSize(1);
        assertThat(direct.get(0).get("relevance")).isEqualTo("DIRECT");

        // risk
        @SuppressWarnings("unchecked")
        Map<String, Object> riskMap = (Map<String, Object>) out.get("risk");
        assertThat(riskMap.get("level")).isEqualTo("LOW");

        // validation
        @SuppressWarnings("unchecked")
        Map<String, Object> validationMap = (Map<String, Object>) out.get("validation");
        assertThat(validationMap.get("passed")).isEqualTo(true);

        // reasoning + markdown
        assertThat(out.get("reasoning")).isInstanceOf(String.class);
        assertThat((String) out.get("reasoning")).isNotBlank();
        assertThat(out.get("markdown_report")).isInstanceOf(String.class);
        assertThat((String) out.get("markdown_report")).contains("影响分析报告");

        assertThat(node.name()).isEqualTo("impact");
        assertThat(node.agentId()).isEqualTo("impact-v1");
    }

    @Test
    void execute_fallbackToSearch_whenTargetMethodsEmpty() {
        List<Seed> narrowedSeeds = List.of(new Seed("s1", 0.9, "related seed"));
        List<MethodTarget> targets = List.of(
                new MethodTarget("s1", "", "", "related seed"));
        List<Entry> upstream = List.of();
        AnnotatedEntries annotated = new AnnotatedEntries(List.of(), List.of());
        RiskScore risk = new RiskScore(1.0, RiskLevel.LOW);
        DeterministicValidator.ValidationOutcome outcome =
                new DeterministicValidator.ValidationOutcome(true, List.of());

        InvolvedRing involved = new InvolvedRing(narrowedSeeds, List.of(), List.of());
        when(involvedRingResolver.resolve(anyString(), any(List.class))).thenReturn(involved);
        when(scopeNarrowingService.narrow(anyString(), any(), anyString())).thenReturn(narrowedSeeds);
        when(methodTargetResolver.resolve(any(), any(), anyString())).thenReturn(targets);
        when(kg.rootEntryAncestors(any(), anyList(), anyInt())).thenReturn(upstream);
        when(affectedEntriesAnnotator.annotate(anyString(), any(), anyString(), anyString())).thenReturn(annotated);
        when(riskScorer.score(any(), any(), any())).thenReturn(risk);
        when(deterministicValidator.validate(any(), any(), any(), anyString())).thenReturn(outcome);

        ImpactNode node = new ImpactNode(kg, methodTargetResolver, involvedRingResolver,
                scopeNarrowingService, affectedEntriesAnnotator, riskScorer, deterministicValidator);

        Map<String, Object> out = node.execute(Map.of(
                "intent", "fix sync bug",
                "projectHints", List.of("/p"),
                "project_paths", List.of("/p")));

        @SuppressWarnings("unchecked")
        List<Map<String, String>> methods = (List<Map<String, String>>) out.get("methods_to_modify");
        assertThat(methods).hasSize(1);
    }

    @Test
    void execute_fillsCallPathForAnnotatedEntries() {
        List<MethodTarget> targets = List.of(
                new MethodTarget("t1", "RequireStatusServiceImpl", "syncReqStatus", "target"));
        List<Entry> upstream = List.of(
                new Entry("e1", "ReqController", "deliver", "CONTROLLER"));
        AnnotatedEntry entryWithDeep = new AnnotatedEntry("e1", "ReqController", "deliver",
                "CONTROLLER", "DIRECT", "直接相关",
                "协作交付端点", "deliver调用syncReqStatus", "状态回卷", "");
        AnnotatedEntries annotated = new AnnotatedEntries(List.of(entryWithDeep), List.of());
        RiskScore risk = new RiskScore(3.5, RiskLevel.LOW);
        DeterministicValidator.ValidationOutcome outcome =
                new DeterministicValidator.ValidationOutcome(true, List.of());

        // KG returns callees tree: deliver → RequireStatusService.syncReqStatus
        CallTreeNode targetChild = new CallTreeNode("t1", "RequireStatusServiceImpl",
                "syncReqStatus", 1, List.of());
        CallTreeNode calleesTree = new CallTreeNode("e1", "ReqController",
                "deliver", 0, List.of(targetChild));

        when(methodTargetResolver.resolve(any(), any(), anyString())).thenReturn(targets);
        when(kg.rootEntryAncestors(any(), anyList(), anyInt())).thenReturn(upstream);
        when(affectedEntriesAnnotator.annotate(anyString(), any(), anyString(), anyString())).thenReturn(annotated);
        when(kg.calleesTree(eq("ReqController"), eq("deliver"), anyList(), anyInt()))
                .thenReturn(calleesTree);
        when(riskScorer.score(any(), any(), any())).thenReturn(risk);
        when(deterministicValidator.validate(any(), any(), any(), anyString())).thenReturn(outcome);

        ImpactNode node = new ImpactNode(kg, methodTargetResolver, involvedRingResolver,
                scopeNarrowingService, affectedEntriesAnnotator, riskScorer, deterministicValidator);

        Map<String, Object> out = node.execute(Map.of(
                "intent", "需求状态回卷",
                "projectHints", List.of("/p"),
                "project_paths", List.of("/p"),
                "target_methods", List.of("RequireStatusServiceImpl#syncReqStatus")));

        @SuppressWarnings("unchecked")
        Map<String, Object> affectedEntries = (Map<String, Object>) out.get("affected_entries");
        @SuppressWarnings("unchecked")
        List<Map<String, String>> direct = (List<Map<String, String>>) affectedEntries.get("direct");
        assertThat(direct).hasSize(1);
        assertThat(direct.get(0)).containsKey("call_path");
        assertThat(direct.get(0).get("call_path")).contains("deliver");
        assertThat(direct.get(0).get("call_path")).contains("syncReqStatus");
    }
}
