package com.huawei.hisi.loganalysis.orchestrator;

import com.huawei.hisi.loganalysis.event.LogAnalysisEventEmitter;
import com.huawei.hisi.loganalysis.event.LogNodeEvent;
import com.huawei.hisi.loganalysis.nodes.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Orchestrator for the log analysis DAG flow.
 *
 * Runs the 5-node DAG in order:
 * [ParseNode] → [KgSearchNode] → [CodeContextNode] → [ClaudeAnalyzeNode] → [ReportNode]
 *
 * Similar to RAM's DagExecutor but simplified for log analysis:
 * - No checkpoint caching (logs are one-shot analysis)
 * - No HITL pauses (automated analysis flow)
 */
@Slf4j
@Component
public class LogAnalysisDagOrchestrator {

    private final ParseNode parseNode;
    private final KgSearchNode kgSearchNode;
    private final CodeContextNode codeContextNode;
    private final ClaudeAnalyzeNode claudeAnalyzeNode;
    private final ReportNode reportNode;

    // Ordered list of nodes
    private final List<LogAnalysisDagNode> nodes;

    @Autowired(required = false)
    private LogAnalysisEventEmitter eventEmitter;

    public LogAnalysisDagOrchestrator(ParseNode parseNode,
                                       KgSearchNode kgSearchNode,
                                       CodeContextNode codeContextNode,
                                       ClaudeAnalyzeNode claudeAnalyzeNode,
                                       ReportNode reportNode) {
        this.parseNode = parseNode;
        this.kgSearchNode = kgSearchNode;
        this.codeContextNode = codeContextNode;
        this.claudeAnalyzeNode = claudeAnalyzeNode;
        this.reportNode = reportNode;

        this.nodes = List.of(parseNode, kgSearchNode, codeContextNode, claudeAnalyzeNode, reportNode);
    }

    /**
     * Run the full DAG with the given initial input.
     *
     * @param initialInput Input containing: message, stackTrace, projectPath, etc.
     * @return Final output containing finalReport
     */
    public Map<String, Object> run(Map<String, Object> initialInput) {
        return run(initialInput, -1);
    }

    /**
     * Run the full DAG with optional real-time event emission.
     *
     * @param initialInput Input containing: message, stackTrace, projectPath, etc.
     * @param reportId Report ID for event emission (-1 to skip events)
     * @return Final output containing finalReport
     */
    public Map<String, Object> run(Map<String, Object> initialInput, long reportId) {
        log.info("[LogAnalysisDagOrchestrator] 开始执行 DAG 流程");

        List<String> executed = new ArrayList<>();
        Map<String, Object> currentOutput = initialInput == null ? Map.of() : initialInput;
        long dagStart = System.currentTimeMillis();

        for (LogAnalysisDagNode node : nodes) {
            log.info("[LogAnalysisDagOrchestrator] 执行节点: {}", node.name());

            // Emit NODE_START event
            if (reportId > 0 && eventEmitter != null) {
                eventEmitter.emit(LogNodeEvent.nodeStart(reportId, node.name()));
            }

            long nodeStart = System.currentTimeMillis();

            try {
                Map<String, Object> input = currentOutput;
                Map<String, Object> output = node.execute(input);

                if (output == null) {
                    output = new LinkedHashMap<>(input);
                }

                executed.add(node.name());
                currentOutput = output;

                long nodeDuration = System.currentTimeMillis() - nodeStart;
                log.info("[LogAnalysisDagOrchestrator] 节点 {} 完成 ({}ms), output.keys={}",
                        node.name(), nodeDuration, currentOutput.keySet());

                // Emit NODE_COMPLETE event
                if (reportId > 0 && eventEmitter != null) {
                    eventEmitter.emit(LogNodeEvent.nodeComplete(reportId, node.name(), nodeDuration,
                            currentOutput.keySet().stream().limit(10).toList()));
                }

            } catch (Exception e) {
                log.error("[LogAnalysisDagOrchestrator] 节点 {} 执行失败: {}",
                        node.name(), e.getMessage(), e);

                // Emit NODE_ERROR event
                if (reportId > 0 && eventEmitter != null) {
                    eventEmitter.emit(LogNodeEvent.nodeError(reportId, node.name(), e.getMessage()));
                }

                // Record error and continue with partial output
                Map<String, Object> errorOutput = new LinkedHashMap<>(currentOutput);
                errorOutput.put("errorNode", node.name());
                errorOutput.put("errorMessage", e.getMessage());
                errorOutput.put("errorType", e.getClass().getName());
                currentOutput = errorOutput;
                break;
            }
        }

        long totalDuration = System.currentTimeMillis() - dagStart;
        log.info("[LogAnalysisDagOrchestrator] DAG 流程完成: executed={}, hasError={}, duration={}ms",
                executed, currentOutput.containsKey("errorNode"), totalDuration);

        // Emit DAG_COMPLETE event
        if (reportId > 0 && eventEmitter != null) {
            eventEmitter.emit(LogNodeEvent.dagComplete(reportId, totalDuration));
        }

        return currentOutput;
    }

    /**
     * Run DAG for a specific log analysis request.
     *
     * @param message Error message
     * @param stackTrace Stack trace
     * @param projectPath Project path for KG search (optional)
     * @param serviceName Service name (optional)
     * @param traceId Trace ID (optional)
     * @return Analysis result with finalReport
     */
    public Map<String, Object> analyzeLog(String message,
                                          String stackTrace,
                                          String projectPath,
                                          String serviceName,
                                          String traceId) {
        return analyzeLog(message, stackTrace, projectPath, serviceName, traceId, null);
    }

    /**
     * Run DAG for a specific log analysis request with project package prefixes.
     *
     * @param message Error message
     * @param stackTrace Stack trace
     * @param projectPath Project path for KG search (optional)
     * @param serviceName Service name (optional)
     * @param traceId Trace ID (optional)
     * @param projectPackagePrefixes List of project package prefixes to prioritize stack frame extraction
     *                               (e.g., ["com.hisilicon", "com.huawei.xxx"])
     * @return Analysis result with finalReport
     */
    public Map<String, Object> analyzeLog(String message,
                                          String stackTrace,
                                          String projectPath,
                                          String serviceName,
                                          String traceId,
                                          List<String> projectPackagePrefixes) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("message", message);
        input.put("stackTrace", stackTrace);
        input.put("projectPath", projectPath);
        input.put("serviceName", serviceName);
        input.put("traceId", traceId);
        input.put("projectPackagePrefixes", projectPackagePrefixes);

        return run(input);
    }

    /**
     * Run DAG with real-time event emission for a specific report.
     */
    public Map<String, Object> analyzeLog(String message,
                                          String stackTrace,
                                          String projectPath,
                                          String serviceName,
                                          String traceId,
                                          List<String> projectPackagePrefixes,
                                          long reportId) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("message", message);
        input.put("stackTrace", stackTrace);
        input.put("projectPath", projectPath);
        input.put("serviceName", serviceName);
        input.put("traceId", traceId);
        input.put("projectPackagePrefixes", projectPackagePrefixes);

        return run(input, reportId);
    }

    /**
     * Get the list of node names in order.
     */
    public List<String> getNodeNames() {
        return nodes.stream().map(LogAnalysisDagNode::name).toList();
    }
}