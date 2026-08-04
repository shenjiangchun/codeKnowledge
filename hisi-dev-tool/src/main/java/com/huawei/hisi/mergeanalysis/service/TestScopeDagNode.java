package com.huawei.hisi.mergeanalysis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.mergeanalysis.model.DiffResult;
import com.huawei.hisi.mergeanalysis.model.ImpactResult;
import com.huawei.hisi.mergeanalysis.model.TestScopeResult;
import com.huawei.hisi.workflow.ClarifyRequiredException;
import com.huawei.hisi.workflow.DagNode;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
public class TestScopeDagNode implements DagNode {

    private final TestScopeService testScopeService;
    private final ObjectMapper objectMapper;

    public TestScopeDagNode(TestScopeService testScopeService, ObjectMapper objectMapper) {
        this.testScopeService = testScopeService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "test_scope";
    }

    @Override
    public String agentId() {
        return "test-scope-v1";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input) throws ClarifyRequiredException {
        ImpactResult impactResult = extractImpactResult(input);
        DiffResult diffResult = extractDiffResult(input);

        if (impactResult == null || diffResult == null) {
            throw new IllegalArgumentException(
                    "TestScopeDagNode requires impactResult and diffResult in input");
        }

        log.info("[TestScopeDagNode] entryPoints={}", impactResult.getAffectedEntryPoints() != null
                ? impactResult.getAffectedEntryPoints().size() : 0);
        TestScopeResult testScopeResult = testScopeService.generateTestScope(impactResult, diffResult);

        Map<String, Object> output = new LinkedHashMap<>(input);
        output.put("testScopeResult", testScopeResult);
        return output;
    }

    @SuppressWarnings("unchecked")
    private ImpactResult extractImpactResult(Map<String, Object> input) {
        Object raw = input.get("impactResult");
        if (raw instanceof ImpactResult ir) return ir;
        if (raw instanceof Map<?, ?> map) {
            return objectMapper.convertValue(map, ImpactResult.class);
        }
        return null;
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
}
