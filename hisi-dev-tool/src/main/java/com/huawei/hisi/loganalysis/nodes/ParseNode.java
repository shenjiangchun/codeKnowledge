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

        // Diagnostic logs to understand why keyFrames might be empty
        log.info("[ParseNode] 输入诊断: message长度={}, stackTrace长度={}, fullContent长度={}",
                message != null ? message.length() : 0,
                stackTrace != null ? stackTrace.length() : 0,
                fullContent.length());
        if (message != null && !message.isEmpty()) {
            log.info("[ParseNode] message前200字符: {}", truncate(message, 200));
        }
        if (stackTrace != null && !stackTrace.isEmpty()) {
            log.info("[ParseNode] stackTrace前300字符: {}", truncate(stackTrace, 300));
        }

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

            // 过滤框架类和生成的方法（CGLIB代理等）
            if (isFrameworkClass(className) || isGeneratedMethod(className, methodName)) continue;

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