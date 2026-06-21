package com.huawei.hisi.ram.nodes;

import com.huawei.hisi.ram.kg.dto.Bridge;
import com.huawei.hisi.ram.kg.dto.CallTreeNode;
import com.huawei.hisi.ram.kg.dto.Entry;
import com.huawei.hisi.ram.kg.dto.MethodBodyInfo;
import com.huawei.hisi.ram.kg.dto.Seed;
import com.huawei.hisi.ram.model.DomainHint;
import com.huawei.hisi.ram.model.Phase2Context;
import com.huawei.hisi.ram.nodes.impl.RamClaudeJsonClient;
import com.huawei.hisi.ram.sdk.SendOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM client for phase 2 precise location analysis.
 *
 * <p>Takes KG-collected deep data from {@link Phase2Context} and produces
 * a precise location report answering the user's follow-up question.</p>
 *
 * <p>Output JSON schema requirements:</p>
 * <ul>
 *     <li>phase: "phase2_precise_location"</li>
 *     <li>answer_summary: ≤50 chars, must contain method name (regex validation)</li>
 *     <li>call_chains: ≥2 complete chains, each ≥3 nodes</li>
 *     <li>impact_scope: module/interface/data scope annotation</li>
 *     <li>code_references: ≥3 code snippet references</li>
 *     <li>recommendations: ≥2 specific suggestions</li>
 *     <li>confidence.level: high/medium/low/insufficient</li>
 *     <li>kg_coverage: upstream/downstream completeness, code body count</li>
 *     <li>markdown_report: complete Markdown format report</li>
 * </ul>
 */
@Slf4j
@Component
public class Phase2LlmClient {

    // ===== 魔法数字常量定义 =====
    /** Prompt 中展示的最大核心方法数量 */
    private static final int MAX_CORE_METHODS_IN_PROMPT = 20;
    /** Prompt 中展示的最大上游调用者数量 */
    private static final int MAX_UPSTREAM_ENTRIES_IN_PROMPT = 30;
    /** Prompt 中展示的最大下游调用树数量 */
    private static final int MAX_DOWNSTREAM_TREES_IN_PROMPT = 10;
    /** Prompt 中展示的最大入口点数量 */
    private static final int MAX_ROOT_ENTRIES_IN_PROMPT = 20;
    /** Prompt 中展示的最大方法代码体数量 */
    private static final int MAX_METHOD_BODIES_IN_PROMPT = 20;
    /** Prompt 中展示的最大桥接点数量 */
    private static final int MAX_BRIDGE_POINTS_IN_PROMPT = 20;
    /** 调用树每个节点的最大子节点展示数量 */
    private static final int MAX_CALL_TREE_CHILDREN = 5;
    /** Fallback Markdown 报告中的最大条目展示数量 */
    private static final int MAX_FALLBACK_ENTRIES = 10;
    /** Minimal output 中代码引用的最大数量 */
    private static final int MAX_CODE_REFS_IN_MINIMAL_OUTPUT = 3;
    /** 代码片段截断的默认最大行数 */
    private static final int DEFAULT_CODE_TRUNCATE_LINES = 30;
    /** Minimal output 中代码片段截断的最大行数 */
    private static final int MINIMAL_CODE_TRUNCATE_LINES = 10;
    /** LLM 调用最大 token 数 */
    private static final int MAX_TOKENS = 8192;
    /** LLM 调用温度参数 */
    private static final double TEMPERATURE = 0.3;

