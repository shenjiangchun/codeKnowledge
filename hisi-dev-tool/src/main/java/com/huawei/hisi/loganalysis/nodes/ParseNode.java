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
    private static final Pattern AT_PATTERN =
        Pattern.compile("^\\s+at\\s+([\\w.$]+)\\.([\\w<>]+)\\(([\\w.]+):(\\d+)\\)", Pattern.MULTILINE);

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

        Map<String, Object> output = new LinkedHashMap<>(input);

        // Extract error info
        String errorType = extractErrorType(fullContent);
        String rootCause = extractRootCause(fullContent);
        List<Map<String, Object>> keyFrames = extractKeyStackFrames(fullContent);
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

        log.info("[ParseNode] 解析完成: errorType={}, rootCause={}, keyFrames={}",
                errorType, rootCause, keyFrames.size());

        return output;
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

    private List<Map<String, Object>> extractKeyStackFrames(String content) {
        List<Map<String, Object>> frames = new ArrayList<>();
        Matcher m = AT_PATTERN.matcher(content);
        int count = 0;
        while (m.find() && count < 10) {
            String className = m.group(1);
            String methodName = m.group(2);
            String fileName = m.group(3);
            int lineNum = Integer.parseInt(m.group(4));

            if (isFrameworkClass(className)) continue;

            Map<String, Object> frame = new LinkedHashMap<>();
            frame.put("className", className);
            frame.put("simpleClassName", simplifyClassName(className));
            frame.put("methodName", methodName);
            frame.put("fileName", fileName);
            frame.put("lineNumber", lineNum);
            frame.put("fullSignature", className + "." + methodName);

            frames.add(frame);
            count++;
        }
        return frames;
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
        return className.startsWith("java.") ||
               className.startsWith("javax.") ||
               className.startsWith("sun.") ||
               className.startsWith("org.springframework.") ||
               className.startsWith("org.apache.") ||
               className.startsWith("com.fasterxml.") ||
               className.startsWith("io.netty.") ||
               className.startsWith("reactor.") ||
               className.startsWith("org.slf4j.") ||
               className.startsWith("ch.qos.logback.") ||
               className.startsWith("jakarta.");
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }
}