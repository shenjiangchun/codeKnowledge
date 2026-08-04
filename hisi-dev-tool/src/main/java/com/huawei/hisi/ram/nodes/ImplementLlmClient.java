package com.huawei.hisi.ram.nodes;

import java.util.List;
import java.util.Map;

/**
 * LLM client abstraction for the implement stage. Produces a 3-artifact
 * (business / UI / tech) requirement draft from the impact-analysis output
 * and any acceptance criteria captured during clarify.
 */
public interface ImplementLlmClient {

    /**
     * Draft business / UI / tech plans from impact-analysis output.
     *
     * @param impactOutput       the upstream impact node output map
     * @param acceptanceCriteria list of acceptance criteria (may be empty)
     * @param model              model id selected by the caller, e.g. {@code claude-opus-4-6}
     * @return a map matching the {@code implement.output} JSON schema
     */
    Map<String, Object> draft(Map<String, Object> impactOutput,
                              List<String> acceptanceCriteria,
                              String model);
}
