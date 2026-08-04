package com.huawei.hisi.apm.service.locator;

import com.huawei.hisi.apm.model.ApmSpanEntity;
import com.huawei.hisi.apm.model.DiagnoseReport;

import java.util.List;

/**
 * Plug-in port for the LLM-driven root-cause diagnosis step of the pipeline.
 * <p>Implementations are expected to consult exception spans plus KG-derived
 * evidence and synthesise a Markdown root-cause analysis together with a
 * confidence score. The default Spring bean is a stub returning a template
 * fallback; Task 11 supplies the production implementation.
 *
 * @author HiSi DevTool Team
 */
@FunctionalInterface
public interface LlmDiagnoser {

    /**
     * Outcome of an LLM diagnose call.
     *
     * @param rootCauseMarkdown Markdown-rendered root cause analysis, nullable
     * @param confidence        confidence score in {@code [0.0, 1.0]}
     */
    record LlmResult(String rootCauseMarkdown, double confidence) {}

    /**
     * Run a single LLM diagnose call.
     *
     * @param projectPath    absolute project path
     * @param exceptionSpans exception spans for the traceId
     * @param kgEvidence     KG-derived evidence anchors produced by {@link KgEnricher}
     * @param userNote       optional caller-supplied free-form context, nullable
     * @return the LLM result; never null
     */
    LlmResult diagnose(String projectPath,
                       List<ApmSpanEntity> exceptionSpans,
                       List<DiagnoseReport.EvidenceAnchor> kgEvidence,
                       String userNote);
}
