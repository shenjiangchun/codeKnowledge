package com.huawei.hisi.loganalysis.event;

/**
 * Real-time event emitted during log analysis DAG execution.
 * Pushed to frontend via WebSocket for live node progress display.
 */
public record LogNodeEvent(
        long reportId,
        String type,       // NODE_START, NODE_COMPLETE, NODE_ERROR, DAG_COMPLETE
        String nodeName,   // parse, kg_search, code_context, claude_analyze, report
        long timestamp,
        Object payload     // node-specific data (duration, output keys, error message)
) {

    public static LogNodeEvent nodeStart(long reportId, String nodeName) {
        return new LogNodeEvent(reportId, "NODE_START", nodeName,
                System.currentTimeMillis(), null);
    }

    public static LogNodeEvent nodeComplete(long reportId, String nodeName, long durationMs, Object summary) {
        return new LogNodeEvent(reportId, "NODE_COMPLETE", nodeName,
                System.currentTimeMillis(),
                java.util.Map.of("durationMs", durationMs, "summary", summary));
    }

    public static LogNodeEvent nodeError(long reportId, String nodeName, String errorMessage) {
        return new LogNodeEvent(reportId, "NODE_ERROR", nodeName,
                System.currentTimeMillis(),
                java.util.Map.of("error", errorMessage));
    }

    public static LogNodeEvent dagComplete(long reportId, long totalDurationMs) {
        return new LogNodeEvent(reportId, "DAG_COMPLETE", "_dag",
                System.currentTimeMillis(),
                java.util.Map.of("totalDurationMs", totalDurationMs));
    }
}
