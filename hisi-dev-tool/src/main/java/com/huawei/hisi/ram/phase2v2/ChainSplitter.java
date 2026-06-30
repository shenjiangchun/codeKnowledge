// hisi-dev-tool/src/main/java/com/huawei/hisi/ram/phase2v2/ChainSplitter.java
package com.huawei.hisi.ram.phase2v2;

import com.huawei.hisi.ram.kg.dto.Entry;
import com.huawei.hisi.ram.phase2v2.model.ChainComplexity;
import com.huawei.hisi.ram.phase2v2.model.ChainContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 链路拆分器：根据 KG entryPoints 和用户问题拆分独立链路。
 *
 * 支持两种追问模式：
 * - 延伸型追问（"细说"、"展开"等）：继承 Phase1 top-N entryPoints
 * - 搜索型追问（"订单流程"等）：关键词过滤匹配
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChainSplitter {

    private final DynamicToolRegistry toolRegistry;

    private static final Set<String> FOLLOW_UP_PHRASES = Set.of(
            "细说", "展开", "详细", "深入", "详述", "详析",
            "继续", "再分析", "再看看", "说说", "讲讲");

    private static final int DEFAULT_TOP_N = 10;

    /**
     * 将 entryPoints 拆分为独立 ChainContext。
     *
     * @param entries       入口点列表
     * @param question      用户问题
     * @param projectPath   项目路径
     * @param parentSessionId 父会话 ID
     * @param inheritedData Phase1 继承数据（可选，追问时用于 top-N 选择）
     * @return 拆分后的链路上下文列表
     */
    public List<ChainContext> split(
            List<Entry> entries,
            String question,
            String projectPath,
            String parentSessionId,
            ChainContext.Phase1InheritedData inheritedData) {

        if (entries == null || entries.isEmpty()) {
            return List.of();
        }

        List<Entry> selectedEntries;
        boolean isFollowUp = isFollowUpQuestion(question);

        if (isFollowUp) {
            // 延伸型追问：使用 Phase1 继承的 top-N entryPoints
            List<Entry> sourceEntries = (inheritedData != null && inheritedData.entryPoints() != null
                    && !inheritedData.entryPoints().isEmpty())
                    ? inheritedData.entryPoints()
                    : entries;
            selectedEntries = selectTopN(sourceEntries, DEFAULT_TOP_N);
            log.info("[ChainSplitter] Follow-up question detected, using top-{} entries from {}",
                    selectedEntries.size(),
                    inheritedData != null && inheritedData.entryPoints() != null ? "Phase1" : "fallback");
        } else {
            // 搜索型追问：关键词过滤
            List<String> keywords = extractKeywords(question);
            selectedEntries = filterByKeywords(entries, keywords);
            log.info("[ChainSplitter] Search question, keywords={}, selectedEntries={}",
                    keywords, selectedEntries.size());
        }

        // 每个入口点创建一个 ChainContext
        List<ChainContext> contexts = new ArrayList<>();
        for (Entry entry : selectedEntries) {
            contexts.add(buildChainContext(entry, question, projectPath, parentSessionId, inheritedData));
        }

        return List.copyOf(contexts);
    }

    /**
     * 判断是否为延伸型追问。
     * 延伸型追问通常是短语（"细说"、"展开"），不含具体技术关键词。
     */
    boolean isFollowUpQuestion(String question) {
        if (question == null || question.isBlank()) return true;

        String trimmed = question.trim();

        // 明确的追问短语
        if (FOLLOW_UP_PHRASES.contains(trimmed)) return true;

        // 极短的中文文本（≤4 个字）大概率是追问
        if (trimmed.matches("^[\\u4e00-\\u9fa5]{1,4}$")) return true;

        // 包含具体英文技术词（≥3字母连续）→ 搜索型
        if (trimmed.matches(".*[a-zA-Z]{3,}.*")) return false;

        // 含空格分隔的多个词 → 搜索型
        if (trimmed.contains(" ") && trimmed.split("\\s+").length >= 2) return false;

        // 默认视为追问
        return true;
    }

    /**
     * 选择 top-N 入口点（按类型优先级排序）。
     */
    private List<Entry> selectTopN(List<Entry> entries, int maxN) {
        if (entries == null || entries.size() <= maxN) {
            return entries != null ? entries : List.of();
        }
        return entries.stream()
                .sorted(Comparator.comparingInt(e -> entryTypePriority(e.type())))
                .limit(maxN)
                .collect(Collectors.toList());
    }

    private int entryTypePriority(String type) {
        if (type == null) return 3;
        return switch (type) {
            case "Controller" -> 0;
            case "MQ_LISTENER", "FEIGN_CLIENT" -> 1;
            case "SCHEDULED" -> 2;
            default -> 3;
        };
    }

    private ChainContext buildChainContext(Entry entry, String question,
                                           String projectPath, String parentSessionId,
                                           ChainContext.Phase1InheritedData inheritedData) {
        String chainId = generateChainId();
        String chainName = buildChainName(entry);
        ChainComplexity complexity = inferComplexity(entry, question);
        List<String> tools = toolRegistry.getTools(complexity);

        return new ChainContext(
                chainId,
                chainName,
                entry,
                question,
                projectPath,
                parentSessionId,
                complexity,
                tools,
                inheritedData
        );
    }

    /**
     * 从问题中提取关键词。
     * 简单实现：按分隔符拆分，过滤短词。
     */
    private List<String> extractKeywords(String question) {
        if (question == null || question.isBlank()) {
            return List.of();
        }

        String[] words = question.split("[\\s,，。.!！?？、]+");
        return Arrays.stream(words)
            .filter(w -> w != null && w.length() >= 2)
            .collect(Collectors.toList());
    }

    /**
     * 根据关键词过滤入口点。
     * 如果没有关键词或关键词无法匹配任何入口点，返回所有入口点。
     */
    private List<Entry> filterByKeywords(List<Entry> entries, List<String> keywords) {
        if (keywords.isEmpty()) {
            return entries;
        }

        List<Entry> filtered = entries.stream()
            .filter(entry -> matchesKeywords(entry, keywords))
            .collect(Collectors.toList());

        return filtered.isEmpty() ? entries : filtered;
    }

    /**
     * 检查入口点是否匹配关键词。
     */
    private boolean matchesKeywords(Entry entry, List<String> keywords) {
        String className = entry.className() != null ? entry.className() : "";
        String methodName = entry.methodName() != null ? entry.methodName() : "";
        String combined = className + "." + methodName;

        for (String keyword : keywords) {
            if (combined.toLowerCase().contains(keyword.toLowerCase())) {
                return true;
            }
        }

        return false;
    }

    /**
     * 构建链路名称。
     */
    private String buildChainName(Entry entry) {
        String type = entry.type() != null ? entry.type() : "UNKNOWN";
        String className = entry.className() != null ? entry.className() : "Unknown";
        String methodName = entry.methodName() != null ? entry.methodName() : "unknown";

        return switch (type) {
            case "Controller" -> className + " 控制器链路";
            case "MQ_LISTENER" -> className + " MQ消费链路";
            case "FEIGN_CLIENT" -> className + " Feign调用链路";
            case "SCHEDULED" -> className + " 定时任务链路";
            default -> className + "#" + methodName + " 链路";
        };
    }

    /**
     * 推断链路复杂度。
     */
    private ChainComplexity inferComplexity(Entry entry, String question) {
        String type = entry.type();

        if ("MQ_LISTENER".equals(type) || "FEIGN_CLIENT".equals(type)) {
            return ChainComplexity.CROSS_SERVICE;
        }

        if (question != null && (question.contains("验证") || question.contains("测试") || question.contains("编译"))) {
            return ChainComplexity.VERIFICATION;
        }

        return ChainComplexity.SIMPLE;
    }

    /**
     * 生成唯一链路 ID。
     */
    private String generateChainId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
