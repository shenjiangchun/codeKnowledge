package com.huawei.hisi.ram.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.ram.model.AgentEvent;
import com.huawei.hisi.ram.model.EventType;
import com.huawei.hisi.ram.repository.AgentEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChatContextBuilder {

    private static final int RECENT_TURN_LIMIT = 3;
    private static final int MAX_SUMMARY_LENGTH = 800;

    private final AgentEventRepository eventRepository;
    private final ObjectMapper objectMapper;

    public record ChatContext(String systemPrompt, String userPrompt) {}

    public ChatContext buildContext(long sessionId, String currentQuestion, List<String> projectPaths) {
        String recentSummary = buildRecentSummaries(sessionId);
        String systemPrompt = buildSystemPrompt(projectPaths);
        String userPrompt = buildUserPrompt(recentSummary, currentQuestion);
        return new ChatContext(systemPrompt, userPrompt);
    }

    private String buildRecentSummaries(long sessionId) {
        List<AgentEvent> events = eventRepository.findBySessionId(sessionId);
        List<AgentEvent> checkpoints = new ArrayList<>();
        for (int i = events.size() - 1; i >= 0 && checkpoints.size() < RECENT_TURN_LIMIT; i--) {
            AgentEvent ev = events.get(i);
            if (ev.getType() == EventType.CHECKPOINT) {
                checkpoints.add(ev);
            }
        }
        if (checkpoints.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("[历史会话上下文 / Recent Conversation]\n");
        for (int i = checkpoints.size() - 1; i >= 0; i--) {
            AgentEvent ckpt = checkpoints.get(i);
            sb.append("--- Turn ").append(checkpoints.size() - i).append(" ---\n");
            String summary = extractSummary(ckpt.getPayload());
            if (summary.length() > MAX_SUMMARY_LENGTH) {
                summary = summary.substring(0, MAX_SUMMARY_LENGTH) + "...(truncated)";
            }
            sb.append(summary).append("\n\n");
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private String extractSummary(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) return "";
        try {
            Map<String, Object> payload = objectMapper.readValue(payloadJson, Map.class);
            Object summary = payload.get("summary");
            if (summary instanceof String s) return s;
            Object finalJson = payload.get("finalJson");
            if (finalJson instanceof String s) return s;
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse checkpoint payload: {}", e.getOriginalMessage());
            return "";
        }
    }

    String buildSystemPrompt(List<String> projectPaths) {
        String pathsBlock = projectPaths == null || projectPaths.isEmpty()
                ? "(未指定)"
                : String.join(", ", projectPaths);
        return """
                你是项目现状分析助手，帮助开发者快速理解代码库结构、核心调用链、技术栈。

                [项目路径 / Project Path]
                %s

                [工作方式 / Working Mode]
                1. 用户用自然语言提问，你根据需要调用工具收集信息
                2. 可用工具：
                   - generate_project_overview: 生成完整项目概览（入口点、核心调用链、技术栈、模块分析、改进建议）
                   - hybrid_search: 语义+关键词混合检索方法节点
                   - load_method_bodies: 加载方法源码
                   - callees_tree: 获取下游调用链
                   - root_entries: 获取上游入口来源
                   - entry_points: 列出所有入口点（Controller/MQ/Feign）
                   - grep_project: 在项目源码中搜索关键字
                   - read_file: 读取文件内容
                   - list_files: 列出目录文件
                3. 工具调用上限 10 轮，超过将强制结束
                4. 如果用户问题是追问，参考 [历史会话上下文] 中的前文，不要重复调用已调用过的工具

                [输出约束 / Output Constraints]
                1. 使用 Markdown 输出：正文可以包含标题、代码块（```lang）、有序/无序列表、表格。
                2. 优先在正文顶部用一句话点明核心结论，然后再展开细节，方便前端做摘要与滚动。
                3. 若引用到具体代码位置，使用 `path:line` 形式，便于用户跳转。
                4. 图表（如序列图、类关系）使用 mermaid 代码块。
                """.formatted(pathsBlock);
    }

    private String buildUserPrompt(String recentSummary, String currentQuestion) {
        StringBuilder sb = new StringBuilder();
        if (!recentSummary.isBlank()) {
            sb.append(recentSummary).append("\n");
        }
        sb.append("[当前问题 / Current Question]\n").append(currentQuestion);
        return sb.toString();
    }
}
