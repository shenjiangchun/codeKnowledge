package com.huawei.hisi.knowledgegraph.service;

import com.huawei.hisi.glossary.model.GlossaryTerm;
import com.huawei.hisi.glossary.repository.GlossaryTermRepository;
import com.huawei.hisi.neo4j.model.EntryPointNode;
import com.huawei.hisi.neo4j.model.MethodNode;
import com.huawei.hisi.neo4j.repository.Neo4jMethodNodeRepository;
import com.huawei.hisi.neo4j.service.EmbeddingService;
import com.huawei.hisi.service.UnifiedTextService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 入口点描述生成服务
 *
 * 全量 KG 生成后，遍历所有入口点，基于完整调用链路中的方法描述生成入口的简要和详细描述。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EntryPointDescriptionService {

    private final Neo4jMethodNodeRepository methodNodeRepository;
    private final GlossaryTermRepository glossaryTermRepository;
    private final UnifiedTextService textService;
    private final EmbeddingService embeddingService;

    private static final int MAX_CALL_DEPTH = 3;
    private static final int MAX_DESCRIPTION_CHARS = 3000;
    private static final int BRIEF_MAX_LENGTH = 30;
    private static final int DETAILED_MIN_LENGTH = 100;
    private static final int DETAILED_MAX_LENGTH = 200;

    private static final String ENTRY_BRIEF_PROMPT = """
你是代码语义解析专家，请为以下代码入口点生成简要描述（30字以内）。

## 入口信息
类型：{{entryType}}
标识：{{entryKey}}
方法签名：{{signature}}

## 调用链关键方法（按重要性排序）
{{methodDescriptions}}

## 输出要求
- 一句话概括该入口的核心功能
- 不要输出代码细节
- 必须使用术语表中的术语

## 术语规范
{{glossary}}

直接输出描述，无额外内容。
""";

    private static final String ENTRY_DETAILED_PROMPT = """
你是代码语义解析专家，请为以下代码入口点生成详细描述（100-200字）。

## 入口信息
类型：{{entryType}}
标识：{{entryKey}}
方法签名：{{signature}}

## 完整调用链
{{methodDescriptions}}

## 输出要求
- 描述该入口的业务场景和触发条件
- 概述主要处理流程（3-5个关键步骤）
- 指出关键数据流转和外部依赖
- 使用术语表中的术语

## 术语规范
{{glossary}}

直接输出描述，无额外内容。
""";

    /**
     * 为入口点生成描述（简要 + 详细）和向量
     */
    public EntryPointDescription generateDescription(EntryPointNode entry, String projectPath) {
        log.debug("[入口描述] 生成描述: entryId={}, entryKey={}", entry.getEntryId(), entry.getEntryKey());

        // 1. 获取入口方法
        String methodNodeId = entry.getMethodNodeId();
        if (methodNodeId == null || methodNodeId.isEmpty()) {
            log.warn("[入口描述] 入口点无关联方法: entryId={}", entry.getEntryId());
            return generateFallbackDescription(entry, projectPath);
        }

        // 2. 获取调用链（限制深度）
        List<MethodNode> callees = methodNodeRepository.findCalleesUpToDepth(methodNodeId, MAX_CALL_DEPTH);

        // 3. 收集方法描述（截断策略）
        List<String> methodDescriptions = collectMethodDescriptions(callees);

        // 4. 获取术语表
        String glossarySegment = buildGlossarySegment(projectPath);

        // 5. 构建 Prompt 并调用 LLM
        String briefPrompt = ENTRY_BRIEF_PROMPT
                .replace("{{entryType}}", entry.getEntryType())
                .replace("{{entryKey}}", entry.getEntryKey())
                .replace("{{signature}}", extractSignature(entry))
                .replace("{{methodDescriptions}}", formatMethodDescriptions(methodDescriptions))
                .replace("{{glossary}}", glossarySegment);

        String detailedPrompt = ENTRY_DETAILED_PROMPT
                .replace("{{entryType}}", entry.getEntryType())
                .replace("{{entryKey}}", entry.getEntryKey())
                .replace("{{signature}}", extractSignature(entry))
                .replace("{{methodDescriptions}}", formatMethodDescriptions(methodDescriptions))
                .replace("{{glossary}}", glossarySegment);

        String brief = textService.generateText(briefPrompt);
        String detailed = textService.generateText(detailedPrompt);

        // 6. 生成向量
        float[] briefEmbedding = embeddingService.generateEmbedding(brief);
        float[] detailedEmbedding = embeddingService.generateEmbedding(detailed);

        return new EntryPointDescription(brief, detailed, briefEmbedding, detailedEmbedding);
    }

    /**
     * 收集方法描述（截断策略，按深度排序）
     */
    private List<String> collectMethodDescriptions(List<MethodNode> callees) {
        List<String> descriptions = new ArrayList<>();
        int totalChars = 0;

        // 按描述长度排序（短描述优先，避免超出限制）
        List<MethodNode> sortedCallees = callees.stream()
                .sorted(Comparator.comparingInt(m ->
                    m.getDescription() != null ? m.getDescription().length() : Integer.MAX_VALUE))
                .collect(Collectors.toList());

        for (MethodNode method : sortedCallees) {
            String desc = method.getDescription();
            if (desc != null && !desc.isEmpty()) {
                // 截断超长描述
                if (totalChars + desc.length() > MAX_DESCRIPTION_CHARS) {
                    int remaining = MAX_DESCRIPTION_CHARS - totalChars;
                    if (remaining > 50) {
                        desc = desc.substring(0, Math.min(100, remaining));
                        descriptions.add(desc);
                        totalChars += desc.length();
                    }
                    break;
                }
                descriptions.add(desc);
                totalChars += desc.length();
            }
        }

        return descriptions;
    }

    /**
     * 格式化方法描述列表
     */
    private String formatMethodDescriptions(List<String> descriptions) {
        if (descriptions.isEmpty()) {
            return "（无调用链信息）";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < descriptions.size(); i++) {
            sb.append(i + 1).append(". ").append(descriptions.get(i)).append("\n");
        }
        return sb.toString();
    }

    /**
     * 从入口点提取方法签名
     */
    private String extractSignature(EntryPointNode entry) {
        // 从 entryInfo JSON 中提取签名信息
        String entryInfo = entry.getEntryInfo();
        if (entryInfo != null && entryInfo.contains("handlerMethod")) {
            // 简化处理：直接返回 entryKey
            return entry.getEntryKey();
        }
        return entry.getEntryKey();
    }

    /**
     * 构建术语表片段
     */
    private String buildGlossarySegment(String projectPath) {
        if (projectPath == null || projectPath.isBlank()) {
            return "";
        }
        List<GlossaryTerm> terms = glossaryTermRepository.findByProjectPath(projectPath);
        if (terms.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
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

    /**
     * 生成降级描述（当无关联方法时）
     */
    private EntryPointDescription generateFallbackDescription(EntryPointNode entry, String projectPath) {
        String brief = entry.getEntryType() + " 入口：" + entry.getEntryKey();
        String detailed = "该入口点为 " + entry.getEntryType() + " 类型，标识为 " + entry.getEntryKey() + "。" +
                "暂无关联方法信息，无法生成详细描述。";

        float[] briefEmbedding = embeddingService.generateEmbedding(brief);
        float[] detailedEmbedding = embeddingService.generateEmbedding(detailed);

        return new EntryPointDescription(brief, detailed, briefEmbedding, detailedEmbedding);
    }

    /**
     * 入口点描述结果
     */
    public record EntryPointDescription(
        String brief,
        String detailed,
        float[] briefEmbedding,
        float[] detailedEmbedding
    ) {}
}