    private static final String SYSTEM_PROMPT = """
            你是一名资深代码分析师，负责根据用户追问精确定位代码位置并生成技术报告。
            你将收到知识图谱（KG）深度收集的项目数据和用户的具体追问，需要精确定位相关代码并生成一份详细的Markdown报告。

            你必须返回一个 JSON 对象（不要 prose、不要 markdown fences），结构如下：

            {
              "phase": "phase2_precise_location",
              "answer_summary": "方法名是 Xxx#methodName，位于 Xxx.java，执行 Yyy 功能（≤50字）",
              "call_chains": [
                {
                  "chain_id": 1,
                  "direction": "upstream/downstream",
                  "nodes": [
                    {"node_id": "...", "class_name": "ClassA", "method_name": "methodA", "line": 10},
                    {"node_id": "...", "class_name": "ClassB", "method_name": "methodB", "line": 20},
                    {"node_id": "...", "class_name": "ClassC", "method_name": "methodC", "line": 30}
                  ],
                  "description": "调用链描述，必须说明业务含义"
                },
                {
                  "chain_id": 2,
                  ...
                }
              ],
              "impact_scope": {
                "modules": ["affected_module_1", "affected_module_2"],
                "interfaces": ["InterfaceA#methodX"],
                "data_impact": ["TableX.columnY", "DTO.fieldZ"]
              },
              "code_references": [
                {
                  "node_id": "...",
                  "file_path": "path/to/File.java",
                  "method_name": "methodName",
                  "snippet": "关键代码片段（≤10行）",
                  "relevance": "为什么相关"
                },
                ...
              ],
              "recommendations": [
                {
                  "sequence": 1,
                  "action": "建议动作（如：修改、检查、重构）",
                  "target": "具体目标（类名#方法名）",
                  "reason": "原因说明"
                },
                {
                  "sequence": 2,
                  ...
                }
              ],
              "confidence": {
                "level": "high/medium/low/insufficient",
                "kg_coverage": {
                  "upstream_complete": true/false,
                  "downstream_complete": true/false,
                  "code_bodies_loaded": 5,
                  "missing_info": ["缺失信息说明"]
                },
                "limitations": ["限制说明"]
              },
              "markdown_report": "完整Markdown格式报告（使用\\n换行）"
            }

            核心原则：
            1. **禁止模糊词汇**：不要使用"可能"、"大概"、"似乎"、"若干"、"某些"
            2. **必须引用具体信息**：每个字段必须引用具体 nodeId、方法签名（ClassName#methodName）、文件路径
            3. **最小信息量要求**：
               - answer_summary 必须包含至少一个方法名（正则校验）
               - call_chains 必须至少 2 条完整链，每链至少 3 节点
               - code_references 必须至少 3 个代码片段引用
               - recommendations 必须至少 2 条具体建议
            4. **KG 数据缺失时明确标注**：在 missing_info 中说明缺失的数据类型
            5. **impact_scope 必须标注**：模块、接口、数据影响范围
            6. **markdown_report 包含完整报告**：结构清晰，直接回答用户追问
            7. **绝对禁止**在 JSON 之外添加任何文本——只输出纯 JSON
            """;

    private final RamClaudeJsonClient claude;

    public Phase2LlmClient(RamClaudeJsonClient claude) {
        this.claude = claude;
    }

    /**
     * Generate phase 2 precise location report from KG context.
     *
     * @param context    the phase 2 context containing all KG-collected data
     * @param projectPath the project path being analyzed
     * @return a map containing the analysis result following the JSON schema
     */
    public Map<String, Object> generate(Phase2Context context, String projectPath) {
        log.info("[RAM][Phase2LlmClient] generate for projectPath={} question={}",
                projectPath, context.question());

        if (!claude.isAvailable()) {
            log.warn("[RAM][Phase2LlmClient] Claude unavailable — returning minimal output");
            return minimalOutput(context);
        }

        String userPrompt = buildUserPrompt(context, projectPath);

        try {
            SendOptions opts = new SendOptions(claude.defaultModel(), MAX_TOKENS, TEMPERATURE, SYSTEM_PROMPT);
            Map<String, Object> raw = claude.callJson(SYSTEM_PROMPT, userPrompt, opts);

            log.info("[RAM][Phase2LlmClient] Claude returned keys={}",
                    raw == null ? "null" : raw.keySet());

            return normalize(raw, context);
        } catch (Exception ex) {
            log.error("[RAM][Phase2LlmClient] Claude call FAILED: {}", ex.getMessage(), ex);
            return minimalOutput(context);
        }
    }

