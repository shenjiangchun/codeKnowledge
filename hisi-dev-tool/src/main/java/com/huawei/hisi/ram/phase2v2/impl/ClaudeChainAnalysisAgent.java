package com.huawei.hisi.ram.phase2v2.impl;

import com.huawei.hisi.ram.kg.KgMcpClient;
import com.huawei.hisi.ram.phase2v2.ChainAnalysisAgent;
import com.huawei.hisi.ram.phase2v2.model.ChainContext;
import com.huawei.hisi.ram.phase2v2.model.ChainReport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Claude SDK 驱动的链路分析 Agent。
 * <p>
 * 当前为骨架实现（Task 3.1），后续 Task 3.2 将实现 KG 数据收集，
 * 后续 Task 将集成 Claude SDK 进行 LLM 分析。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClaudeChainAnalysisAgent implements ChainAnalysisAgent {

    private final KgMcpClient kgClient;

    @Override
    public String agentType() {
        return "claude-chain-analysis-v1";
    }

    @Override
    public ChainReport analyze(ChainContext context) {
        log.info("[ChainAgent] Starting analysis for chainId={} chainName={}",
                context.chainId(), context.chainName());

        try {
            // Step 1: KG 数据收集 (骨架)
            ChainReport.KgRawData kgData = collectKgData(context);

            // Step 2: 骨架返回空结果
            ChainReport.AnalysisResult analysis = new ChainReport.AnalysisResult(
                    "待分析",
                    "",
                    "",
                    "",
                    List.of(),
                    List.of(),
                    new ChainReport.Confidence("insufficient",
                            new ChainReport.KgCoverage(false, false, 0, List.of("骨架实现")),
                            List.of())
            );

            return new ChainReport(
                    context.chainId(),
                    context.chainName(),
                    new ChainReport.EntryPointInfo(
                            context.entryPoint().type(),
                            context.entryPoint().className(),
                            context.entryPoint().methodName(),
                            context.entryPoint().nodeId()
                    ),
                    analysis,
                    kgData,
                    "DONE",
                    null
            );
        } catch (Exception e) {
            log.error("[ChainAgent] Analysis failed for chainId={}: {}",
                    context.chainId(), e.getMessage(), e);

            return new ChainReport(
                    context.chainId(),
                    context.chainName(),
                    null,
                    null,
                    null,
                    "FAILED",
                    e.getMessage()
            );
        }
    }

    /**
     * 收集 KG 数据 (骨架)。
     * <p>
     * TODO: Task 3.2 实现
     * - 上游调用链 (rootEntries / affecting)
     * - 下游调用树 (calleesTree / downstream)
     * - 方法体 (loadMethodBodies)
     * - 桥接点 (bridges)
     * </p>
     *
     * @param context 链路上下文
     * @return KG 原始数据（当前为空列表）
     */
    private ChainReport.KgRawData collectKgData(ChainContext context) {
        // TODO: Task 3.2 实现
        return new ChainReport.KgRawData(List.of(), List.of(), List.of(), List.of());
    }
}