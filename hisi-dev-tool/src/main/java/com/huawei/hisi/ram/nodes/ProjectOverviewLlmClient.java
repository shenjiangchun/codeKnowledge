package com.huawei.hisi.ram.nodes;

import com.huawei.hisi.knowledgegraph.model.BridgeStats;
import com.huawei.hisi.ram.kg.dto.CallTreeNode;
import com.huawei.hisi.ram.kg.dto.Entry;
import com.huawei.hisi.ram.kg.dto.Seed;
import com.huawei.hisi.ram.nodes.impl.RamClaudeJsonClient;
import com.huawei.hisi.ram.sdk.SendOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM client for generating project overview reports.
 *
 * <p>Takes KG-collected data and produces a Markdown report
 * helping new employees understand project structure, core call chains,
 * modules, and technology stack.</p>
 */
@Slf4j
@Component
public class ProjectOverviewLlmClient {

    private static final String SYSTEM_PROMPT = """
            你是一名资深技术导师，负责根据用户问题分析项目代码并生成技术报告。
            你将收到知识图谱（KG）收集的项目数据和用户的具体问题，需要分析并生成一份易懂的Markdown报告。

            你必须返回一个 JSON 对象（不要 prose、不要 markdown fences），结构如下：

            {
              "entry_points_summary": "与用户问题相关的入口点概览",
              "core_call_chains": [
                {
                  "method": "类名#方法名",
                  "description": "调用链描述（需说明与用户问题的关联）",
                  "depth": 3,
                  "key_callees": ["下游方法1", "下游方法2"]
                }
              ],
              "modules_analysis": "模块划分分析（聚焦用户问题相关部分）",
              "tech_stack": {
                "framework": "框架名称",
                "database": "数据库",
                "mq": "消息队列",
                "external_services": ["外部服务1", "外部服务2"]
              },
              "recommendations": [
                {
                  "topic": "建议主题",
                  "detail": "建议详情"
                }
              ],
              "markdown_report": "完整Markdown格式报告（使用\\n换行）"
            }

            核心原则：
            1. **必须聚焦用户问题**：报告内容应直接回答用户的问题，不相关的信息可省略
            2. 使用通俗易懂的语言，避免过多技术术语
            3. 入口点按类型分类，重点说明与问题相关的入口点
            4. 核心调用链选择与问题最相关的，描述其业务含义和与问题的关联
            5. 模块分析聚焦问题涉及的模块
            6. 技术栈识别基于桥接类型推断
            7. 推荐部分指导如何解决问题或深入理解
            8. markdown_report 包含完整报告，结构清晰，直接回答用户问题
            9. **绝对禁止**在 JSON 之外添加任何文本——只输出纯 JSON
            """;

    private final RamClaudeJsonClient claude;

    public ProjectOverviewLlmClient(RamClaudeJsonClient claude) {
        this.claude = claude;
    }

    /**
     * Extract technical keywords from user's natural language question.
     * Converts Chinese business concepts to likely code naming patterns.
     *
     * @param question User's natural language question (e.g., "知识图谱是如何生成的")
     * @return List of technical search terms (e.g., ["KnowledgeGraphBuilder", "scanProject", "parseFile"])
     */
    public List<String> extractKeywords(String question) {
        log.info("[RAM][ProjectOverviewLlmClient] extractKeywords from: {}", question);

        if (question == null || question.isBlank()) {
            return List.of("main handler process service");
        }

        if (!claude.isAvailable()) {
            log.warn("[RAM][ProjectOverviewLlmClient] Claude unavailable — using fallback keywords");
            return fallbackKeywords(question);
        }

        String keywordPrompt = """
                你是一个技术助手，负责从用户的自然语言问题中提取可能用于搜索代码的技术关键词。

                用户问题：%s

                请分析问题，提取可能相关的代码命名模式，返回 JSON 数组格式：
                {
                  "keywords": ["Keyword1", "Keyword2", "Keyword3", "method_pattern", "class_pattern"]
                }

                规则：
                1. 将中文概念转换为可能的英文技术术语（如"知识图谱" → "KnowledgeGraph", "KG", "Graph"）
                2. 提取可能的方法名模式（如"生成" → "generate", "build", "create", "scan", "parse"）
                3. 提取可能的类名模式（如"服务" → "Service", "Handler", "Controller"）
                4. 使用驼峰命名风格（如"KnowledgeGraphBuilder"）
                5. 返回 5-8 个关键词，按相关性排序
                6. 只返回 JSON，不要其他文本
                """.formatted(question);

        try {
            SendOptions opts = new SendOptions(claude.defaultModel(), 1024, 0.3,
                    "你是一个技术关键词提取助手，只返回 JSON 格式结果。");
            Map<String, Object> raw = claude.callJson(
                    "你是一个技术关键词提取助手，只返回 JSON 格式结果。",
                    keywordPrompt, opts);

            if (raw != null && raw.get("keywords") instanceof List<?> list) {
                List<String> keywords = list.stream()
                        .filter(String.class::isInstance)
                        .map(String.class::cast)
                        .toList();
                log.info("[RAM][ProjectOverviewLlmClient] extracted keywords: {}", keywords);
                return keywords.isEmpty() ? fallbackKeywords(question) : keywords;
            }
        } catch (Exception ex) {
            log.warn("[RAM][ProjectOverviewLlmClient] keyword extraction failed: {}", ex.getMessage());
        }

        return fallbackKeywords(question);
    }