    /**
     * Build user prompt with all KG-collected data injection.
     */
    private String buildUserPrompt(Phase2Context ctx, String projectPath) {
        StringBuilder sb = new StringBuilder();

        sb.append("## 项目路径\n").append(projectPath).append("\n\n");

        // User's question
        sb.append("## 用户追问\n").append(ctx.question()).append("\n\n");
        sb.append("**请精确定位相关代码位置，重点回答上述追问。**\n\n");

        // Domain hint
        DomainHint hint = ctx.domainHint();
        sb.append("## 领域推断\n");
        sb.append("- 分析类型: ").append(hint.analysisType()).append("\n");
        sb.append("- 推荐工具: ").append(hint.primaryTools()).append("\n");
        sb.append("- 调用树方向: ").append(hint.treeDirection()).append("\n");
        sb.append("- 关注桥接点: ").append(hint.focusOnBridges()).append("\n\n");

        // Keywords extracted
        sb.append("## 关键词提取\n");
        sb.append("从用户追问中提取的关键词: ").append(ctx.keywords()).append("\n\n");

        // Core methods (seed nodes from hybrid search)
        sb.append("## 核心方法候选\n");
        sb.append("共 ").append(ctx.coreMethods().size()).append(" 个候选方法：\n");
        for (Seed seed : ctx.coreMethods().stream().limit(MAX_CORE_METHODS_IN_PROMPT).toList()) {
            sb.append("- nodeId: ").append(seed.nodeId()).append("\n");
            sb.append("  summary: ").append(orMissing(seed.summary())).append("\n");
            sb.append("  score: ").append(seed.score()).append("\n");
        }
        sb.append("\n");

        // Upstream chains (affecting callers)
        sb.append("## 上游调用链\n");
        List<Entry> upstream = ctx.upstreamChains();
        sb.append("共 ").append(upstream.size()).append(" 个上游调用者：\n");
        for (Entry e : upstream.stream().limit(MAX_UPSTREAM_ENTRIES_IN_PROMPT).toList()) {
            sb.append("- nodeId: ").append(e.nodeId()).append("\n");
            sb.append("  ").append(orMissing(e.className()))
              .append("#").append(orMissing(e.methodName())).append("\n");
            sb.append("  type: ").append(orMissing(e.type())).append("\n");
        }
        sb.append("\n");

        // Downstream chains (callees tree)
        sb.append("## 下游调用链树\n");
        List<CallTreeNode> downstream = ctx.downstreamChains();
        sb.append("共 ").append(downstream.size()).append(" 个下游调用树：\n");
        for (CallTreeNode tree : downstream.stream().limit(MAX_DOWNSTREAM_TREES_IN_PROMPT).toList()) {
            appendCallTree(sb, tree, 0);
        }
        sb.append("\n");

        // Root entries (entry points)
        sb.append("## 入口点溯源\n");
        List<Entry> roots = ctx.rootEntries();
        sb.append("共 ").append(roots.size()).append(" 个根入口点：\n");
        for (Entry e : roots.stream().limit(MAX_ROOT_ENTRIES_IN_PROMPT).toList()) {
            sb.append("- nodeId: ").append(e.nodeId()).append("\n");
            sb.append("  ").append(orMissing(e.className()))
              .append("#").append(orMissing(e.methodName())).append("\n");
            sb.append("  type: ").append(orMissing(e.type())).append("\n");
        }
        sb.append("\n");

        // Method bodies (code snippets)
        sb.append("## 方法代码体\n");
        List<MethodBodyInfo> bodies = ctx.methodBodies();
        sb.append("共 ").append(bodies.size()).append(" 个方法代码体已加载：\n");
        for (MethodBodyInfo body : bodies.stream().limit(MAX_METHOD_BODIES_IN_PROMPT).toList()) {
            sb.append("- nodeId: ").append(body.nodeId()).append("\n");
            sb.append("  ").append(orMissing(body.className()))
              .append("#").append(orMissing(body.methodName())).append("\n");
            sb.append("  file: ").append(orMissing(body.filePath())).append("\n");
            sb.append("  description: ").append(orMissing(body.description())).append("\n");
            sb.append("  code:\n");
            sb.append("```java\n");
            sb.append(body.methodBody() != null
                    ? truncateCode(body.methodBody(), DEFAULT_CODE_TRUNCATE_LINES)
                    : MISSING_INFO);
            sb.append("\n```\n");
        }
        sb.append("\n");

        // Bridge points (Feign/MQ/Mapper)
        sb.append("## 桥接点\n");
        List<Bridge> bridges = ctx.bridgePoints();
        sb.append("共 ").append(bridges.size()).append(" 个桥接点（Feign/MQ/Mapper）：\n");
        for (Bridge b : bridges.stream().limit(MAX_BRIDGE_POINTS_IN_PROMPT).toList()) {
            sb.append("- nodeId: ").append(b.nodeId()).append("\n");
            sb.append("  bridgeType: ").append(orMissing(b.bridgeType())).append("\n");
            sb.append("  target: ").append(orMissing(b.target())).append("\n");
        }
        sb.append("\n");

        // KG coverage summary
        sb.append("## KG 数据覆盖统计\n");
        sb.append("- 核心方法候选数: ").append(ctx.coreMethods().size()).append("\n");
        sb.append("- 上游调用链数: ").append(upstream.size()).append("\n");
        sb.append("- 下游调用树数: ").append(downstream.size()).append("\n");
        sb.append("- 入口点数: ").append(roots.size()).append("\n");
        sb.append("- 代码体加载数: ").append(bodies.size()).append("\n");
        sb.append("- 桥接点数: ").append(bridges.size()).append("\n");
        sb.append("\n");

        sb.append("请分析以上 KG 数据，精确定位代码位置，生成符合 JSON Schema 的报告。\n");
        sb.append("如果某些数据缺失或不足，请在 missing_info 中明确标注。\n");

        return sb.toString();
    }

