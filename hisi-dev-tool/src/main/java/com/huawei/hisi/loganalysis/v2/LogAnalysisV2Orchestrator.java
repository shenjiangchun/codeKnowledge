package com.huawei.hisi.loganalysis.v2;

import com.huawei.hisi.loganalysis.event.LogNodeEvent;
import com.huawei.hisi.loganalysis.event.LogAnalysisEventEmitter;
import com.huawei.hisi.loganalysis.nodes.ParseNode;
import com.huawei.hisi.loganalysis.nodes.ReportNode;
import com.huawei.hisi.loganalysis.orchestrator.LogAnalysisDagNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 日志分析 v2 的编排器：按固定顺序执行节点，节点间以 Map 传递上下文，实时推送与 v1 相同的节点事件。
 *
 * <p>流程：{@code ParseNode（复用 v1）→ KgEvidenceV2 → HypothesisV2 → VerifyV2 → SynthesisV2 → ReportNode（复用 v1）}。
 * 事件类型（{@code NODE_START / NODE_COMPLETE / NODE_ERROR / DAG_COMPLETE}）与 v1 一致，前端无需改动即可显示进度。
 */
@Slf4j
@Component
public class LogAnalysisV2Orchestrator {

    private final List<LogAnalysisDagNode> nodes;
    private final ObjectProvider<LogAnalysisEventEmitter> eventEmitter;

    public LogAnalysisV2Orchestrator(ParseNode parseNode,
                                     LogKgEvidenceNodeV2 kgEvidence,
                                     LogHypothesisNodeV2 hypothesis,
                                     LogVerifyNodeV2 verify,
                                     LogSynthesisNodeV2 synthesis,
                                     ReportNode reportNode,
                                     ObjectProvider<LogAnalysisEventEmitter> eventEmitter) {
        this.nodes = List.of(parseNode, kgEvidence, hypothesis, verify, synthesis, reportNode);
        this.eventEmitter = eventEmitter;
    }

    /** 节点名称列表，供日志与测试断言。 */
    public List<String> getNodeNames() {
        return nodes.stream().map(LogAnalysisDagNode::name).toList();
    }

    /** 组装输入并执行完整流程（不推送事件）。 */
    public Map<String, Object> analyzeLog(String message, String stackTrace, String projectPath,
                                          String serviceName, String traceId,
                                          List<String> projectPackagePrefixes) {
        return run(inputOf(message, stackTrace, projectPath, serviceName, traceId, projectPackagePrefixes), -1);
    }

    /** 组装输入并执行完整流程，同时按 reportId 推送节点事件。 */
    public Map<String, Object> analyzeLog(String message, String stackTrace, String projectPath,
                                          String serviceName, String traceId,
                                          List<String> projectPackagePrefixes, long reportId) {
        return run(inputOf(message, stackTrace, projectPath, serviceName, traceId, projectPackagePrefixes), reportId);
    }

    private static Map<String, Object> inputOf(String message, String stackTrace, String projectPath,
                                               String serviceName, String traceId,
                                               List<String> projectPackagePrefixes) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("message", message);
        input.put("stackTrace", stackTrace);
        input.put("projectPath", projectPath);
        input.put("serviceName", serviceName);
        input.put("traceId", traceId);
        input.put("projectPackagePrefixes", projectPackagePrefixes);
        input.put("analysisVersion", "v2.0");
        return input;
    }

    /**
     * 按顺序执行全部节点。
     *
     * <p>节点抛错时中断流程并在输出里记录 {@code errorNode / errorMessage / errorType}，由调用方决定是否回退到 v1。
     *
     * @param reportId 用于推送节点事件；小于等于 0 时不推送
     */
    public Map<String, Object> run(Map<String, Object> initialInput, long reportId) {
        log.info("[LogAnalysisV2] 开始执行: reportId={}, nodes={}", reportId, getNodeNames());

        List<String> executed = new ArrayList<>();
        Map<String, Object> current = initialInput == null ? Map.of() : initialInput;
        long dagStart = System.currentTimeMillis();

        for (LogAnalysisDagNode node : nodes) {
            emit(LogNodeEvent.nodeStart(reportId, node.name()));
            long nodeStart = System.currentTimeMillis();
            try {
                Map<String, Object> output = node.execute(current);
                current = output == null ? new LinkedHashMap<>(current) : output;
                executed.add(node.name());
                long duration = System.currentTimeMillis() - nodeStart;
                log.info("[LogAnalysisV2] 节点 {} 完成 ({}ms)", node.name(), duration);
                emit(LogNodeEvent.nodeComplete(reportId, node.name(), duration,
                        current.keySet().stream().limit(10).toList()));
            } catch (Exception e) {
                String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                log.error("[LogAnalysisV2] 节点 {} 执行失败: {}", node.name(), message, e);
                emit(LogNodeEvent.nodeError(reportId, node.name(), message));
                Map<String, Object> errorOutput = new LinkedHashMap<>(current);
                errorOutput.put("errorNode", node.name());
                errorOutput.put("errorMessage", message);
                errorOutput.put("errorType", e.getClass().getName());
                current = errorOutput;
                break;
            }
        }

        log.info("[LogAnalysisV2] 执行结束: executed={}, hasError={}, duration={}ms",
                executed, current.containsKey("errorNode"), System.currentTimeMillis() - dagStart);
        emit(LogNodeEvent.dagComplete(reportId, System.currentTimeMillis() - dagStart));
        return current;
    }

    private void emit(LogNodeEvent event) {
        if (event.reportId() <= 0) {
            return;
        }
        LogAnalysisEventEmitter emitter = eventEmitter.getIfAvailable();
        if (emitter != null) {
            emitter.emit(event);
        }
    }
}
