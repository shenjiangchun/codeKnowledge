package com.huawei.hisi.loganalysis.nodes;

import com.huawei.hisi.loganalysis.orchestrator.LogAnalysisDagNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ParseNode - First node in log analysis DAG.
 *
 * Extracts structured information from raw error log:
 * - Error type (exception class)
 * - Root cause exception
 * - Key stack frames (non-framework classes)
 * - Method signatures for KG search
 */
@Slf4j
@Component
public class ParseNode implements LogAnalysisDagNode {

    private static final Pattern EXCEPTION_PATTERN =
        Pattern.compile("([\\w.]+(?:Exception|Error|Throwable))[:\\s]");
    private static final Pattern CAUSED_BY_PATTERN =
        Pattern.compile("Caused by:\\s*([\\w.]+(?:Exception|Error|Throwable))[:\\s]");
    // 正则模式：允许行首零或多个空格（兼容不同日志格式的堆栈）
    // 标准 Java 堆栈有 4 个空格，但某些日志聚合系统可能去除缩进
    private static final Pattern AT_PATTERN =
        Pattern.compile("^\\s*at\\s+([\\w.$]+)\\.([\\w<>]+)\\(([\\w.]+):(\\d+)\\)", Pattern.MULTILINE);

    // "... N more" 续行模式（堆栈省略行的 continuation）
    private static final Pattern CONTINUATION_PATTERN =
        Pattern.compile("^\\s*\\.\\.\\.\\s+\\d+\\s+more", Pattern.MULTILINE);

    // CGLIB/Spring 生成的代理类后缀（应过滤掉）
    private static final Pattern GENERATED_METHOD_PATTERN =
        Pattern.compile("$$\\w+\\$$|<generated>|FastClassBySpringCGLIB");

    @Override
    public String name() {
        return "ParseNode";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input) {
        log.info("[ParseNode] 开始解析日志");

        String message = (String) input.get("message");
        String stackTrace = (String) input.get("stackTrace");
        String fullContent = buildFullContent(message, stackTrace);

        // Extract project package prefixes from input (if provided)
        List<String> projectPackagePrefixes = extractProjectPackagePrefixes(input);

        // Extract deep mode flag (optional, default false)
        boolean deepMode = Boolean.TRUE.equals(input.get("deepMode"));

        // Diagnostic logs to understand why keyFrames might be empty
        log.info("[ParseNode] 输入诊断: message长度={}, stackTrace长度={}, fullContent长度={}, projectPrefixes={}, deepMode={}",
                message != null ? message.length() : 0,
                stackTrace != null ? stackTrace.length() : 0,
                fullContent.length(),
                projectPackagePrefixes,
                deepMode);

        Map<String, Object> output = new LinkedHashMap<>(input);

        // Extract error info
        String errorType = extractErrorType(fullContent);
        String rootCause = extractRootCause(fullContent);

        // Layered stack frame extraction (business layer + root cause layer)
        StackFrameLayers layers = extractStackFrameLayers(fullContent, projectPackagePrefixes);

        // Default: business frames (first 3 project frames)
        // Deep mode: include root cause frames
        List<Map<String, Object>> keyFrames = buildKeyFrames(layers, deepMode);
        List<String> searchTerms = buildSearchTerms(keyFrames);

        // Build parsed error structure
        Map<String, Object> parsedError = new LinkedHashMap<>();
        parsedError.put("errorType", errorType);
        parsedError.put("rootCauseException", rootCause);
        parsedError.put("fullMessage", truncate(message, 1000));
        parsedError.put("stackTrace", truncate(stackTrace, 5000));

        output.put("parsedError", parsedError);
        output.put("keyFrames", keyFrames);
        output.put("searchTerms", searchTerms);
        output.put("errorFingerprint", buildFingerprint(errorType, keyFrames));

        // Output layered frames for KG integration (KgSearchNode can use these)
        output.put("businessFrames", layers.businessFrames());
        output.put("rootCauseFrames", layers.rootCauseFrames());
        output.put("otherNonFrameworkFrames", layers.otherNonFrameworkFrames());

        log.info("[ParseNode] 解析完成: errorType={}, rootCause={}, businessFrames={}, rootCauseFrames={}, deepMode={}",
                errorType, rootCause,
                layers.businessFrames().size(),
                layers.rootCauseFrames().size(),
                deepMode);

        return output;
    }

    /**
     * Extract project package prefixes from input.
     * Supports both List<String> and comma-separated String formats.
     */
    private List<String> extractProjectPackagePrefixes(Map<String, Object> input) {
        Object prefixesObj = input.get("projectPackagePrefixes");
        if (prefixesObj == null) {
            return Collections.emptyList();
        }
        if (prefixesObj instanceof List<?> list) {
            return list.stream()
                    .filter(obj -> obj instanceof String)
                    .map(obj -> (String) obj)
                    .filter(s -> !s.isBlank())
                    .toList();
        }
        if (prefixesObj instanceof String s && !s.isBlank()) {
            // Comma-separated format: "com.hisilicon,com.huawei.xxx"
            return Arrays.stream(s.split(","))
                    .map(String::trim)
                    .filter(trimmed -> !trimmed.isEmpty())
                    .toList();
        }
        return Collections.emptyList();
    }