    /**
     * Append a call tree node and its children recursively.
     */
    private void appendCallTree(StringBuilder sb, CallTreeNode node, int depth) {
        String indent = "  ".repeat(depth);
        sb.append(indent).append("- nodeId: ").append(node.nodeId()).append("\n");
        sb.append(indent).append("  ")
          .append(orMissing(node.className()))
          .append("#").append(orMissing(node.methodName()))
          .append(", depth=").append(node.depth()).append("\n");

        if (node.children() != null && !node.children().isEmpty()) {
            for (CallTreeNode child : node.children().stream().limit(MAX_CALL_TREE_CHILDREN).toList()) {
                appendCallTree(sb, child, depth + 1);
            }
        }
    }

    /**
     * Truncate code snippet to specified max lines.
     */
    private String truncateCode(String code, int maxLines) {
        if (code == null) return MISSING_INFO;
        String[] lines = code.split("\n");
        if (lines.length <= maxLines) {
            return code;
        }
        StringBuilder truncated = new StringBuilder();
        for (int i = 0; i < maxLines; i++) {
            truncated.append(lines[i]).append("\n");
        }
        truncated.append("// ... truncated (").append(lines.length).append(" lines total)");
        return truncated.toString();
    }

    /**
     * Null-safe value handler: returns value if non-blank, else "missing_info".
     */
    private static final String MISSING_INFO = "missing_info";

    private String orMissing(String value) {
        return value != null && !value.isBlank() ? value : MISSING_INFO;
    }

    private String orMissing(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }

