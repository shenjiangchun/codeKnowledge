package com.huawei.hisi.workflow.v2;

import com.huawei.hisi.agentbase.AgentRegistry;
import com.huawei.hisi.agentbase.ReActSession;
import com.huawei.hisi.agentflow.AgentFlowCancelRegistry;
import com.huawei.hisi.archdiagnosis.service.ArchDiagramGenerator;
import com.huawei.hisi.workflow.DagNode;
import com.huawei.hisi.workflow.NodeEventSink;
import com.huawei.hisi.workflow.agent.ArchAgentToolRegistry;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 架构深度分析的 ReAct 节点：装配方式与契约都来自统一声明（{@code arch-review}），
 * 由 {@link ReActSession} 执行——pi 按只读清单调 MCP 工具查证，收敛后输出 final_answer；
 * 输出不合契约时由会话层带问题自动修正（最多 2 轮），不再在这里手写修正循环。
 *
 * <p>节点名与 v1 相同（{@code arch_react_agent}，前端写死），输出契约不变
 * （{@code {rootCauseResult, markdownReport, diagrams, degraded}}）；查证规则的提示词、
 * 图引用校验、降级模板复用父类；查证方法的领域知识在 {@code architecture-review} skill 里。
 */
@Slf4j
public class PiReActAgentNode extends com.huawei.hisi.workflow.nodes.ArchReActAgentNode implements DagNode {

    /** 与 v1 节点同名：事件、检查点、从节点重跑对前端保持一致。 */
    public static final String NODE_NAME = "arch_react_agent";

    /** 统一声明里的 agent 键。 */
    static final String AGENT_KEY = "arch-review";

    private final ReActSession session;
    private final AgentFlowCancelRegistry cancelRegistry;

    public PiReActAgentNode(ArchAgentToolRegistry registry,
                            ArchDiagramGenerator diagramGenerator,
                            ReActSession session,
                            AgentFlowCancelRegistry cancelRegistry) {
        super(registry, diagramGenerator);
        this.session = session;
        this.cancelRegistry = cancelRegistry;
    }

    @Override
    public String name() {
        return NODE_NAME;
    }

    @Override
    public String agentId() {
        return "arch-react-agent-pi";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input) {
        return execute(input, msg -> {});
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input, NodeEventSink sink) {
        if (input == null || !(input.get("evidencePack") instanceof Map<?, ?> evidencePack)) {
            throw new IllegalArgumentException("evidencePack is required (upstream arch_evidence_collect output)");
        }
        List<String> projectPaths = extractProjectPaths(evidencePack);
        List<Map<String, Object>> diagrams = generateDiagrams(evidencePack, projectPaths);

        String sessionId = "arch-" + UUID.randomUUID().toString().substring(0, 12);
        Path workdir = projectPaths.isEmpty() ? Path.of(System.getProperty("user.dir")) : Path.of(projectPaths.get(0));
        if (!Files.isDirectory(workdir)) {
            workdir = Path.of(System.getProperty("user.dir"));
        }

        String systemPrompt = piSystemPrompt(diagrams, projectPaths);
        String userPrompt = userPrompt(evidencePack, List.of());
        sink.emit("pi 会话启动：按只读清单查证中…");

        AgentRegistry.ReactAgent agent = AgentRegistry.agent(AGENT_KEY).orElseThrow();
        ReActSession.Result result = session.run(agent, sessionId, workdir, systemPrompt, userPrompt,
                event -> reportEvent(event, sink), cancelRegistry::isCurrentCancelled, null);

        if (result.json() == null) {
            if ("已取消".equals(result.note())) {
                throw new com.huawei.hisi.agentflow.AgentFlowCancelledException();
            }
            sink.emit(result.note() == null ? "pi 会话未产出结果，降级为模板报告"
                    : result.note() + "，降级为模板报告");
            return buildDegradedOutput(evidencePack, sink, diagrams);
        }
        Map<String, Object> step = result.json();
        if (!(step.get("final_answer") instanceof Map<?, ?> fa)) {
            sink.emit("pi 输出缺少 final_answer，降级为模板报告");
            return buildDegradedOutput(evidencePack, sink, diagrams);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> answer = (Map<String, Object>) fa;
        return buildOutput(evidencePack, answer, sink, diagrams);
    }

    /**
     * 把底座的上下文管理动作显示在进度里：提交前的预算压缩（所有流都有），
     * 以及 pi 运行中的会话压缩（上下文接近窗口上限时总结较早的内容）。
     */
    static void reportEvent(Map<String, Object> event, NodeEventSink sink) {
        String type = String.valueOf(event.get("type"));
        if ("tool_execution_start".equals(type)) {
            sink.emit("查询工具：" + event.getOrDefault("toolName", "?"));
        } else if ("context_compaction".equals(type)) {
            sink.emit(String.format("上下文预算：输入 %.1f 万字符 → %.1f 万字符（%s），完整原文已落盘可供回看",
                    number(event.get("beforeChars")) / 10_000.0, number(event.get("afterChars")) / 10_000.0,
                    Boolean.TRUE.equals(event.get("structured")) ? "长列表已按预算截短" : "只保留了开头"));
        } else if ("auto_compaction_start".equals(type) || "compaction_start".equals(type)) {
            sink.emit("上下文压缩：会话接近窗口上限，正在总结较早的内容…");
        } else if ("auto_compaction_end".equals(type) || "compaction_end".equals(type)) {
            Object error = event.get("errorMessage");
            if (error != null) {
                sink.emit("上下文压缩失败：" + error);
            } else {
                sink.emit(String.format("上下文压缩完成：%s token → %s token",
                        event.getOrDefault("tokensBefore", "?"), event.getOrDefault("tokensAfter", "?")));
            }
        }
    }

    private static long number(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    /** pi 模式系统提示词：查证规则与父类一致，协议改为自由工具调用 + 最终 JSON。 */
    private String piSystemPrompt(List<Map<String, Object>> diagrams, List<String> projectPaths) {
        StringBuilder sb = new StringBuilder(super.systemPrompt(diagrams));
        sb.append("\n\n—— 执行协议（pi 模式）——\n")
                .append("你可以直接调用工具，不必输出 thought/action JSON；工具返回后继续查证，直到证据充分。\n")
                .append("第一步：用 kg_select_scope 设置项目路径（list 然后 set），项目：")
                .append(String.join("、", projectPaths)).append("。\n")
                .append("最后一步：只输出一个 JSON 对象，形如 {\"thought\":\"<一句话说明结论依据>\",\"final_answer\":{...}}，")
                .append("final_answer 与上面 schema 一致；不要输出其他文字、不要加代码围栏。\n")
                .append("查证原则：先看 evidencePack 里的证据，只对拿不准的候选问题调工具抽查，")
                .append("不要逐条调用；工具返回 scannedFileCount=0 时不得判 false_positive。\n")
                .append("输入经过上下文预算：过长的列表带 _truncated 标记（说明还有多少条没展示），")
                .append("_contextNotice 给出完整原文的保存路径；被截掉的部分不要当作不存在，必要时用 read 工具分段查看。\n")
                .append("evidence_appendix 是字符串字段（把证据附录写成一段文字），不要给成对象或数组。");
        return sb.toString();
    }
}