    private String buildFullContent(String message, String stackTrace) {
        StringBuilder sb = new StringBuilder();
        if (message != null) sb.append(message);
        if (stackTrace != null && !stackTrace.isEmpty()) {
            sb.append("\n").append(stackTrace);
        }
        return sb.toString();
    }

    private String extractErrorType(String content) {
        Matcher m = EXCEPTION_PATTERN.matcher(content);
        String last = null;
        while (m.find()) {
            last = m.group(1);
        }
        return last != null ? simplifyClassName(last) : "Unknown";
    }

    private String extractRootCause(String content) {
        Matcher m = CAUSED_BY_PATTERN.matcher(content);
        String last = null;
        while (m.find()) {
            last = m.group(1);
        }
        if (last != null) {
            return simplifyClassName(last);
        }
        // No "Caused by" - take first exception
        m = EXCEPTION_PATTERN.matcher(content);
        if (m.find()) {
            return simplifyClassName(m.group(1));
        }
        return null;
    }

    /**
     * Extract key stack frames with project package prefix prioritization.
     *
     * Strategy:
     * 1. Scan entire stack trace
     * 2. Separate into project frames (match prefixes) and other non-framework frames
     * 3. Return project frames first (up to 10), then fill with other non-framework frames
     *
     * This ensures project-specific code is extracted even if buried deep in the stack
     * (e.g., after 20+ framework/external dependency frames).
     */
    private List<Map<String, Object>> extractKeyStackFrames(String content, List<String> projectPackagePrefixes) {
        StackFrameLayers layers = extractStackFrameLayers(content, projectPackagePrefixes);
        return buildKeyFrames(layers, false);
    }

    /**
     * Layered stack frame extraction based on "Caused by" separators.
     *
     * Strategy (per roundtable discussion conclusion):
     * 1. Split stack trace by "Caused by" positions → layers
     * 2. For each layer, extract project frames + other non-framework frames
     * 3. Business layer: frames before last "Caused by" (surface exception)
     * 4. Root cause layer: frames after last "Caused by" (deepest exception)
     */
    private StackFrameLayers extractStackFrameLayers(String content, List<String> projectPackagePrefixes) {
        List<Map<String, Object>> businessProjectFrames = new ArrayList<>();
        List<Map<String, Object>> rootCauseProjectFrames = new ArrayList<>();
        List<Map<String, Object>> otherNonFrameworkFrames = new ArrayList<>();

        // Find "Caused by" positions to split layers
        List<Integer> causedByPositions = findCausedByPositions(content);

        // Scan all stack frames and assign to appropriate layer
        Matcher m = AT_PATTERN.matcher(content);
        while (m.find()) {
            String className = m.group(1);
            String methodName = m.group(2);
            String fileName = m.group(3);
            int lineNum = Integer.parseInt(m.group(4));
            int framePosition = m.start();

            // Skip framework classes and generated methods
            if (isFrameworkClass(className) || isGeneratedMethod(className, methodName)) continue;

            Map<String, Object> frame = new LinkedHashMap<>();
            frame.put("className", className);
            frame.put("simpleClassName", simplifyClassName(className));
            frame.put("methodName", methodName);
            frame.put("fileName", fileName);
            frame.put("lineNumber", lineNum);
            frame.put("fullSignature", className + "." + methodName);

            // Determine layer: business layer or root cause layer
            boolean isRootCauseLayer = isAfterLastCausedBy(framePosition, causedByPositions);
            boolean isProjectFrame = isProjectClass(className, projectPackagePrefixes);

            if (isRootCauseLayer) {
                if (isProjectFrame) {
                    rootCauseProjectFrames.add(frame);
                } else {
                    otherNonFrameworkFrames.add(frame);
                }
            } else {
                if (isProjectFrame) {
                    businessProjectFrames.add(frame);
                } else {
                    otherNonFrameworkFrames.add(frame);
                }
            }
        }

        log.info("[ParseNode] 分层提取: businessProject={}, rootCauseProject={}, otherNonFramework={}",
                businessProjectFrames.size(), rootCauseProjectFrames.size(), otherNonFrameworkFrames.size());

        return new StackFrameLayers(businessProjectFrames, rootCauseProjectFrames, otherNonFrameworkFrames);
    }

    /**
     * Find positions of all "Caused by:" markers in the content.
     */
    private List<Integer> findCausedByPositions(String content) {
        List<Integer> positions = new ArrayList<>();
        Matcher m = Pattern.compile("Caused by:").matcher(content);
        while (m.find()) {
            positions.add(m.start());
        }
        return positions;
    }

