package com.huawei.hisi.ram.nodes.impact;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImpactNodeTest {

    @Mock
    InvolvedRingResolver involvedRingResolver;
    @Mock
    ScopeNarrowingService scopeNarrowingService;
    @Mock
    ModifiedRingResolver modifiedRingResolver;
    @Mock
    ImpactRingResolver impactRingResolver;
    @Mock
    RiskScorer riskScorer;
    @Mock
    DeterministicValidator deterministicValidator;

    @Test
    void execute_buildsOutputMapMatchingImpactSchema() {
        InvolvedRing involved = new InvolvedRing(List.of(), List.of(), List.of());
        ModifiedRing modified = new ModifiedRing(List.of());
        ImpactRing impact = new ImpactRing(List.of(), List.of(), List.of(), List.of());
        RiskScore risk = new RiskScore(42.0, RiskLevel.MEDIUM);
        DeterministicValidator.ValidationOutcome outcome =
                new DeterministicValidator.ValidationOutcome(true, List.of());

        when(involvedRingResolver.resolve(anyString(), anyString())).thenReturn(involved);
        when(scopeNarrowingService.narrow(anyString(), any(), anyString())).thenReturn(List.of());
        when(modifiedRingResolver.resolve(any(), anyString(), anyInt())).thenReturn(modified);
        when(impactRingResolver.resolve(any(), anyString())).thenReturn(impact);
        when(riskScorer.score(any(), any(), any())).thenReturn(risk);
        when(deterministicValidator.validate(any(), any(), any(), anyString())).thenReturn(outcome);

        ImpactNode node = new ImpactNode(involvedRingResolver, scopeNarrowingService,
                modifiedRingResolver, impactRingResolver, riskScorer, deterministicValidator);

        Map<String, Object> out = node.execute(Map.of(
                "intent", "add payment retry",
                "project_paths", List.of("/p"),
                "acceptance_criteria", List.of("retry on 5xx")));

        assertThat(out.keySet()).containsAll(List.of("involved", "modified", "impacted", "risk", "validation"));
        @SuppressWarnings("unchecked")
        Map<String, Object> riskMap = (Map<String, Object>) out.get("risk");
        assertThat(riskMap.keySet()).containsAll(List.of("score", "level"));
        String level = (String) riskMap.get("level");
        assertThat(RiskLevel.valueOf(level)).isEqualTo(RiskLevel.MEDIUM);

        @SuppressWarnings("unchecked")
        Map<String, Object> involvedOut = (Map<String, Object>) out.get("involved");
        assertThat(involvedOut.keySet()).containsAll(List.of("seeds", "entries", "impls"));
        @SuppressWarnings("unchecked")
        Map<String, Object> impactedOut = (Map<String, Object>) out.get("impacted");
        assertThat(impactedOut.keySet()).containsAll(List.of("upstream", "downstream", "crossService", "bridges"));
        @SuppressWarnings("unchecked")
        Map<String, Object> validationOut = (Map<String, Object>) out.get("validation");
        assertThat(validationOut.keySet()).containsAll(List.of("passed", "violations"));
        assertThat(node.name()).isEqualTo("impact");
        assertThat(node.agentId()).isEqualTo("impact-v1");
    }
}
