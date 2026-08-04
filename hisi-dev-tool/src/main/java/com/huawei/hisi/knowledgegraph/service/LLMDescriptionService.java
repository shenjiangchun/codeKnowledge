package com.huawei.hisi.knowledgegraph.service;

import com.huawei.hisi.glossary.model.GlossaryTerm;
import com.huawei.hisi.glossary.repository.GlossaryTermRepository;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private final GlossaryTermRepository glossaryTermRepository;

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
    public LLMDescriptionService(UnifiedTextService textService,
                                  GlossaryTermRepository glossaryTermRepository) {
        this.textService = textService;
        this.glossaryTermRepository = glossaryTermRepository;
    }

    /**
     * Prompt 模板格式（旧版，仅包含签名和注释）
     * 保留向后兼容
     */
    private static final String PROMPT_TEMPLATE = """
            请用一句话描述以下Java方法的功能（50字以内）：
            类名：{{className}}
            方法名：{{methodName}}
            签名：{{signature}}
            注释：{{comment}}
            """;

    /**
     * 新版 Prompt 模板格式（一致性校验 + 方法体）
     * 用于生成更准确的语义描述
     */
    private static final String PROMPT_TEMPLATE_WITH_BODY = """
你是专业的代码语义解析专家，请按以下逻辑生成方法描述：

## 一致性校验规则
1. 先分析方法体的实际行为（核心流程、数据流、返回值）
2. 对比注释和方法名是否与方法体行为一致：
   - 一致：优先采用注释或方法名（精简为自然语言）
   - 不一致：以方法体实际行为为准，忽略误导性注释
3. 若注释无实质内容（如"TODO"、"默认方法"），直接从方法体推断

## 输入
类名：{{className}}
方法名：{{methodName}}
签名：{{signature}}
注释：{{comment}}
方法体：
{{methodBody}}

## 输出要求
- 50字以内，精准描述实际功能
- 必须使用术语表中的术语
- 不要输出代码或实现细节

## 术语规范
{{glossary}}

直接输出描述，无额外内容。
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
            String glossarySegment = buildGlossarySegment(node.getProjectPath());
            String prompt = buildPrompt(
                    node.getClassName(),
                    node.getMethodName(),
                    node.getSignature(),
                    comment != null ? comment : node.getComment()
            ) + glossarySegment;
            String description = textService.generateText(prompt);
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
        return PROMPT_TEMPLATE
            .replace("{{className}}", className)
            .replace("{{methodName}}", methodName)
            .replace("{{signature}}", signature)
            .replace("{{comment}}", commentStr);
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
                                              String comment, String methodBody, String glossary) {
        String commentStr = (comment == null || comment.isEmpty()) ? "无" : comment;
        String bodyStr = (methodBody == null || methodBody.isEmpty()) ? "无" : truncateMethodBody(methodBody);
        String glossaryStr = (glossary == null || glossary.isEmpty()) ? "" : glossary;
        return PROMPT_TEMPLATE_WITH_BODY
            .replace("{{className}}", className)
            .replace("{{methodName}}", methodName)
            .replace("{{signature}}", signature)
            .replace("{{comment}}", commentStr)
            .replace("{{methodBody}}", bodyStr)
            .replace("{{glossary}}", glossaryStr);
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
                    node.getMethodBody(),
                    buildGlossarySegment(node.getProjectPath())
            );
            String description = textService.generateText(prompt);
            fileLog(methodId + " 文本模型生成成功（含方法体）: " + description);
            return description;
        } catch (Exception e) {
            fileLog("[ERROR] " + methodId + " 文本模型生成失败（含方法体）: " + e.getMessage());
            throw new RuntimeException("LLM描述生成失败（含方法体）: " + e.getMessage(), e);
        }
    }

    /**
     * 批量生成方法描述。
     * 将多个 MethodNode 转为 Map 列表后委托 UnifiedTextService.generateDescriptionsBatch。
     *
     * @param nodes 方法节点列表
     * @return 与输入顺序一致的描述列表
     */
    public List<String> generateDescriptionsBatch(List<MethodNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return List.of();
        }
        initFileLogger();

        List<Map<String, String>> methods = new ArrayList<>(nodes.size());
        for (MethodNode node : nodes) {
            Map<String, String> m = new HashMap<>();
            m.put("className", node.getClassName());
            m.put("methodName", node.getMethodName());
            m.put("signature", node.getSignature() != null ? node.getSignature() : "");
            m.put("comment", node.getComment() != null ? node.getComment() : "无");
            m.put("methodBody", node.getMethodBody() != null ? node.getMethodBody() : "无");
            methods.add(m);
        }

        String glossary = nodes.isEmpty() ? "" : buildGlossarySegment(nodes.get(0).getProjectPath());

        try {
            return textService.generateDescriptionsBatch(methods, glossary);
        } catch (Exception e) {
            fileLog("[ERROR] 批量描述生成失败: " + e.getMessage() + ", 降级单条");
            // 降级：逐条生成
            List<String> results = new ArrayList<>(nodes.size());
            for (MethodNode node : nodes) {
                try {
                    results.add(generateDescriptionWithBody(node));
                } catch (Exception ex) {
                    results.add(node.getClassName() + "." + node.getMethodName() + " - " + node.getSignature());
                    fileLog("[ERROR] 单条降级也失败: " + node.getClassName() + "." + node.getMethodName());
                }
            }
            return results;
        }
    }

    private String buildGlossarySegment(String projectPath) {
        if (projectPath == null || projectPath.isBlank()) {
            return "";
        }
        List<GlossaryTerm> terms = glossaryTermRepository.findByProjectPath(projectPath);
        if (terms.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n\n## 术语规范（必须严格遵守）\n");
        for (GlossaryTerm t : terms) {
            sb.append("- 「").append(t.getTerm()).append("」");
            if (t.getSynonym() != null && !t.getSynonym().isBlank()) {
                sb.append("（同义词：").append(t.getSynonym()).append("）");
            }
            sb.append("：请统一使用「").append(t.getTerm()).append("」");
            if (t.getContext() != null && !t.getContext().isBlank()) {
                sb.append("（").append(t.getContext()).append("）");
            }
            sb.append("\n");
        }
        return sb.toString();
    }
}