    /**
     * Check if a position is after the last "Caused by:" marker.
     * If no "Caused by" exists, all frames belong to business layer.
     */
    private boolean isAfterLastCausedBy(int position, List<Integer> causedByPositions) {
        if (causedByPositions.isEmpty()) {
            return false; // No "Caused by" → all frames are business layer
        }
        int lastCausedBy = causedByPositions.get(causedByPositions.size() - 1);
        return position > lastCausedBy;
    }

    /**
     * Build key frames from layered extraction.
     * Default mode: first 3 business project frames
     * Deep mode: business + root cause frames
     */
    private List<Map<String, Object>> buildKeyFrames(StackFrameLayers layers, boolean deepMode) {
        List<Map<String, Object>> result = new ArrayList<>();

        // Default: first 3 business project frames
        int businessLimit = 3;
        result.addAll(layers.businessFrames().subList(0, Math.min(layers.businessFrames().size(), businessLimit)));

        // Deep mode: add root cause project frames
        if (deepMode && !layers.rootCauseFrames().isEmpty()) {
            int rootCauseLimit = 5;
            result.addAll(layers.rootCauseFrames().subList(0, Math.min(layers.rootCauseFrames().size(), rootCauseLimit)));
        }

        // Fallback: if no business frames, use other non-framework frames
        if (result.isEmpty() && !layers.otherNonFrameworkFrames().isEmpty()) {
            int fallbackLimit = deepMode ? 10 : 5;
            result.addAll(layers.otherNonFrameworkFrames().subList(0, Math.min(layers.otherNonFrameworkFrames().size(), fallbackLimit)));
        }

        log.info("[ParseNode] 构建关键帧: deepMode={}, 结果数量={}", deepMode, result.size());
        return result;
    }

    /**
     * Record for layered stack frame extraction result.
     */
    private record StackFrameLayers(
        List<Map<String, Object>> businessFrames,      // Project frames before last "Caused by"
        List<Map<String, Object>> rootCauseFrames,     // Project frames after last "Caused by"
        List<Map<String, Object>> otherNonFrameworkFrames  // Non-framework, non-project frames
    ) {}

    /**
     * Check if a class belongs to the project based on package prefixes.
     */
    private boolean isProjectClass(String className, List<String> projectPackagePrefixes) {
        if (projectPackagePrefixes == null || projectPackagePrefixes.isEmpty()) {
            return false;
        }
        for (String prefix : projectPackagePrefixes) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private List<String> buildSearchTerms(List<Map<String, Object>> keyFrames) {
        List<String> terms = new ArrayList<>();
        for (Map<String, Object> frame : keyFrames) {
            terms.add((String) frame.get("className"));
            terms.add((String) frame.get("fullSignature"));
        }
        return terms;
    }

    private String buildFingerprint(String errorType, List<Map<String, Object>> keyFrames) {
        StringBuilder sb = new StringBuilder(errorType);
        for (Map<String, Object> frame : keyFrames) {
            sb.append("|").append(frame.get("fullSignature"));
        }
        return sb.toString();
    }

    private String simplifyClassName(String className) {
        if (className == null) return null;
        int lastDot = className.lastIndexOf('.');
        return lastDot >= 0 ? className.substring(lastDot + 1) : className;
    }

    private boolean isFrameworkClass(String className) {
        // 扩展的框架类排除列表（参考日志分析经验）
        return className.startsWith("java.") ||
               className.startsWith("javax.") ||
               className.startsWith("sun.") ||
               className.startsWith("sun.reflect.") ||
               className.startsWith("com.sun.") ||
               className.startsWith("org.springframework.") ||
               className.startsWith("org.apache.") ||
               className.startsWith("org.mybatis.spring") ||
               className.startsWith("com.fasterxml.") ||
               className.startsWith("io.netty.") ||
               className.startsWith("reactor.") ||
               className.startsWith("org.slf4j.") ||
               className.startsWith("ch.qos.logback.") ||
               className.startsWith("jakarta.") ||
               className.startsWith("java.util.concurrent.") ||
               className.startsWith("java.lang.reflect.") ||
               className.startsWith("com.grapecity.") ||
               className.startsWith("com.huawei.opengauss.") ||
               className.startsWith("com.huawei.it.jalor5.") ||
               // AWS SDK（新增）
               className.startsWith("com.amazonaws.") ||
               className.startsWith("software.amazon.") ||
               // Feign HTTP 客户端（新增）
               className.startsWith("feign.") ||
               // OkHttp（新增）
               className.startsWith("okhttp3.") ||
               className.startsWith("okio.");
    }

    /**
     * 判断是否为生成的方法（CGLIB代理、反射生成的临时类等）
     */
    private boolean isGeneratedMethod(String className, String methodName) {
        return GENERATED_METHOD_PATTERN.matcher(className).find() ||
               GENERATED_METHOD_PATTERN.matcher(methodName).find() ||
               methodName.contains("intercept") ||
               methodName.contains("invoke");
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }
}