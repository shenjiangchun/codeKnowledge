package com.huawei.hisi.mergeanalysis.service;

import com.huawei.hisi.mergeanalysis.model.DiffResult;
import com.huawei.hisi.mergeanalysis.model.ImpactResult;
import com.huawei.hisi.mergeanalysis.model.TestScopeResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class TestScopeService {

    public TestScopeResult generateTestScope(ImpactResult impactResult, DiffResult diffResult) {
        log.info("[TestScope] Generating test scope for {} entry points",
                impactResult.getAffectedEntryPoints() != null ? impactResult.getAffectedEntryPoints().size() : 0);
        // TODO: Task 13 — implement LLM test scope generation
        return TestScopeResult.builder()
                .groups(new ArrayList<>())
                .regressionSuggestions(new ArrayList<>())
                .build();
    }
}
