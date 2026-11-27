package com.huawei.hisi.agentbase;

import com.huawei.hisi.piagent.FlowToolManifest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 所有 agent 的唯一声明处：每个 agent 一个定义（ReAct 或工作流），代码里一处声明、测试锁死。
 *
 * <p>判定用哪一个模式的规则（用户确认）：
 * <ul>
 *   <li>步骤与顺序能事先定死、需要人在环/落库/确定性校验/失败回退 → 工作流；工作流节点里「需要模型自己探索或动手」的那一步是 ReAct agent；</li>
 *   <li>独立成入口的纯 ReAct 只有对话类（RAM 对话、日志追问、通用对话）。</li>
 * </ul>
 *
 * <p>ReAct agent 由 {@link ReActSession} 装配执行（工具清单 + skill + 系统提示词 + 契约校验/修正）；
 * 工作流由 {@link FlowEngine} 按声明建链执行。声明只描述结构，提示词正文在 skill 文件里
 * （{@code resources/pi-skills/&lt;name&gt;/SKILL.md}，启动时同步到 pi 的 skills 目录）。
 */
public final class AgentRegistry {

    /**
     * 一个 ReAct agent 的声明。{@code contractSchema} 为 null 表示自由文本（对话类）；
     * 有契约时，输出必须过契约校验，不过则带问题自动修正（最多 {@code repairRounds} 轮）。
     */
    public record ReactAgent(String key, String flow, List<String> skills,
                             String contractSchema, int repairRounds, String description) {
    }

    /** 一个工作流的声明：有序节点 + 执行策略。 */
    public record Workflow(String key, String displayName, List<String> nodes,
                           Map<String, Object> policy, String description) {
    }

    private static final Map<String, ReactAgent> AGENTS = new LinkedHashMap<>();
    private static final Map<String, Workflow> WORKFLOWS = new LinkedHashMap<>();
    private static final Map<String, String> NODE_TO_AGENT = new LinkedHashMap<>();

