package com.huawei.hisi.utils;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 堆栈过滤器
 * 用于过滤堆栈信息，仅保留业务代码帧
 */
@Slf4j
@Component
public class StackTraceFilter {

    // 最大输出帧数
    private static final int MAX_FRAMES = 15;

    // Java 内部帧过滤模式
    private static final List<String> JAVA_INTERNAL_PATTERNS = List.of(
        "java\\.lang\\.",
        "java\\.util\\.",
        "java\\.io\\.",
        "java\\.net\\.",
        "java\\.nio\\.",
        "java\\.time\\.",
        "java\\.security\\.",
        "java\\.reflect\\.",
        "java\\.text\\.",
        "sun\\.",
        "jdk\\.",
        "com\\.sun\\."
    );

    // 拦截器/代理帧过滤模式
    private static final List<String> PROXY_PATTERNS = List.of(
        "\\$Proxy",
        "\\$Lambda",
        "InvocationHandler",
        "Reflective",
        "CGLIB",
        "Interceptor",
        "AOP",
        "Proxy",
        "FilterChain",
        "DispatcherServlet",
        "RequestMappingHandlerAdapter",
        "HandlerExecutionChain",
        "AbstractInterceptorHandlerAdapter"
    );

    // Spring 框架过滤模式
    private static final List<String> SPRING_FRAMEWORK_PATTERNS = List.of(
        "org\\.springframework\\.web\\.",
        "org\\.springframework\\.aop\\.",
        "org\\.springframework\\.beans\\.",
        "org\\.springframework\\.context\\.",
        "org\\.springframework\\.core\\.",
        "org\\.springframework\\.util\\.",
        "org\\.springframework\\.validation\\.",
        "org\\.springframework\\.transaction\\."
    );

    // Feign 客户端过滤模式
    private static final List<String> FEIGN_PATTERNS = List.of(
        "^feign\\.",
        "^okhttp3\\.",
        "^okio\\.",
        "^retrofit2\\."
    );

    // 堆栈行解析模式：at className.methodName(fileName:lineNumber)
    private static final Pattern STACK_LINE_PATTERN = Pattern.compile(
        "^\\s*at\\s+([\\w.]+)\\.([\\w$]+)\\(([\\w./\\\\]+):(-?\\d+)\\)\\s*$"
    );

    // 项目源码根目录（从配置读取）
    private final String projectDir;

    /**
     * 构造函数
     *
     * @param projectDir 项目源码根目录路径
     */
    public StackTraceFilter(@Value("${app.project_dir:}") String projectDir) {
        this.projectDir = projectDir != null ? projectDir : "";
        log.info("StackTraceFilter initialized with projectDir: {}", this.projectDir);
    }

    /**
     * 过滤堆栈，仅保留业务代码帧
     *
     * @param stackTrace 原始堆栈信息
     * @return 过滤后的堆栈帧列表（最多 MAX_FRAMES 个）
     */
    public List<StackFrame> filter(String stackTrace) {
        if (stackTrace == null || stackTrace.trim().isEmpty()) {
            return new ArrayList<>();
        }

        List<StackFrame> result = new ArrayList<>();
        String[] lines = stackTrace.split("\n");

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }

            // 跳过异常类型行（如：java.lang.IllegalStateException: ...）
            if (line.startsWith("java.") || line.startsWith("javax.") ||
                line.startsWith("org.") || !line.startsWith("at")) {
                continue;
            }

            // 过滤 Java 内部帧
            if (isJavaInternal(line)) {
                continue;
            }

            // 过滤代理/拦截器帧
            if (isProxyFrame(line)) {
                continue;
            }

            // 过滤 Spring 框架帧
            if (isSpringFramework(line)) {
                continue;
            }

            // 过滤 Feign/HTTP 客户端帧
            if (isFeignClient(line)) {
                continue;
            }

            // 检查是否为项目源码
            if (!isProjectSource(line)) {
                continue;
            }

            // 解析堆栈帧
            StackFrame frame = parseStackFrame(line);
            if (frame != null) {
                result.add(frame);
            }

            // 达到最大帧数限制
            if (result.size() >= MAX_FRAMES) {
                break;
            }
        }

        log.debug("Filtered stack trace: {} frames -> {} frames", lines.length, result.size());
        return result;
    }

    /**
     * 检查是否为 Java 内部帧
     */
    private boolean isJavaInternal(String line) {
        for (String pattern : JAVA_INTERNAL_PATTERNS) {
            if (line.matches(".*" + pattern + ".*")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 检查是否为代理/拦截器帧
     */
    private boolean isProxyFrame(String line) {
        for (String pattern : PROXY_PATTERNS) {
            if (line.matches(".*" + pattern + ".*")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 检查是否为 Spring 框架帧
     */
    private boolean isSpringFramework(String line) {
        for (String pattern : SPRING_FRAMEWORK_PATTERNS) {
            if (line.matches(".*" + pattern + ".*")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 检查是否为 Feign/HTTP 客户端帧
     */
    private boolean isFeignClient(String line) {
        for (String pattern : FEIGN_PATTERNS) {
            if (line.matches(".*" + pattern + ".*")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 检查是否为项目源码（文件路径检查）
     */
    private boolean isProjectSource(String line) {
        // 如果未配置项目目录，不过滤
        if (projectDir == null || projectDir.trim().isEmpty()) {
            return true;
        }

        // 提取类名
        String className = extractClassName(line);
        if (className == null || className.isEmpty()) {
            return false;
        }

        // 转换为文件路径：com.example.MyClass -> projectDir/com/example/MyClass.java
        String filePath = toFilePath(className);

        // 检查文件是否存在
        return Files.exists(Paths.get(filePath));
    }

    /**
     * 从堆栈行中提取类名
     */
    private String extractClassName(String line) {
        Matcher matcher = STACK_LINE_PATTERN.matcher(line);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    /**
     * 将类名转换为文件路径
     */
    private String toFilePath(String className) {
        // com.example.MyClass -> com/example/MyClass.java
        String path = className.replace('.', '/') + ".java";
        return new File(projectDir, path).getAbsolutePath();
    }

    /**
     * 解析堆栈行为 StackFrame 对象
     */
    private StackFrame parseStackFrame(String line) {
        Matcher matcher = STACK_LINE_PATTERN.matcher(line);
        if (matcher.find()) {
            StackFrame frame = new StackFrame();
            frame.setClassName(matcher.group(1));
            frame.setMethodName(matcher.group(2));
            frame.setFileName(matcher.group(3));
            try {
                frame.setLineNumber(Integer.parseInt(matcher.group(4)));
            } catch (NumberFormatException e) {
                frame.setLineNumber(-1);
            }
            return frame;
        }
        return null;
    }

    /**
     * 堆栈帧数据类
     */
    @Data
    public static class StackFrame {
        /**
         * 类名（包含包名）
         */
        private String className;

        /**
         * 方法名
         */
        private String methodName;

        /**
         * 文件名
         */
        private String fileName;

        /**
         * 行号
         */
        private Integer lineNumber;

        /**
         * 获取方法位置描述：ClassName.methodName
         */
        public String getLocation() {
            return className + "." + methodName;
        }

        /**
         * 获取完整堆栈描述
         */
        public String toString() {
            return String.format("%s.%s(%s:%d)", className, methodName, fileName, lineNumber);
        }
    }
}