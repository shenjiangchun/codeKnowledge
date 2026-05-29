package com.huawei.hisi.knowledgegraph.service;

import com.huawei.hisi.neo4j.model.MethodNode;
import com.huawei.hisi.service.UnifiedTextService;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * LLM 描述生成服务
 * 使用大语言模型为方法生成自然语言描述
 *
 * 支持两种描述生成模式：
 * 1. 基于方法签名和注释的简要描述（旧版，向后兼容）
 * 2. 基于方法体内容的详细描述（新版，用于多向量搜索）
 */
@Slf4j
@Service
public class LLMDescriptionService {

    private final UnifiedTextService textService;

    // 文件日志
    private PrintWriter fileLogger;
    private static final String LOG_DIR = "logs/llm";
    private static final String LOG_FILE = "llm-description-service.log";

    private boolean loggerInitialized = false;

    /**
     * 方法体最大字符数，超过此限制会截取
     */
    private static final int MAX_METHOD_BODY_LENGTH = 2000;

    @Autowired
    public LLMDescriptionService(UnifiedTextService textService) {
        this.textService = textService;
    }

    /**
     * Prompt 模板格式（旧版，仅包含签名和注释）
     * 保留向后兼容
     */
    private static final String PROMPT_TEMPLATE = """
            请用一句话描述以下Java方法的功能（50字以内）：
            类名：%s
            方法名：%s
            签名：%s
            注释：%s
            """;

    /**
     * 新版 Prompt 模板格式（包含方法体）
     * 用于生成更准确的语义描述
     */
    private static final String PROMPT_TEMPLATE_WITH_BODY = """
            你是专业的Java代码语义解析专家，请用简洁精准的中文描述下面Java方法的核心功能、业务意图，不超过50字，不要输出代码。

            类名：%s
            方法名：%s
            签名：%s
            注释：%s
            方法体：
            %s

            请直接输出描述，不要有任何额外内容。
            """;

    /**
     * 初始化文件日志
     */
    private synchronized void initFileLogger() {
        if (loggerInitialized) return;

        try {
            Path logDir = Paths.get(LOG_DIR);
            if (!java.nio.file.Files.exists(logDir)) {
                java.nio.file.Files.createDirectories(logDir);
            }
            Path logFile = logDir.resolve(LOG_FILE);
            fileLogger = new PrintWriter(new FileWriter(logFile.toFile(), true), true);
            loggerInitialized = true;
            fileLog("========== 日志系统初始化完成 ==========");
        } catch (IOException e) {
            log.error("无法初始化文件日志: {}", e.getMessage());
            throw new RuntimeException("无法初始化文件日志", e);
        }
    }

    private void fileLog(String message) {
        if (!loggerInitialized) {
            initFileLogger();
        }
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"));
        String logLine = "[" + timestamp + "] " + message;
        if (fileLogger != null) {
            fileLogger.println(logLine);
            fileLogger.flush();
        }
        log.info(message);
    }

    /**
     * 为方法节点生成描述
     *
     * @param node 方法节点
     * @return 生成的描述
     * @throws RuntimeException 如果生成失败
     */
    public String generateDescription(MethodNode node) {
        return generateDescription(node, null);
    }

    /**
     * 为方法节点生成描述
     *
     * @param node    方法节点
     * @param comment 方法注释（可选）
     * @return 生成的描述
     * @throws RuntimeException 如果生成失败
     */
    public String generateDescription(MethodNode node, String comment) {
        if (node == null) {
            throw new IllegalArgumentException("MethodNode 不能为 null");
        }

        initFileLogger();

        String methodId = "[LLM-" + node.getClassName() + "." + node.getMethodName() + "]";
        fileLog(methodId + " 开始生成描述");

        if (!textService.isAvailable()) {
            throw new RuntimeException("文本生成服务不可用，请检查 text-model 配置");
        }

        try {
            String description = textService.generateDescription(
                    node.getClassName(),
                    node.getMethodName(),
                    node.getSignature(),
                    comment != null ? comment : node.getComment()
            );
            fileLog(methodId + " 文本模型生成成功: " + description);
            return description;
        } catch (Exception e) {
            fileLog("[ERROR] " + methodId + " 文本模型生成失败: " + e.getMessage());
            throw new RuntimeException("LLM描述生成失败: " + e.getMessage(), e);
        }
    }