    static {
        // ── ReAct agents ────────────────────────────────────────────────────
        react("arch-review", "arch-react", List.of("architecture-review"), "arch/react-step", 2,
                "架构深度分析：对确定性证据逐条查证，产出问题清单与治理路线图");
        react("wb-develop", FlowToolManifest.WORKBENCH_DEV, List.of("java-fix-tdd"), null, 0,
                "工作台开发：在 worktree 内实现任务、写测试、编译到通过");
        react("wb-proposal", FlowToolManifest.ONE_SHOT, List.of("fix-proposal"), null, 0,
                "工作台方案：根据证据生成候选修复方案");
        react("wb-spec", FlowToolManifest.ONE_SHOT, List.of("fix-spec"), null, 0,
                "工作台 Spec：目标、验收标准、测试用例");
        react("wb-tasks", FlowToolManifest.ONE_SHOT, List.of("fix-tasks"), null, 0,
                "工作台任务拆分：可独立验证的任务清单");
        react("ram-clarify", "ram", List.of("requirement-clarify"), "clarify.output", 1,
                "需求澄清：读代码判断需求是否清晰，答不上来的才问用户");
        react("ram-design", "ram", List.of("solution-design"), "implement.output", 1,
                "实现方案：基于影响面给出落点与改动方式");
        react("ram-techplan", "ram", List.of("tech-plan"), "tech_plan.output", 1,
                "技术方案：待改方法明细、时序/流程图、测试范围");
        react("ram-overview", "ram", List.of("status-review"), null, 0,
                "项目现状解读：把图谱与质量数据写成现状报告");
        react("ram-locate", "ram", List.of("call-tree-triage"), null, 0,
                "Phase2 精准定位：顺调用链定位问题根因");
        react("log-hypothesis", "log-analysis", List.of("log-triage"), null, 1,
                "日志根因探查：查图谱与日志形成假设");
        react("log-followup", "log-analysis", List.of("log-triage"), null, 0,
                "日志追问：围绕报告继续问答");
        react("ram-chat", "ram-act", List.of("ram-assistant"), null, 0,
                "RAM 对话助手：查图谱答疑，可发起分析与提交澄清（动作工具，每次运行带用户令牌）");
        react("merge-impact", FlowToolManifest.ONE_SHOT, List.of("merge-triage"), null, 0,
                "合并影响面：对 diff 归类影响面与风险");
        react("merge-test-scope", FlowToolManifest.ONE_SHOT, List.of("merge-triage"), null, 0,
                "合并测试范围：按影响面圈定测试范围");
        react("chat", "chat-read", List.of(), null, 0,
                "通用对话：读图谱/日志/APM 答疑");
        react("oneshot", FlowToolManifest.ONE_SHOT, List.of(), null, 0,
                "零工具一次性结构化调用：标注、分解、校验等");

        // ── Workflows：节点是 bean 名、ReAct agent 的节点名或工作台阶段键 ──
        workflow("arch-deep-analysis", "架构深度分析",
                List.of("arch_evidence_collect", "arch_react_agent"),
                Map.of("skipHitl", true, "emitNodeEvents", true),
                "实时诊断报告 + ReAct 查证 + 报告组装");
        workflow("ram-demand", "需求分析",
                List.of("clarify", "impact", "implement", "verify", "tech_plan"),
                Map.of("hitlNodes", List.of("clarify", "impact", "implement")),
                "澄清 → 影响面 → 实现 → 验证 → 技术方案，节点间确认");
        workflow("ram-status", "项目现状分析",
                List.of("project_overview"),
                Map.of("skipHitl", true, "emitNodeEvents", true),
                "图谱与质量数据 → 现状报告");
        workflow("ram-phase2", "Phase2 精准定位",
                List.of("phase2_analysis"),
                Map.of("skipHitl", true, "emitNodeEvents", true),
                "调用链精准定位");
        workflow("wb-autofix", "工作台自动修复",
                List.of("evidence", "proposal", "spec", "tasks", "develop", "tests", "e2e", "ship"),
                Map.of("confirmGate", "proposal", "retryTarget", "develop", "maxIterations", 3),
                "技术债待办全链路修复：方案确认门，回归/E2E 失败回开发");
        workflow("log-analysis", "日志分析",
                List.of("decode", "collect", "hypothesis", "verify", "synthesis", "report"),
                Map.of("skipHitl", true, "emitNodeEvents", true),
                "解码 → 证据收集 → 根因探查 → 校验 → 综合 → 报告");
        workflow("merge-analysis", "合并影响分析",
                List.of("diff_extract", "impact_analysis", "test_scope"),
                Map.of("skipHitl", true, "emitNodeEvents", true),
                "diff 提取 → 影响面 → 测试范围");
    }

    private static void react(String key, String flow, List<String> skills,
                              String contractSchema, int repairRounds, String description) {
        AGENTS.put(key, new ReactAgent(key, flow, List.copyOf(skills), contractSchema, repairRounds, description));
    }

    private static void workflow(String key, String displayName, List<String> nodes,
                                 Map<String, Object> policy, String description) {
        WORKFLOWS.put(key, new Workflow(key, displayName, List.copyOf(nodes), Map.copyOf(policy), description));
    }

    static {
        // 工作流节点名 → ReAct agent：引擎按名解析时先找 bean，找不到就按这个表交给 ReAct 会话
        NODE_TO_AGENT.put("arch_react_agent", "arch-review");
        NODE_TO_AGENT.put("develop", "wb-develop");
        NODE_TO_AGENT.put("proposal", "wb-proposal");
        NODE_TO_AGENT.put("spec", "wb-spec");
        NODE_TO_AGENT.put("tasks", "wb-tasks");
    }

    public static Optional<ReactAgent> agent(String key) {
        return Optional.ofNullable(AGENTS.get(key));
    }

    public static Optional<Workflow> workflow(String key) {
        return Optional.ofNullable(WORKFLOWS.get(key));
    }

    /** 工作流节点名对应的 ReAct agent；没有映射（该节点是确定性 bean）时为空。 */
    public static Optional<ReactAgent> agentForNode(String nodeName) {
        return Optional.ofNullable(NODE_TO_AGENT.get(nodeName)).flatMap(AgentRegistry::agent);
    }

    /** 全部 ReAct agent（只读视图）。 */
    public static Map<String, ReactAgent> agents() {
        return Map.copyOf(AGENTS);
    }

    /** 全部工作流（只读视图）。 */
    public static Map<String, Workflow> workflows() {
        return Map.copyOf(WORKFLOWS);
    }

    private AgentRegistry() {
    }
}