    /**
     * Normalize raw LLM response to ensure schema compliance.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> normalize(Map<String, Object> raw, Phase2Context ctx) {
        if (raw == null) return minimalOutput(ctx);

        Map<String, Object> out = new LinkedHashMap<>();

        // phase
        out.put("phase", "phase2_precise_location");

        // answer_summary (must contain method name pattern like ClassName#methodName)
        String summary = String.valueOf(raw.getOrDefault("answer_summary", ""));
        if (!summary.matches(".*#\\w+.*")) {
            // Fallback: extract from coreMethods
            summary = extractFallbackSummary(ctx);
        }
        out.put("answer_summary", summary.length() <= 50 ? summary : summary.substring(0, 50));

        // call_chains
        out.put("call_chains", asList(raw.get("call_chains")));

        // impact_scope
        Object impactScope = raw.get("impact_scope");
        if (impactScope instanceof Map<?, ?> m) {
            out.put("impact_scope", (Map<String, Object>) m);
        } else {
            out.put("impact_scope", Map.of(
                    "modules", List.of(),
                    "interfaces", List.of(),
                    "data_impact", List.of()
            ));
        }

        // code_references
        out.put("code_references", asList(raw.get("code_references")));

        // recommendations
        out.put("recommendations", asList(raw.get("recommendations")));

        // confidence
        Object confidence = raw.get("confidence");
        if (confidence instanceof Map<?, ?> m) {
            out.put("confidence", (Map<String, Object>) m);
        } else {
            out.put("confidence", Map.of(
                    "level", "medium",
                    "kg_coverage", Map.of(
                            "upstream_complete", ctx.upstreamChains().size() > 0,
                            "downstream_complete", ctx.downstreamChains().size() > 0,
                            "code_bodies_loaded", ctx.methodBodies().size(),
                            "missing_info", List.of()
                    ),
                    "limitations", List.of("LLM未返回confidence字段")
            ));
        }

        // markdown_report
        out.put("markdown_report", raw.getOrDefault("markdown_report", ""));

        return out;
    }

    /**
     * Extract fallback answer_summary from coreMethods if LLM response doesn't match pattern.
     */
    private String extractFallbackSummary(Phase2Context ctx) {
        if (ctx.coreMethods().isEmpty()) {
            return "未找到相关方法，请检查知识图谱数据";
        }
        Seed first = ctx.coreMethods().get(0);
        String nodeId = first.nodeId();
        // Extract className#methodName from nodeId (format: path:className.methodName.hash)
        String[] parts = nodeId.split(":");
        if (parts.length >= 2) {
            String classMethod = parts[parts.length - 1];
            int lastDot = classMethod.lastIndexOf('.');
            if (lastDot > 0) {
                int secondLastDot = classMethod.lastIndexOf('.', lastDot - 1);
                if (secondLastDot > 0) {
                    String className = classMethod.substring(0, secondLastDot);
                    String methodName = classMethod.substring(secondLastDot + 1, lastDot);
                    return className + "#" + methodName + "（知识图谱定位）";
                }
            }
        }
        return "相关方法已定位（score=" + first.score() + ")";
    }

    /**
     * Minimal output when LLM is unavailable.
     */
    private Map<String, Object> minimalOutput(Phase2Context ctx) {
        Map<String, Object> out = new LinkedHashMap<>();

        out.put("phase", "phase2_precise_location");
        out.put("answer_summary", extractFallbackSummary(ctx));

        // Build minimal call_chains from upstream/downstream
        List<Map<String, Object>> callChains = new java.util.ArrayList<>();

        // Upstream chain from rootEntries
        if (!ctx.rootEntries().isEmpty()) {
            Entry root = ctx.rootEntries().get(0);
            Map<String, Object> chain = new LinkedHashMap<>();
            chain.put("chain_id", 1);
            chain.put("direction", "upstream");
            chain.put("nodes", List.of(
                    Map.of("node_id", root.nodeId(),
                           "class_name", orMissing(root.className()),
                           "method_name", orMissing(root.methodName()))
            ));
            chain.put("description", "入口点溯源（LLM不可用，原始KG数据）");
            callChains.add(chain);
        }

        // Downstream chain from coreMethods
        if (!ctx.coreMethods().isEmpty()) {
            Seed seed = ctx.coreMethods().get(0);
            Map<String, Object> chain = new LinkedHashMap<>();
            chain.put("chain_id", 2);
            chain.put("direction", "downstream");
            chain.put("nodes", List.of(
                    Map.of("node_id", seed.nodeId(),
                           "class_name", extractClassName(seed.nodeId()),
                           "method_name", extractMethodName(seed.nodeId()))
            ));
            chain.put("description", "核心方法（LLM不可用，原始KG数据）");
            callChains.add(chain);
        }

        out.put("call_chains", callChains);

        // Impact scope from bridge points
        List<String> modules = ctx.bridgePoints().stream()
                .map(b -> b.bridgeType())
                .filter(t -> t != null)
                .distinct()
                .toList();
        out.put("impact_scope", Map.of(
                "modules", modules.isEmpty() ? List.of("unknown") : modules,
                "interfaces", List.of(),
                "data_impact", List.of()
        ));

        // Code references from method bodies
        List<Map<String, Object>> codeRefs = new java.util.ArrayList<>();
        for (MethodBodyInfo body : ctx.methodBodies().stream().limit(MAX_CODE_REFS_IN_MINIMAL_OUTPUT).toList()) {
            Map<String, Object> ref = new LinkedHashMap<>();
            ref.put("node_id", body.nodeId());
            ref.put("file_path", orMissing(body.filePath()));
            ref.put("method_name", orMissing(body.methodName()));
            ref.put("snippet", truncateCode(body.methodBody(), MINIMAL_CODE_TRUNCATE_LINES));
            ref.put("relevance", "知识图谱加载的代码体");
            codeRefs.add(ref);
        }
        out.put("code_references", codeRefs);

        // Minimal recommendations
        out.put("recommendations", List.of(
                Map.of("sequence", 1, "action", "检查知识图谱", "target", "N/A",
                       "reason", "LLM不可用，请检查知识图谱服务状态"),
                Map.of("sequence", 2, "action", "人工分析", "target", "N/A",
                       "reason", "建议手动分析KG数据以定位问题")
        ));

        // Confidence with missing info
        out.put("confidence", Map.of(
                "level", "insufficient",
                "kg_coverage", Map.of(
                        "upstream_complete", ctx.upstreamChains().size() > 0,
                        "downstream_complete", ctx.downstreamChains().size() > 0,
                        "code_bodies_loaded", ctx.methodBodies().size(),
                        "missing_info", List.of("LLM服务不可用")
                ),
                "limitations", List.of("无法进行智能分析，仅返回原始KG数据")
        ));

        // Markdown report
        String markdownReport = buildFallbackMarkdownReport(ctx);
        out.put("markdown_report", markdownReport);

        return out;
    }

