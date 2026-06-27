// hisi-dev-tool/src/main/java/com/huawei/hisi/ram/phase2v2/ChainSplitter.java
package com.huawei.hisi.ram.phase2v2;

import com.huawei.hisi.ram.kg.dto.Entry;
import com.huawei.hisi.ram.phase2v2.model.ChainComplexity;
import com.huawei.hisi.ram.phase2v2.model.ChainContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 链路拆分器：根据 KG entryPoints 和用户问题关键词拆分独立链路。
 */
@Component
@RequiredArgsConstructor
public class ChainSplitter {

    private final DynamicToolRegistry toolRegistry;

    /**
     * 将 entryPoints 拆分为独立 ChainContext。
     *
     * @param entries 入口点列表
     * @param question 用户问题
     * @param projectPath 项目路径
     * @param parentSessionId 父会话 ID
     * @return 拆分后的链路上下文列表
     */
    public List<ChainContext> split(
            List<Entry> entries,
            String question,
            String projectPath,
            String parentSessionId) {

        if (entries == null || entries.isEmpty()) {
            return List.of();
        }

        List<ChainContext> contexts = new ArrayList<>();

        // 1. 从问题提取关键词
        List<String> keywords = extractKeywords(question);

        // 2. 过滤相关入口点
        List<Entry> relevantEntries = filterByKeywords(entries, keywords);

        // 3. 每个入口点创建一个 ChainContext
        for (Entry entry : relevantEntries) {
            String chainId = generateChainId();
            String chainName = buildChainName(entry);
            ChainComplexity complexity = inferComplexity(entry, question);
            List<String> tools = toolRegistry.getTools(complexity);

            ChainContext context = new ChainContext(
                chainId,
                chainName,
                entry,
                question,
                projectPath,
                parentSessionId,
                complexity,
                tools,
                null  // inheritedData 由 Orchestrator 设置
            );

            contexts.add(context);
        }

        return contexts;
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
        return List.of(words).stream()
            .filter(w -> w != null && w.length() >= 2)
            .toList();
    }

    /**
     * 根据关键词过滤入口点。
     * 如果没有关键词或关键词无法匹配任何入口点，返回所有入口点。
     */
    private List<Entry> filterByKeywords(List<Entry> entries, List<String> keywords) {
        // 无关键词时返回所有入口点
        if (keywords.isEmpty()) {
            return entries;
        }

        // 尝试过滤匹配的入口点
        List<Entry> filtered = entries.stream()
            .filter(entry -> matchesKeywords(entry, keywords))
            .toList();

        // 如果没有匹配项，返回所有入口点（关键词可能是用户不关心的词）
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

        // MQ 和 Feign 跨服务
        if ("MQ_LISTENER".equals(type) || "FEIGN_CLIENT".equals(type)) {
            return ChainComplexity.CROSS_SERVICE;
        }

        // 验证关键词需要 VERIFICATION 复杂度
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