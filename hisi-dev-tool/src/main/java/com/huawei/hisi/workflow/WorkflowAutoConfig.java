package com.huawei.hisi.workflow;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Registers built-in workflow definitions at startup.
 */
@Component
public class WorkflowAutoConfig {

    public WorkflowAutoConfig(WorkflowRegistry registry) {
        // RAM demand analysis: multi-node DAG with HITL
        registry.buildWorkflow("demand", "需求分析", "需求分析大师完整流程",
                List.of("clarify", "impact", "implement", "verify", "tech_plan"));

        // Project status analysis: single-node
        registry.buildWorkflow("status", "项目现状分析", "项目现状分析报告",
                List.of("project_overview"));

        // Phase2 analysis: single-node
        registry.buildWorkflow("phase2", "Phase2 精准定位", "Phase2 精准定位分析",
                List.of("phase2_analysis"));
    }
}
