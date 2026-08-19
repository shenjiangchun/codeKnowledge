package com.huawei.hisi.knowledgegraph.aggregation.stage;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 游离节点层级 LLM 补全服务（通用，同时服务类级 ClassNode.classRole 和包级 ModuleNode.layerRole）。
 *
 * <p>三级回退（注解/类名/包名 或 包名 CASE）仍无法识别的节点，批量分批调用 LLM，
 * 输入「节点名 + 依赖结构」，LLM 判断其架构层级（CONTROLLER/SERVICE/REPOSITORY/MODEL/UTILITY/UNKNOWN）。
 *
 * <p>LLM 后端走 {@code extractionChatClient}（Spring AI AnthropicChatModel → anthropic 中转 deepseek），
 * 与领域归纳（{@link MultiDimensionCommunityDetector}）同一条链路，避开智谱 GLM-4.7-Flash 的全局限流。
 */
@Slf4j
@Service
public class LayerRoleLlmService {

    private final ChatClient extractionChatClient;

    public LayerRoleLlmService(@Qualifier("extractionChatClient") ChatClient extractionChatClient) {
        this.extractionChatClient = extractionChatClient;
    }

    private static final String SYSTEM_PROMPT = """
        你是 Java Spring 项目的架构分层专家。给定一个代码模块/类的名称和它的依赖关系，
        判断它属于哪一层。分层定义：
        - CONTROLLER：接收 HTTP 请求的入口层（controller/handler/endpoint/facade）
        - SERVICE：业务逻辑层（service/biz/domain/核心业务）
        - REPOSITORY：数据访问层（repository/dao/mapper/数据持久化）
        - MODEL：数据模型层（dto/entity/model/po/vo/domain 对象）
        - UTILITY：工具/基础设施层（util/config/common/constant/helper/scanner/parser/agent 等）
        - UNKNOWN：确实无法判断

        只返回层名（CONTROLLER/SERVICE/REPOSITORY/MODEL/UTILITY/UNKNOWN 之一），不要解释。
        """;

    /** 每批处理的节点数 */
    private static final int BATCH_SIZE = 20;

    /** LLM 层级判断结果 */
    public record RoleResult(String name, String role) {}

    /** 结构化输出目标：单节点名称 + 层级 */
    @JsonClassDescription("层级判断结果：name 逐字回显输入的节点名（不要缩写），role 为 CONTROLLER/SERVICE/REPOSITORY/MODEL/UTILITY/UNKNOWN 之一")
    public record RoleItem(String name, String role) {}
    @JsonClassDescription("层级判断结果集：items 覆盖输入的全部节点，每个节点一个 RoleItem，不要遗漏、不要重复")
    public record RoleGrouping(List<RoleItem> items) {}

    /**
     * 批量分批补全游离节点层级。
     *
     * @param items 待补全的节点（name=节点名，deps=依赖结构描述字符串）
     * @return 补全结果（name → 层级）
     */
    public List<RoleResult> resolveRoles(List<Map<String, String>> items) {
        List<RoleResult> results = new ArrayList<>();
        if (items == null || items.isEmpty()) return results;

        for (int start = 0; start < items.size(); start += BATCH_SIZE) {
            int end = Math.min(start + BATCH_SIZE, items.size());
            List<Map<String, String>> batch = items.subList(start, end);
            try {
                String userPrompt = buildBatchPrompt(batch);
                RoleGrouping grouping = extractionChatClient.prompt()
                        .system(SYSTEM_PROMPT)
                        .user(userPrompt)
                        .call()
                        .entity(RoleGrouping.class);
                if (grouping != null && grouping.items() != null) {
                    for (RoleItem item : grouping.items()) {
                        results.add(new RoleResult(item.name(), normalizeRole(item.role())));
                    }
                } else {
                    log.warn("[LayerRoleLlm] 批量补全返回空（{}-{}）", start, end);
                }
            } catch (Exception e) {
                log.warn("[LayerRoleLlm] 批量补全失败（{}-{}）: {}", start, end, e.getMessage());
            }
        }
        return results;
    }

    private String buildBatchPrompt(List<Map<String, String>> batch) {
        StringBuilder sb = new StringBuilder();
        sb.append("请为以下模块/类判断架构层级：\n");
        for (Map<String, String> item : batch) {
            sb.append("- 名称: ").append(item.get("name")).append("\n");
            sb.append("  依赖: ").append(item.getOrDefault("deps", "无")).append("\n");
        }
        sb.append("\n请按相同顺序，为每个节点输出「名称:层级」，层级只能是 CONTROLLER/SERVICE/REPOSITORY/MODEL/UTILITY/UNKNOWN。");
        return sb.toString();
    }

    /** 规整 LLM 返回的层名（容错大小写/空白，非法值归 UNKNOWN）。 */
    private String normalizeRole(String role) {
        if (role == null) return "UNKNOWN";
        String upper = role.trim().toUpperCase();
        return switch (upper) {
            case "CONTROLLER", "SERVICE", "REPOSITORY", "MODEL", "UTILITY" -> upper;
            default -> "UNKNOWN";
        };
    }
}