    /**
     * 批量生成方法描述
     *
     * @param nodes 方法节点列表
     * @return 生成的描述列表
     */
    public List<String> batchGenerateDescriptions(List<MethodNode> nodes) {
        if (nodes == null) {
            throw new IllegalArgumentException("节点列表不能为 null");
        }

        List<String> descriptions = new ArrayList<>();
        for (MethodNode node : nodes) {
            String description = generateDescription(node);
            descriptions.add(description);
            node.setDescription(description);
        }
        return descriptions;
    }

    /**
     * 构建 Prompt（旧版，仅包含签名和注释）
     */
    public static String buildPrompt(String className, String methodName, String signature, String comment) {
        String commentStr = (comment == null || comment.isEmpty()) ? "无" : comment;
        return String.format(PROMPT_TEMPLATE, className, methodName, signature, commentStr);
    }

    /**
     * 构建 Prompt（新版，包含方法体）
     *
     * @param className  类名
     * @param methodName 方法名
     * @param signature  方法签名
     * @param comment    方法注释
     * @param methodBody 方法体内容（超过2000字符会自动截取）
     * @return 构建好的 Prompt
     */
    public static String buildPromptWithBody(String className, String methodName, String signature,
                                              String comment, String methodBody) {
        String commentStr = (comment == null || comment.isEmpty()) ? "无" : comment;
        String bodyStr = (methodBody == null || methodBody.isEmpty()) ? "无" : truncateMethodBody(methodBody);
        return String.format(PROMPT_TEMPLATE_WITH_BODY, className, methodName, signature, commentStr, bodyStr);
    }

    /**
     * 截取方法体内容
     * 超过 MAX_METHOD_BODY_LENGTH 字符时截取前 N 个字符
     *
     * @param methodBody 方法体原文
     * @return 截取后的方法体
     */
    public static String truncateMethodBody(String methodBody) {
        if (methodBody == null || methodBody.isEmpty()) {
            return "无";
        }
        if (methodBody.length() <= MAX_METHOD_BODY_LENGTH) {
            return methodBody;
        }
        return methodBody.substring(0, MAX_METHOD_BODY_LENGTH) + "\n... (方法体过长，已截取前" + MAX_METHOD_BODY_LENGTH + "字符)";
    }

    /**
     * 为方法节点生成描述（包含方法体，用于多向量搜索）
     * 使用 PROMPT_TEMPLATE_WITH_BODY 模板，提供更准确的语义描述
     *
     * @param node 方法节点
     * @return 生成的描述
     * @throws RuntimeException 如果生成失败
     */
    public String generateDescriptionWithBody(MethodNode node) {
        if (node == null) {
            throw new IllegalArgumentException("MethodNode 不能为 null");
        }

        initFileLogger();

        String methodId = "[LLM-WithBody-" + node.getClassName() + "." + node.getMethodName() + "]";
        fileLog(methodId + " 开始生成描述（含方法体）");

        if (!textService.isAvailable()) {
            throw new RuntimeException("文本生成服务不可用，请检查 text-model 配置");
        }

        try {
            String prompt = buildPromptWithBody(
                    node.getClassName(),
                    node.getMethodName(),
                    node.getSignature(),
                    node.getComment(),
                    node.getMethodBody()
            );
            String description = textService.generateText(prompt);
            fileLog(methodId + " 文本模型生成成功（含方法体）: " + description);
            return description;
        } catch (Exception e) {
            fileLog("[ERROR] " + methodId + " 文本模型生成失败（含方法体）: " + e.getMessage());
            throw new RuntimeException("LLM描述生成失败（含方法体）: " + e.getMessage(), e);
        }
    }
}
