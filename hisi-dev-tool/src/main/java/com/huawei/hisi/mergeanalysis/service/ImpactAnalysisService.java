package com.huawei.hisi.mergeanalysis.service;

import com.huawei.hisi.mergeanalysis.model.DiffResult;
import com.huawei.hisi.mergeanalysis.model.ImpactResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class ImpactAnalysisService {

    public ImpactResult analyze(String projectPath, DiffResult diffResult) {
        log.info("[ImpactAnalysis] Analyzing {} changed files for {}", diffResult.getTotalFiles(), projectPath);
        // TODO: Task 12 — implement KG queries + LLM analysis
        return ImpactResult.builder()
                .affectedEntryPoints(new ArrayList<>())
                .callChainEdges(new ArrayList<>())
                .businessImpactSummary("Impact analysis not yet implemented")
                .riskLevel("UNKNOWN")
                .build();
    }
}
