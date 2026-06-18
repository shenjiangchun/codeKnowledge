package com.huawei.hisi.loganalysis.orchestrator;

import com.huawei.hisi.loganalysis.nodes.*;
import lombok.extern.slf4j.Slf4j;
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
        log.info("[LogAnalysisDagOrchestrator] 开始执行 DAG 流程");

        List<String> executed = new ArrayList<>();
        Map<String, Object> currentOutput = initialInput == null ? Map.of() : initialInput;

        for (LogAnalysisDagNode node : nodes) {
            log.info("[LogAnalysisDagOrchestrator] 执行节点: {}", node.name());

            try {
                Map<String, Object> input = currentOutput;
                Map<String, Object> output = node.execute(input);

                if (output == null) {
                    output = new LinkedHashMap<>(input);
                }

                executed.add(node.name());
                currentOutput = output;

                log.info("[LogAnalysisDagOrchestrator] 节点 {} 完成, output.keys={}",
                        node.name(), currentOutput.keySet());

            } catch (Exception e) {
                log.error("[LogAnalysisDagOrchestrator] 节点 {} 执行失败: {}",
                        node.name(), e.getMessage(), e);
                // Record error and continue with partial output
                Map<String, Object> errorOutput = new LinkedHashMap<>(currentOutput);
                errorOutput.put("errorNode", node.name());
                errorOutput.put("errorMessage", e.getMessage());
                errorOutput.put("errorType", e.getClass().getName());
                currentOutput = errorOutput;
                break;
            }
        }

        log.info("[LogAnalysisDagOrchestrator] DAG 流程完成: executed={}, hasError={}",
                executed, currentOutput.containsKey("errorNode"));

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
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("message", message);
        input.put("stackTrace", stackTrace);
        input.put("projectPath", projectPath);
        input.put("serviceName", serviceName);
        input.put("traceId", traceId);

        return run(input);
    }

    /**
     * Get the list of node names in order.
     */
    public List<String> getNodeNames() {
        return nodes.stream().map(LogAnalysisDagNode::name).toList();
    }
}