    /**
     * Fallback keyword extraction when LLM is unavailable.
     */
    private List<String> fallbackKeywords(String question) {
        // Simple heuristic mapping
        List<String> keywords = new java.util.ArrayList<>();

        // Add common mappings
        if (question.contains("知识图谱")) {
            keywords.add("KnowledgeGraph");
            keywords.add("KG");
            keywords.add("Graph");
        }
        if (question.contains("生成") || question.contains("构建")) {
            keywords.add("generate");
            keywords.add("build");
            keywords.add("create");
        }
        if (question.contains("解析") || question.contains("分析")) {
            keywords.add("parse");
            keywords.add("analyze");
            keywords.add("resolve");
        }
        if (question.contains("服务") || question.contains("接口")) {
            keywords.add("Service");
            keywords.add("Controller");
            keywords.add("Handler");
        }
        if (question.contains("数据库") || question.contains("存储")) {
            keywords.add("Repository");
            keywords.add("Mapper");
            keywords.add("Dao");
        }
        if (question.contains("日志")) {
            keywords.add("Log");
            keywords.add("Logger");
        }

        // Default fallback
        if (keywords.isEmpty()) {
            keywords.add("main");
            keywords.add("process");
            keywords.add("handler");
        }

        return keywords;
    }

    /**
     * Generate project overview report from KG context.
     * If question is provided, the report is customized to answer the question.
     */
    public Map<String, Object> generate(ProjectOverviewNode.ProjectOverviewContext context, String projectPath, String question) {
        log.info("[RAM][ProjectOverviewLlmClient] generate for projectPath={} question={}", projectPath, question);

        if (!claude.isAvailable()) {
            log.warn("[RAM][ProjectOverviewLlmClient] Claude unavailable — returning minimal output");
            return minimalOutput(context, question);
        }

        String userPrompt = buildUserPrompt(context, projectPath, question);

        try {
            SendOptions opts = new SendOptions(claude.defaultModel(), 4096, 0.3, SYSTEM_PROMPT);
            Map<String, Object> raw = claude.callJson(SYSTEM_PROMPT, userPrompt, opts);

            log.info("[RAM][ProjectOverviewLlmClient] Claude returned keys={}",
                    raw == null ? "null" : raw.keySet());

            return normalize(raw, context, question);
        } catch (Exception ex) {
            log.error("[RAM][ProjectOverviewLlmClient] Claude call FAILED: {}", ex.getMessage(), ex);
            return minimalOutput(context, question);
        }
    }

