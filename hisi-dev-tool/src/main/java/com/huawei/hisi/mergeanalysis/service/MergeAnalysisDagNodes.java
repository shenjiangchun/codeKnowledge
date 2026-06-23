package com.huawei.hisi.mergeanalysis.service;

import com.huawei.hisi.workflow.DagNode;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class MergeAnalysisDagNodes {

    private final List<DagNode> nodes;

    public MergeAnalysisDagNodes(DiffExtractService diffExtractService,
                                 ImpactAnalysisService impactAnalysisService,
                                 TestScopeService testScopeService,
                                 com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.nodes = List.of(
                new DiffExtractDagNode(diffExtractService),
                new ImpactAnalysisDagNode(impactAnalysisService, objectMapper),
                new TestScopeDagNode(testScopeService, objectMapper));
    }

    public List<DagNode> nodes() {
        return nodes;
    }
}
