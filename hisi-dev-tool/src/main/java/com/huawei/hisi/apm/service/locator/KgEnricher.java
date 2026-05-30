package com.huawei.hisi.apm.service.locator;

import com.huawei.hisi.apm.model.ApmSpanEntity;
import com.huawei.hisi.apm.model.DiagnoseReport;

import java.util.List;

/**
 * Plug-in port for the knowledge-graph enrichment step of the diagnose pipeline.
 * <p>Implementations translate raw exception spans into structured
 * {@link DiagnoseReport.EvidenceAnchor evidence anchors} pointing into the KG
 * (method nodes, call sites, etc.) which the LLM step subsequently consumes.
 * The default Spring bean is a no-op returning an empty list; Task 10 supplies
 * the production implementation.
 *
 * @author HiSi DevTool Team
 */
@FunctionalInterface
public interface KgEnricher {

    /**
     * Resolve KG-derived evidence anchors for the given exception spans.
     *
     * @param projectPath    absolute project path used to scope KG lookups
     * @param exceptionSpans the exception spans gathered for the traceId
     * @return evidence anchors; never null, possibly empty
     */
    List<DiagnoseReport.EvidenceAnchor> enrich(String projectPath, List<ApmSpanEntity> exceptionSpans);
}
