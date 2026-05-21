package com.huawei.hisi.apm.service.locator;

import com.huawei.hisi.apm.model.ApmSpanEntity;
import com.huawei.hisi.apm.model.DiagnoseReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Production {@link KgEnricher} backed by {@link KgQueryFacade}.
 *
 * <p>For every distinct {@code (className, methodName)} extracted from the
 * exception spans, resolves a KG method anchor. Additionally, for the FIRST
 * exception span only, pulls up to three direct upstream callers to surface
 * "what called this" context.
 *
 * <p>Registered as {@link Primary} so it overrides the no-op default declared
 * in {@code ApmDiagnoseConfig}. Conditional on {@link KgQueryFacade} so the
 * no-op fallback remains in place when the KG is not wired.
 *
 * @author HiSi DevTool Team
 */
@Component
@Primary
@ConditionalOnBean(KgQueryFacade.class)
public class KgEnricherImpl implements KgEnricher {

    private static final Logger LOG = LoggerFactory.getLogger(KgEnricherImpl.class);

    /** Max upstream callers surfaced for the first exception span. */
    private static final int MAX_CALLERS = 3;

    private final KgQueryFacade facade;

    /**
     * @param facade narrow KG query facade
     */
    public KgEnricherImpl(KgQueryFacade facade) {
        this.facade = Objects.requireNonNull(facade, "facade");
    }

    /**
     * Resolve KG evidence anchors for the given exception spans.
     *
     * @param projectPath    scope passed to KG queries; must not be null
     * @param exceptionSpans exception spans; may be null or empty
     * @return immutable list of evidence anchors; never null
     */
    @Override
    public List<DiagnoseReport.EvidenceAnchor> enrich(
            String projectPath, List<ApmSpanEntity> exceptionSpans) {
        Objects.requireNonNull(projectPath, "projectPath");
        if (exceptionSpans == null || exceptionSpans.isEmpty()) {
            return List.of();
        }

        List<DiagnoseReport.EvidenceAnchor> anchors = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ApmSpanEntity span : exceptionSpans) {
            Optional<ClassMethod> cm = extractClassMethod(span);
            if (cm.isEmpty()) {
                continue;
            }
            String key = cm.get().className() + "#" + cm.get().methodName();
            if (!seen.add(key)) {
                continue;
            }
            facade.findMethodAnchor(projectPath, cm.get().className(), cm.get().methodName())
                    .ifPresent(anchors::add);
        }

        // Upstream callers of the FIRST exception span only.
        Optional<ClassMethod> first = extractClassMethod(exceptionSpans.get(0));
        first.ifPresent(cm -> anchors.addAll(
                facade.findCallerAnchors(projectPath, cm.className(), cm.methodName(), MAX_CALLERS)
        ));

        LOG.debug("KG enrichment produced {} anchor(s) for {} exception span(s)",
                anchors.size(), exceptionSpans.size());
        return List.copyOf(anchors);
    }

    /**
     * Try {@code (code.namespace, code.function)} first, then
     * {@code (class.name, method.name)}, then parse the span name by splitting
     * on the LAST dot.
     */
    private static Optional<ClassMethod> extractClassMethod(ApmSpanEntity span) {
        if (span == null) {
            return Optional.empty();
        }
        Map<String, String> attrs = span.getAttributes();
        if (attrs != null) {
            ClassMethod cm = pair(attrs.get("code.namespace"), attrs.get("code.function"));
            if (cm != null) {
                return Optional.of(cm);
            }
            cm = pair(attrs.get("class.name"), attrs.get("method.name"));
            if (cm != null) {
                return Optional.of(cm);
            }
        }
        String name = span.getOperationName();
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        int idx = name.lastIndexOf('.');
        if (idx <= 0 || idx >= name.length() - 1) {
            return Optional.empty();
        }
        return Optional.of(new ClassMethod(name.substring(0, idx), name.substring(idx + 1)));
    }

    private static ClassMethod pair(String className, String methodName) {
        if (className == null || className.isBlank() || methodName == null || methodName.isBlank()) {
            return null;
        }
        return new ClassMethod(className, methodName);
    }

    /** Lightweight value type for (className, methodName). */
    private record ClassMethod(String className, String methodName) {}
}
