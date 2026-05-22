package com.huawei.hisi.ram.nodes.impact;

import com.huawei.hisi.ram.orchestrator.ClarifyRequiredException;
import com.huawei.hisi.ram.orchestrator.DagNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Impact stage DAG node. Implements the 10-step SOP:
 * <ol>
 *     <li>Resolve {@link InvolvedRing} via hybrid search + entry points + implementations.</li>
 *     <li>Resolve {@link ModifiedRing} via callees tree expansion.</li>
 *     <li>Resolve {@link ImpactRing} (upstream / downstream / bridges / cross-service).</li>
 *     <li>Score risk via {@link RiskScorer}.</li>
 *     <li>Run deterministic validation via {@link DeterministicValidator}.</li>
 *     <li>Build output map matching the {@code impact.output} schema.</li>
 * </ol>
 */
@Slf4j
@Component
public class ImpactNode implements DagNode {

    static final String INPUT_INTENT = "intent";
    static final String INPUT_PROJECT_PATHS = "project_paths";
    static final int DEFAULT_TREE_DEPTH = 2;

    private final InvolvedRingResolver involvedRingResolver;
    private final ModifiedRingResolver modifiedRingResolver;
    private final ImpactRingResolver impactRingResolver;
    private final RiskScorer riskScorer;
    private final DeterministicValidator deterministicValidator;

    public ImpactNode(InvolvedRingResolver involvedRingResolver,
                      ModifiedRingResolver modifiedRingResolver,
                      ImpactRingResolver impactRingResolver,
                      RiskScorer riskScorer,
                      DeterministicValidator deterministicValidator) {
        this.involvedRingResolver = involvedRingResolver;
        this.modifiedRingResolver = modifiedRingResolver;
        this.impactRingResolver = impactRingResolver;
        this.riskScorer = riskScorer;
        this.deterministicValidator = deterministicValidator;
    }

    @Override
    public String name() {
        return "impact";
    }

    @Override
    public String agentId() {
        return "impact-v1";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input) throws ClarifyRequiredException {
        if (input == null) {
            throw new IllegalArgumentException("ImpactNode input must not be null");
        }
        String intent = stringInput(input, INPUT_INTENT);
        List<String> projectPaths = stringListInput(input, INPUT_PROJECT_PATHS);
        if (projectPaths.isEmpty()) {
            throw new IllegalArgumentException(
                    "ImpactNode requires non-empty '" + INPUT_PROJECT_PATHS + "' in input");
        }
        // Phase 1: single-project execution against the first path.
        String projectPath = projectPaths.get(0);
        log.debug("impact node executing intent='{}' projectPath='{}'", intent, projectPath);

        InvolvedRing involved = involvedRingResolver.resolve(intent, projectPath);
        ModifiedRing modified = modifiedRingResolver.resolve(involved, projectPath, DEFAULT_TREE_DEPTH);
        ImpactRing impact = impactRingResolver.resolve(modified, projectPath);
        RiskScore risk = riskScorer.score(involved, modified, impact);
        DeterministicValidator.ValidationOutcome validation =
                deterministicValidator.validate(involved, modified, impact, projectPath);

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("involved", Map.of(
                "seeds", involved.seeds(),
                "entries", involved.entries(),
                "impls", involved.impls()));
        output.put("modified", Map.of("tree", modified.tree()));
        output.put("impacted", Map.of(
                "upstream", impact.upstream(),
                "downstream", impact.downstream(),
                "crossService", impact.crossService(),
                "bridges", impact.bridges()));
        output.put("risk", Map.of(
                "score", risk.score(),
                "level", risk.level().name()));
        output.put("validation", Map.of(
                "passed", validation.passed(),
                "violations", validation.violations()));
        return output;
    }

    private static String stringInput(Map<String, Object> input, String key) {
        Object v = input.get(key);
        if (!(v instanceof String s) || s.isBlank()) {
            throw new IllegalArgumentException(
                    "ImpactNode requires non-blank '" + key + "' in input");
        }
        return s;
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringListInput(Map<String, Object> input, String key) {
        Object v = input.get(key);
        if (!(v instanceof List<?> raw)) {
            return List.of();
        }
        List<String> out = new ArrayList<>(raw.size());
        for (Object o : raw) {
            if (o instanceof String s && !s.isBlank()) {
                out.add(s);
            }
        }
        return out;
    }
}
