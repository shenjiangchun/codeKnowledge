package com.huawei.hisi.workflow;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Registers built-in workflow definitions at startup.
 */
@Component
public class WorkflowAutoConfig {

    public WorkflowAutoConfig(WorkflowRegistry registry) {
        registerBuiltIns(registry);
    }

    /** 内置工作流注册（静态以便测试复用）。 */
    static void registerBuiltIns(WorkflowRegistry registry) {
        // RAM demand analysis: multi-node DAG with HITL
        registry.registerWorkflow(new WorkflowDefinition(
                "demand", "需求分析", "需求分析大师完整流程",
                List.of("clarify", "impact", "implement", "verify", "tech_plan"),
                Map.of()));

        // Project status analysis: single-node
        registry.registerWorkflow(new WorkflowDefinition(
                "status", "项目现状分析", "项目现状分析报告",
                List.of("project_overview"), Map.of()));

        // Phase2 analysis: single-node
        registry.registerWorkflow(new WorkflowDefinition(
                "phase2", "Phase2 精准定位", "Phase2 精准定位分析",
                List.of("phase2_analysis"), Map.of()));

        // Arch deep analysis: deterministic evidence collection + single ReAct agent node;
        // emit node lifecycle events so the SSE stream can report node progress.
        registry.registerWorkflow(new WorkflowDefinition(
                "arch-deep-analysis", "架构深度分析", "实时诊断报告 + agent 流深度分析",
                List.of("arch_evidence_collect", "arch_react_agent"),
                Map.of("skipHitl", true, "emitNodeEvents", true)));
    }
}