    /**
     * Build fallback Markdown report when LLM is unavailable.
     */
    private String buildFallbackMarkdownReport(Phase2Context ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 精确位置分析报告\n\n");
        sb.append("### 用户追问\n").append(ctx.question()).append("\n\n");
        sb.append("### 状态说明\n").append("LLM服务不可用，以下为原始知识图谱数据。\n\n");

        sb.append("### 核心方法候选\n");
        if (ctx.coreMethods().isEmpty()) {
            sb.append("无匹配方法。\n");
        } else {
            sb.append("| nodeId | summary | score |\n");
            sb.append("|--------|---------|-------|\n");
            for (Seed s : ctx.coreMethods().stream().limit(MAX_FALLBACK_ENTRIES).toList()) {
                sb.append("| ").append(s.nodeId()).append(" | ")
                  .append(orMissing(s.summary(), "N/A")).append(" | ")
                  .append(s.score()).append(" |\n");
            }
        }
        sb.append("\n");

        sb.append("### 入口点溯源\n");
        if (ctx.rootEntries().isEmpty()) {
            sb.append("无入口点数据。\n");
        } else {
            for (Entry e : ctx.rootEntries().stream().limit(MAX_FALLBACK_ENTRIES).toList()) {
                sb.append("- **").append(orMissing(e.type(), "UNKNOWN")).append("**: ")
                  .append(orMissing(e.className(), "?")).append("#")
                  .append(orMissing(e.methodName(), "?")).append("\n");
            }
        }
        sb.append("\n");

        sb.append("### 建议\n");
        sb.append("1. 检查 LLM 服务配置（API Key 是否有效）\n");
        sb.append("2. 确保知识图谱数据已正确生成\n");
        sb.append("3. 手动分析上述 KG 数据以定位问题\n");

        return sb.toString();
    }

    /**
     * Extract className from nodeId.
     */
    private String extractClassName(String nodeId) {
        if (nodeId == null) return "missing_info";
        String[] parts = nodeId.split(":");
        if (parts.length < 2) return "missing_info";
        String classMethod = parts[parts.length - 1];
        int lastDot = classMethod.lastIndexOf('.');
        if (lastDot < 0) return "missing_info";
        int secondLastDot = classMethod.lastIndexOf('.', lastDot - 1);
        if (secondLastDot < 0) return classMethod.substring(0, lastDot);
        return classMethod.substring(0, secondLastDot);
    }

    /**
     * Extract methodName from nodeId.
     */
    private String extractMethodName(String nodeId) {
        if (nodeId == null) return "missing_info";
        String[] parts = nodeId.split(":");
        if (parts.length < 2) return "missing_info";
        String classMethod = parts[parts.length - 1];
        int lastDot = classMethod.lastIndexOf('.');
        if (lastDot < 0) return "missing_info";
        int secondLastDot = classMethod.lastIndexOf('.', lastDot - 1);
        if (secondLastDot < 0) return classMethod.substring(lastDot + 1);
        return classMethod.substring(secondLastDot + 1, lastDot);
    }

    /**
     * Convert raw list to List<Map>.
     */
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