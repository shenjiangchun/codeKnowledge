package com.huawei.hisi.ram.nodes.impact;

import com.huawei.hisi.ram.kg.KgMcpClient;
import com.huawei.hisi.ram.kg.dto.Entry;
import com.huawei.hisi.ram.kg.dto.Impl;
import com.huawei.hisi.ram.kg.dto.SqlMapping;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Deterministic verification of LLM-claimed impact rings against the KG truth.
 *
 * <ul>
 *     <li>Rule 1 — claimed entries must be reachable as root entries of themselves.</li>
 *     <li>Rule 2 — every involved impl must appear in the modified ring (interface completeness).</li>
 *     <li>Rule 3 — SQL field consistency (placeholder; requires LLM-claimed field set).</li>
 * </ul>
 */
@Component
public class DeterministicValidator {

    /** Outcome of {@link DeterministicValidator#validate}. */
    public record ValidationOutcome(boolean passed, List<String> violations) {
        public ValidationOutcome {
            violations = violations == null ? List.of() : List.copyOf(violations);
        }
    }

    private final KgMcpClient kg;

    public DeterministicValidator(KgMcpClient kg) {
        this.kg = kg;
    }

    public ValidationOutcome validate(InvolvedRing involved,
                                      ModifiedRing modified,
                                      ImpactRing impact,
                                      String projectPath) {
        List<String> violations = new ArrayList<>();

        // Rule 1: claimed entries must be reachable as root entry points.
        // An entry IS a root entry if it's itself an EntryPointNode, or if any of its
        // callers is an EntryPointNode. Using rootEntryAncestors which handles both cases.
        Set<String> claimedEntryIds = new LinkedHashSet<>();
        for (Entry e : involved.entries()) {
            if (e != null && e.nodeId() != null) claimedEntryIds.add(e.nodeId());
        }
        if (!claimedEntryIds.isEmpty()) {
            List<Entry> rootAncestors = kg.rootEntryAncestors(
                    new ArrayList<>(claimedEntryIds), projectPath, 10);
            Set<String> reachableEntryIds = new LinkedHashSet<>();
            for (Entry r : rootAncestors) {
                if (r != null && r.nodeId() != null) reachableEntryIds.add(r.nodeId());
            }
            for (String claimed : claimedEntryIds) {
                // A claimed entry is reachable if ANY root entry ancestor's methodNodeId matches,
                // OR if the entry itself appears as a root entry (it IS an entry point)
                if (!reachableEntryIds.contains(claimed)) {
                    violations.add("Entry not reachable as a root entry: " + claimed);
                }
            }
        }

        // Rule 2: every impl must show up in modified ring.
        Set<String> modifiedIds = modified.allNodeIds();
        for (Impl impl : involved.impls()) {
            if (impl == null || impl.nodeId() == null) continue;
            if (!modifiedIds.contains(impl.nodeId())) {
                violations.add("Impl missing from modified ring: " + impl.nodeId());
            }
        }

        // Rule 3: SQL field consistency — placeholder.
        Set<String> mappers = new LinkedHashSet<>();
        for (Impl impl : involved.impls()) {
            if (impl != null && impl.className() != null && impl.className().endsWith("Mapper")) {
                mappers.add(impl.className());
            }
        }
        for (String mapper : mappers) {
            List<SqlMapping> mappings = kg.mybatisSql(mapper, projectPath);
            // TODO: requires LLM-claimed field set to cross-check tableFields ⊇ claimed.
            if (mappings == null) {
                violations.add("SQL mapping lookup failed for mapper: " + mapper);
            }
        }

        return new ValidationOutcome(violations.isEmpty(), violations);
    }
}