    private String buildUserPrompt(ProjectOverviewNode.ProjectOverviewContext ctx, String projectPath, String question) {
        StringBuilder sb = new StringBuilder();

        sb.append("## 项目路径\n").append(projectPath).append("\n\n");

        // User's question (if provided)
        if (question != null && !question.isBlank()) {
            sb.append("## 用户问题\n").append(question).append("\n\n");
            sb.append("**请分析项目数据，重点回答上述问题。**\n\n");
        }

        sb.append("## 入口点数据\n");
        sb.append("共 ").append(ctx.entryPoints.size()).append(" 个入口点：\n");
        for (Entry e : ctx.entryPoints.stream().limit(20).toList()) {
            sb.append("- ").append(e.type() != null ? e.type() : "UNKNOWN")
              .append(": ").append(e.className() != null ? e.className() : "?")
              .append("#").append(e.methodName() != null ? e.methodName() : "?").append("\n");
        }
        sb.append("\n");

        sb.append("## 桥接统计\n");
        BridgeStats stats = ctx.bridgeStats;
        sb.append("- 总调用关系: ").append(stats.getTotalCallRelations()).append("\n");
        sb.append("- Feign调用: ").append(stats.getFeignCallCount()).append("\n");
        sb.append("- MQ调用: ").append(stats.getMqCallCount()).append("\n");
        sb.append("- Mapper调用: ").append(stats.getMapperCallCount()).append("\n");
        sb.append("- HTTP调用: ").append(stats.getHttpCallCount()).append("\n");
        sb.append("- 外部服务: ").append(stats.getExternalServiceCalls() != null ? stats.getExternalServiceCalls().keySet() : "无").append("\n");
        sb.append("\n");

        sb.append("## 相关方法（根据问题检索）\n");
        sb.append("共 ").append(ctx.coreMethods.size()).append(" 个相关方法候选：\n");
        for (Seed s : ctx.coreMethods.stream().limit(15).toList()) {
            sb.append("- ").append(s.summary() != null ? s.summary() : s.nodeId()).append("\n");
        }
        sb.append("\n");

        sb.append("## 调用链数据\n");
        sb.append("共 ").append(ctx.callChains.size()).append(" 个调用链树：\n");
        for (CallTreeNode tree : ctx.callChains) {
            sb.append("- 根节点: ").append(tree.className() != null ? tree.className() : "?")
              .append("#").append(tree.methodName() != null ? tree.methodName() : "?")
              .append(", 深度=").append(tree.depth())
              .append(", 子节点数=").append(tree.children() != null ? tree.children().size() : 0).append("\n");
            if (tree.children() != null) {
                for (CallTreeNode child : tree.children().stream().limit(5).toList()) {
                    sb.append("  → ").append(child.className() != null ? child.className() : "?")
                      .append("#").append(child.methodName() != null ? child.methodName() : "?").append("\n");
                }
            }
        }
        sb.append("\n");

        if (question != null && !question.isBlank()) {
            sb.append("\n请分析以上数据，生成报告 JSON，**重点回答用户问题**：").append(question).append("\n");
        } else {
            sb.append("\n请分析以上数据，生成项目现状报告 JSON。");
        }

        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> normalize(Map<String, Object> raw, ProjectOverviewNode.ProjectOverviewContext ctx, String question) {
        if (raw == null) return minimalOutput(ctx, question);

        Map<String, Object> out = new LinkedHashMap<>();

        out.put("entry_points_summary", raw.getOrDefault("entry_points_summary", ""));
        out.put("core_call_chains", asList(raw.get("core_call_chains")));
        out.put("modules_analysis", raw.getOrDefault("modules_analysis", ""));
        out.put("tech_stack", raw.get("tech_stack") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of());
        out.put("recommendations", asList(raw.get("recommendations")));
        out.put("markdown_report", raw.getOrDefault("markdown_report", ""));

        return out;
    }

    private Map<String, Object> minimalOutput(ProjectOverviewNode.ProjectOverviewContext ctx, String question) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("entry_points_summary", "共 " + (ctx.entryPoints != null ? ctx.entryPoints.size() : 0) + " 个入口点");
        out.put("core_call_chains", List.of());
        out.put("modules_analysis", "请先生成知识图谱以获取详细模块分析");
        out.put("tech_stack", Map.of());
        String fallbackReport = question != null && !question.isBlank()
                ? "## 问题分析\n\n关于「" + question + "」的分析需要知识图谱数据，请先确保项目已生成知识图谱。"
                : "## 项目现状分析\n\n数据不足，请先生成知识图谱。";
        out.put("recommendations", List.of(
                Map.of("topic", "生成知识图谱", "detail", "运行知识图谱生成以获取完整项目分析")
        ));
        out.put("markdown_report", fallbackReport);
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> asList(Object raw) {
        if (raw instanceof List<?> list) {
            List<Map<String, Object>> out = new java.util.ArrayList<>(list.size());
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    out.add((Map<String, Object>) m);
                }
            }
            return out;
        }
        return List.of();
    }
}