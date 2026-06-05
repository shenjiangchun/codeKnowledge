package com.huawei.hisi.mergeanalysis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.mergeanalysis.model.DiffResult;
import com.huawei.hisi.mergeanalysis.model.ImpactResult;
import com.huawei.hisi.ram.orchestrator.ClarifyRequiredException;
import com.huawei.hisi.ram.orchestrator.DagNode;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
public class ImpactAnalysisDagNode implements DagNode {

    private final ImpactAnalysisService impactAnalysisService;
    private final ObjectMapper objectMapper;

    public ImpactAnalysisDagNode(ImpactAnalysisService impactAnalysisService, ObjectMapper objectMapper) {
        this.impactAnalysisService = impactAnalysisService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "impact_analysis";
    }

    @Override
    public String agentId() {
        return "impact-analysis-v1";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input) throws ClarifyRequiredException {
        String projectPath = stringVal(input, "projectPath");
        DiffResult diffResult = extractDiffResult(input);

        if (projectPath == null || diffResult == null) {
            throw new IllegalArgumentException(
                    "ImpactAnalysisDagNode requires projectPath and diffResult in input");
        }

        log.info("[ImpactAnalysisDagNode] projectPath={} files={}", projectPath, diffResult.getTotalFiles());
        ImpactResult impactResult = impactAnalysisService.analyze(projectPath, diffResult);

        Map<String, Object> output = new LinkedHashMap<>(input);
        output.put("impactResult", impactResult);
        return output;
    }

    @SuppressWarnings("unchecked")
    private DiffResult extractDiffResult(Map<String, Object> input) {
        Object raw = input.get("diffResult");
        if (raw instanceof DiffResult dr) return dr;
        if (raw instanceof Map<?, ?> map) {
            return objectMapper.convertValue(map, DiffResult.class);
        }
        return null;
    }

    private static String stringVal(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof String s ? s : null;
    }
}
