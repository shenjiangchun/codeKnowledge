package com.huawei.hisi.apm.service.locator;

import com.huawei.hisi.apm.model.DiagnoseReport;
import com.huawei.hisi.neo4j.model.MethodNode;
import com.huawei.hisi.neo4j.repository.Neo4jMethodNodeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Narrow facade over {@link Neo4jMethodNodeRepository} for the APM Failure
 * Locator's KG enrichment step. Encapsulates two specific lookups used by
 * {@link KgEnricherImpl}: resolve a method anchor by {@code (className,
 * methodName)} and fetch its direct upstream callers.
 *
 * <p>Bean is only registered when {@link Neo4jMethodNodeRepository} is on the
 * application context — i.e. when the KG (Neo4j) is wired. Otherwise the
 * no-op {@code KgEnricher} default bean remains in place.
 *
 * @author HiSi DevTool Team
 */
public class KgQueryFacade {

    private static final Logger LOG = LoggerFactory.getLogger(KgQueryFacade.class);

    /** Evidence type for a directly-matched method anchor. */
    static final String KIND_KG_METHOD = "kg_method";
    /** Evidence type for an upstream caller anchor. */
    static final String KIND_KG_CALLER = "kg_caller";

    /** Max snippet length to embed in evidence (keep prompt-budget friendly). */
    private static final int SNIPPET_MAX_LEN = 200;

    private final Neo4jMethodNodeRepository repository;

    /**
     * @param repository Neo4j-backed method-node repository
     */
    public KgQueryFacade(Neo4jMethodNodeRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    /**
     * Look up an evidence anchor for a {@code (className, methodName)} pair in
     * the given scope.
     *
     * @param scope      scope identifier — typically the project path
     * @param className  fully-qualified class name; must not be null
     * @param methodName method name; must not be null
     * @return optional anchor; empty when no matching KG node exists
     */
    public Optional<DiagnoseReport.EvidenceAnchor> findMethodAnchor(
            String scope, String className, String methodName) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(className, "className");
        Objects.requireNonNull(methodName, "methodName");
        try {
            List<MethodNode> matches = repository
                    .findByProjectPathAndClassNameAndMethodName(scope, className, methodName);
            if (matches == null || matches.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(toAnchor(KIND_KG_METHOD, matches.get(0)));
        } catch (RuntimeException ex) {
            LOG.warn("KG findMethodAnchor failed: scope={}, class={}, method={}: {}",
                    scope, className, methodName, ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Look up up to {@code maxCallers} direct upstream callers of the given
     * method within the scope.
     *
     * @param scope      scope identifier — typically the project path
     * @param className  fully-qualified class name; must not be null
     * @param methodName method name; must not be null
     * @param maxCallers maximum caller anchors to return; non-positive returns empty
     * @return immutable list of caller anchors; never null, possibly empty
     */
    public List<DiagnoseReport.EvidenceAnchor> findCallerAnchors(
            String scope, String className, String methodName, int maxCallers) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(className, "className");
        Objects.requireNonNull(methodName, "methodName");
        if (maxCallers <= 0) {
            return List.of();
        }
        try {
            List<MethodNode> matches = repository
                    .findByProjectPathAndClassNameAndMethodName(scope, className, methodName);
            if (matches == null || matches.isEmpty()) {
                return List.of();
            }
            String nodeId = matches.get(0).getNodeId();
            if (nodeId == null) {
                return List.of();
            }
            List<MethodNode> callers = repository.findCallers(nodeId);
            if (callers == null || callers.isEmpty()) {
                return List.of();
            }
            List<DiagnoseReport.EvidenceAnchor> out = new ArrayList<>(
                    Math.min(callers.size(), maxCallers));
            for (MethodNode caller : callers) {
                if (out.size() >= maxCallers) {
                    break;
                }
                if (caller != null) {
                    out.add(toAnchor(KIND_KG_CALLER, caller));
                }
            }
            return Collections.unmodifiableList(out);
        } catch (RuntimeException ex) {
            LOG.warn("KG findCallerAnchors failed: scope={}, class={}, method={}: {}",
                    scope, className, methodName, ex.getMessage());
            return List.of();
        }
    }

    private static DiagnoseReport.EvidenceAnchor toAnchor(String kind, MethodNode node) {
        String snippet = pickSnippet(node);
        return new DiagnoseReport.EvidenceAnchor(
                kind,
                node.getClassName(),
                node.getMethodName(),
                node.getFilePath(),
                node.getStartLine(),
                null,
                snippet
        );
    }

    private static String pickSnippet(MethodNode node) {
        String raw = node.getDescription();
        if (raw == null || raw.isBlank()) {
            raw = node.getComment();
        }
        if (raw == null || raw.isBlank()) {
            raw = node.getMethodBody();
        }
        if (raw == null) {
            return null;
        }
        String trimmed = raw.strip();
        if (trimmed.length() <= SNIPPET_MAX_LEN) {
            return trimmed;
        }
        return trimmed.substring(0, SNIPPET_MAX_LEN) + "...";
    }
}
