package com.huawei.hisi.ram.phase2v2;

import com.huawei.hisi.ram.phase2v2.model.ChainContext;
import com.huawei.hisi.ram.phase2v2.model.ChainReport;

/**
 * 链路分析 Agent 接口。
 * <p>
 * 单条链路的端到端分析执行单元，负责：
 * - KG 数据收集（上游调用链、下游调用树、方法体、桥接点）
 * - LLM 集成分析（生成摘要、流程图、代码片段、建议）
 * - 置信度评估（KG 覆盖度、局限性说明）
 * </p>
 */
public interface ChainAnalysisAgent {

    /**
     * 执行链路分析。
     *
     * @param context 链路上下文（包含入口点、项目路径、允许工具等）
     * @return 链路分析报告
     */
    ChainReport analyze(ChainContext context);

    /**
     * Agent 类型标识。
     * <p>
     * 用于区分不同的 Agent 实现策略，例如：
     * - "claude-chain-analysis-v1": Claude SDK 驱动
     * - "mock-chain-analysis": Mock 实现（测试用）
     * </p>
     *
     * @return Agent 类型标识字符串
     */
    String agentType();
}