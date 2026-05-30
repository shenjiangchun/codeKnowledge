package com.huawei.hisi.ram.nodes;

import java.util.List;
import java.util.Map;

/**
 * LLM client abstraction for the tech-plan stage. Produces a complete
 * technical plan using tool-enhanced analysis (KG + FS tools).
 */
public interface TechPlanLlmClient {

    /**
     * Generate a complete technical plan using tool-enhanced analysis.
     *
     * @param impactOutput   output from the Impact node
     * @param implementOutput output from the Implement node
     * @param intent         the original requirement description
     * @param projectPath    Neo4j project path
     * @return structured technical plan output
     */
    Map<String, Object> generate(Map<String, Object> impactOutput,
                                  Map<String, Object> implementOutput,
                                  String intent,
                                  String projectPath);

    boolean isAvailable();
}
