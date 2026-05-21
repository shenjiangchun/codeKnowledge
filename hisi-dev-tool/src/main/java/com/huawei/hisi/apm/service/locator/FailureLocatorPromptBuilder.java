package com.huawei.hisi.apm.service.locator;

import com.huawei.hisi.apm.model.ApmSpanEntity;
import com.huawei.hisi.apm.model.DiagnoseReport.EvidenceAnchor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Assembles a structured {@link PromptPayload} (system + user prompt) for the
 * LLM root-cause diagnosis step.
 *
 * <p>Caps the number of exception spans (top 5) and KG evidence anchors (top 10)
 * to keep the prompt within a reasonable token budget; the final {@code userPrompt}
 * is hard-capped at {@link #MAX_USER_PROMPT_CHARS} characters.
 *
 * @author HiSi DevTool Team
 */
@Component
public class FailureLocatorPromptBuilder {

    /** Maximum exception spans included in the prompt. */
    static final int MAX_EXCEPTION_SPANS = 5;
    /** Maximum KG evidence anchors included in the prompt. */
    static final int MAX_EVIDENCE_ANCHORS = 10;
    /** Maximum stacktrace head lines included per exception span. */
    static final int MAX_STACK_LINES = 5;
    /** Hard cap on final user prompt length to bound token usage. */
    static final int MAX_USER_PROMPT_CHARS = 8000;

    /** Constant system prompt instructing the LLM on output schema. */
    static final String SYSTEM_PROMPT = """
            You are an expert Java APM diagnosis assistant. Analyse the supplied exception
            spans and knowledge-graph evidence to identify the root cause.

            Respond ONLY in JSON of the form:
            {
              "rootCauseMarkdown": "## Root Cause\\n... (use Markdown)",
              "confidence": 0.0-1.0,
              "summary": "one-line summary"
            }

            Rules:
            - Be specific. Cite class.method names from the evidence in your analysis.
            - Confidence reflects how strongly the evidence supports the root cause.
            - If evidence is insufficient, return confidence < 0.3 and explain.
            - Do NOT include any prose outside the JSON.""";

    /**
     * Immutable container for the assembled prompts.
     *
     * @param systemPrompt the system-role prompt; never null
     * @param userPrompt   the user-role prompt; never null
     */
    public record PromptPayload(String systemPrompt, String userPrompt) {}

    /**
     * Build a prompt payload from the inputs.
     *
     * @param projectPath    absolute project path; required (non-null)
     * @param exceptionSpans the exception spans for the trace; required (non-null, may be empty)
     * @param kgEvidence     KG evidence anchors; required (non-null, may be empty)
     * @param userNote       optional caller-supplied note, nullable
     * @return a fully assembled prompt payload; never null
     */
    public PromptPayload build(String projectPath,
                               List<ApmSpanEntity> exceptionSpans,
                               List<EvidenceAnchor> kgEvidence,
                               String userNote) {
        Objects.requireNonNull(projectPath, "projectPath");
        Objects.requireNonNull(exceptionSpans, "exceptionSpans");
        Objects.requireNonNull(kgEvidence, "kgEvidence");

        StringBuilder sb = new StringBuilder(1024);
        sb.append("PROJECT: ").append(projectPath).append("\n\n");

        sb.append("EXCEPTION SPANS (").append(exceptionSpans.size()).append(" total):\n");
        int spanLimit = Math.min(exceptionSpans.size(), MAX_EXCEPTION_SPANS);
        for (int i = 0; i < spanLimit; i++) {
            appendSpan(sb, i + 1, exceptionSpans.get(i));
        }
        sb.append('\n');

        sb.append("KG EVIDENCE (").append(kgEvidence.size()).append(" anchors):\n");
        int evidenceLimit = Math.min(kgEvidence.size(), MAX_EVIDENCE_ANCHORS);
        for (int i = 0; i < evidenceLimit; i++) {
            appendEvidence(sb, kgEvidence.get(i));
        }
        sb.append('\n');

        sb.append("USER NOTE: ")
          .append(userNote == null || userNote.isBlank() ? "(none)" : userNote)
          .append('\n');

        String userPrompt = truncate(sb.toString(), MAX_USER_PROMPT_CHARS);
        return new PromptPayload(SYSTEM_PROMPT, userPrompt);
    }

    private static void appendSpan(StringBuilder sb, int idx, ApmSpanEntity span) {
        Map<String, String> attrs = span.getAttributes() == null ? Map.of() : span.getAttributes();
        String kind = nullSafe(span.getSpanKind(), "INTERNAL");
        String name = nullSafe(span.getOperationName(), "<unnamed>");
        sb.append(idx).append(". [").append(kind).append("] ").append(name).append('\n');
        sb.append("   exception.type: ")
          .append(nullSafe(attrs.get("exception.type"), "<unknown>")).append('\n');
        sb.append("   exception.message: ")
          .append(nullSafe(attrs.get("exception.message"), "<none>")).append('\n');
        String trace = attrs.get("exception.stacktrace");
        if (trace != null && !trace.isBlank()) {
            sb.append("   stacktrace head:\n");
            String[] lines = trace.split("\\r?\\n", -1);
            int lineLimit = Math.min(lines.length, MAX_STACK_LINES);
            for (int i = 0; i < lineLimit; i++) {
                sb.append("     ").append(lines[i]).append('\n');
            }
        }
    }

    private static void appendEvidence(StringBuilder sb, EvidenceAnchor a) {
        sb.append("- [").append(nullSafe(a.type(), "kg_method")).append("] ")
          .append(nullSafe(a.className(), "<?>"))
          .append('#').append(nullSafe(a.methodName(), "<?>"))
          .append(" (").append(nullSafe(a.filePath(), "<?>"))
          .append(':').append(a.startLine() == null ? "?" : a.startLine().toString())
          .append(")\n");
        if (a.snippet() != null && !a.snippet().isBlank()) {
            sb.append("  snippet: ").append(a.snippet().replace("\n", " \\n ")).append('\n');
        }
    }

    private static String nullSafe(String v, String fallback) {
        return (v == null || v.isEmpty()) ? fallback : v;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
