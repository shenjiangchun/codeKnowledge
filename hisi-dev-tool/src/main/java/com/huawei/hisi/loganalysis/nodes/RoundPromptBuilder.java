package com.huawei.hisi.loganalysis.nodes;

import com.huawei.hisi.ram.kg.dto.MethodBodyInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 三轮递进分析的 Prompt 构建器。
 *
 * Round 1 — 模式识别: 只看 parsedError，快速判断事故类型
 * Round 2 — 因果推理: 基于 Round1 的模式 + 代码上下文，进行深度因果链分析
 * Round 3 — 修复方案: 基于 Round2 的因果链，设计分优先级的修复方案
 */
@Slf4j
@Component
public class RoundPromptBuilder {

    // ========== Round 1: 模式识别 ==========

    public String buildRound1SystemPrompt() {
        return """
你是日志异常模式识别专家。根据错误日志和关键堆栈帧，快速判断事故类型和初步假设。

## 已知模式库

| 模式 | 特征异常 | 核心机制 |
|------|---------|---------|
| LOCK_AVALANCHE | LockWaitTimeout/Deadlock | 锁争用→级联超时→连接池耗尽→重试放大 |
| OOM_CASCADE | OutOfMemoryError | 内存泄漏→GC频繁→请求超时 |
| NPE_CHAIN | NullPointerException | 空指针级联→下游服务异常 |
| CONNECTION_EXHAUSTION | ConnectionPoolTimeoutException | 连接池耗尽→请求排队→超时 |
| BROKEN_PIPE | Broken pipe / SocketException | 客户端断连→服务端写失败 |
| SLOW_QUERY | SQL执行超时 | 大查询/缺索引→DB阻塞→级联等待 |
| CONFIG_ERROR | ClassNotFoundException/NoSuchMethodError | 版本不匹配/配置错误 |
| DATA_INCONSISTENCY | ConstraintViolation/BatchUpdateException | 数据完整性问题 |

如果你的观察不完全匹配上述模式，可以提出新模式。

## 输出格式（严格遵循）

返回 JSON:
{
  "patternType": "模式名称（如 LOCK_AVALANCHE 或自定义）",
  "patternConfidence": "high/medium/low",
  "initialHypothesis": "一句话初步假设，描述最可能的根因",
  "suggestedDepth": "shallow/medium/deep",
  "keyObservations": ["从错误日志中观察到的 2-4 个关键线索"]
}

注意:
- patternType 是给 Round 2 使用的分类信号，不要写成描述性文字
- suggestedDepth: shallow=简单NPE等，medium=单因素异常，deep=多因素级联雪崩
- keyObservations 要引用具体的异常名或堆栈帧，不要泛泛描述
""";
    }

    public String buildRound1UserPrompt(Map<String, Object> parsedError) {
        StringBuilder sb = new StringBuilder();

        sb.append("## 错误日志信息\n\n");

        if (parsedError != null) {
            sb.append("错误类型: ").append(parsedError.get("errorType")).append("\n");
            sb.append("根因异常: ").append(parsedError.get("rootCauseException")).append("\n");
            sb.append("\n错误消息:\n").append(parsedError.get("fullMessage")).append("\n");
            sb.append("\n堆栈跟踪:\n").append(parsedError.get("stackTrace")).append("\n");
        }

        sb.append("\n请识别此日志的异常模式，输出 JSON。\n");

        return sb.toString();
    }

    // ========== Round 2: 因果推理 ==========

    /**
     * Round 2 的 system prompt 根据 Round 1 的 patternType 动态调整，
     * 对已知模式给出领域因果模型提示，对未知模式走通用推理。
     */
    public String buildRound2SystemPrompt(String patternType) {
        String domainHint = getDomainHint(patternType);

        return """
你是资深根因分析专家。基于以下已识别的模式和代码上下文，进行深度因果链推理。

## 推理方法（必须遵循）

1. 因果链推理: 从异常表象逐步追溯至根因，形成 A→B→C→D 的因果链路。每一步必须说明机制（为什么 A 导致了 B）。
2. 多因素叠加分析: 识别是否有多个因素共同作用。分析各因素之间的交互和叠加效应。
3. 证据交叉引用: 每个推断步骤必须引用具体的堆栈帧（类名#方法名:行号）或代码片段作为依据。
4. 时序重建: 对并发/时序问题，重建事件演进时间线（T1→T2→T3），标注关键事件和持续时间。

""" + domainHint + """

## 输出格式（严格遵循）

返回 JSON:
{
  "causalChain": [
    {
      "step": 1,
      "event": "描述这一步发生了什么",
      "mechanism": "为什么这一步导致了下一步",
      "evidence": "引用具体堆栈帧或代码行号"
    }
  ],
  "multiFactorAnalysis": {
    "primaryFactor": "主要因素描述",
    "contributingFactors": [
      { "factor": "辅助因素描述", "interaction": "与主因素如何叠加" }
    ],
    "cascadeEffect": "因素叠加后的级联效应"
  },
  "timeline": [
    { "phase": "T1", "event": "阶段关键事件", "duration": "持续时间", "evidence": "佐证依据" }
  ],
  "rootCause": "一句话描述根本原因",
  "rootCauseDetail": "详细分析，包含推理过程和证据引用",
  "confidence": "high/medium/low",
  "confidenceReason": "为什么给出该置信度"
}

注意:
- causalChain 至少 3 步
- 如果不是并发/时序问题，timeline 可以只含 1-2 个阶段
- 如果不是多因素叠加，contributingFactors 可为空数组
""";
    }

    @SuppressWarnings("unchecked")
    public String buildRound2UserPrompt(Map<String, Object> round1Result,
                                        List<MethodBodyInfo> codeBodies,
                                        List<Map<String, Object>> callChains,
                                        List<?> entryPoints,
                                        List<Map<String, Object>> entryPointsWithLayers) {
        StringBuilder sb = new StringBuilder();

        // Round 1 结论
        sb.append("## Round 1 模式识别结果\n\n");
        sb.append("模式类型: ").append(round1Result.getOrDefault("patternType", "UNKNOWN")).append("\n");
        sb.append("模式置信度: ").append(round1Result.getOrDefault("patternConfidence", "low")).append("\n");
        sb.append("初步假设: ").append(round1Result.getOrDefault("initialHypothesis", "无")).append("\n");
        sb.append("建议分析深度: ").append(round1Result.getOrDefault("suggestedDepth", "medium")).append("\n");

        Object keyObs = round1Result.get("keyObservations");
        if (keyObs instanceof List<?> observations && !observations.isEmpty()) {
            sb.append("关键观察:\n");
            for (Object obs : observations) {
                sb.append("- ").append(obs).append("\n");
            }
        }
        sb.append("\n基于以上模式识别，请进行深度因果推理。\n\n");

        // 代码上下文
        sb.append("## 代码上下文\n\n");
        if (codeBodies != null && !codeBodies.isEmpty()) {
            sb.append("找到 ").append(codeBodies.size()).append(" 个相关方法:\n\n");
            for (MethodBodyInfo info : codeBodies.stream().limit(30).collect(Collectors.toList())) {
                sb.append("### ").append(info.className()).append("#").append(info.methodName()).append("\n");
                sb.append("文件: ").append(info.filePath()).append("\n");
                if (info.description() != null && !info.description().isBlank()) {
                    sb.append("描述: ").append(info.description()).append("\n");
                }
                if (info.methodBody() != null && !info.methodBody().isBlank()) {
                    sb.append("\n代码:\n```java\n")
                            .append(truncateCode(info.methodBody(), 2000))
                            .append("\n```\n");
                }
                sb.append("\n");
            }
        } else {
            sb.append("未找到相关代码上下文。\n");
        }

        // 调用链
        sb.append("\n## 调用链信息\n\n");
        if (callChains != null && !callChains.isEmpty()) {
            sb.append("分析了 ").append(callChains.size()).append(" 个调用链:\n\n");
            for (Map<String, Object> chain : callChains.stream().limit(8).collect(Collectors.toList())) {
                sb.append("### ").append(chain.get("className")).append("#").append(chain.get("methodName")).append("\n");
                List<Map<String, Object>> callees = (List<Map<String, Object>>) chain.get("calleesTree");
                if (callees != null && !callees.isEmpty()) {
                    sb.append("下游调用链 (共 ").append(callees.size()).append(" 个方法):\n");
                    for (Map<String, Object> callee : callees.stream().limit(15).collect(Collectors.toList())) {
                        int depth = callee.get("depth") instanceof Integer d ? d : 1;
                        String indent = "  ".repeat(Math.max(0, depth - 1));
                        sb.append(indent).append("- ")
                                .append(callee.get("className")).append("#").append(callee.get("methodName"))
                                .append(" [depth=").append(depth).append("]\n");
                    }
                    if (callees.size() > 15) {
                        sb.append("  ... (还有 ").append(callees.size() - 15).append(" 个下游方法)\n");
                    }
                }
                sb.append("\n");
            }
        } else {
            sb.append("未找到调用链信息。\n");
        }

        // 入口点
        sb.append("\n## 入口点信息\n\n");
        if (entryPointsWithLayers != null && !entryPointsWithLayers.isEmpty()) {
            sb.append("找到 ").append(entryPointsWithLayers.size()).append(" 个入口点:\n\n");
            for (Map<String, Object> ep : entryPointsWithLayers.stream().limit(10).collect(Collectors.toList())) {
                sb.append("- ").append(ep.get("className")).append("#").append(ep.get("methodName"));
                sb.append(" [类型=").append(ep.get("entryType") != null ? ep.get("entryType") : ep.get("type"));
                sb.append(", 层级=").append(ep.get("layer"));
                sb.append(", 来源=").append(ep.get("source")).append("]\n");
            }
        } else if (entryPoints != null && !entryPoints.isEmpty()) {
            sb.append("找到 ").append(entryPoints.size()).append(" 个入口点。\n");
        } else {
            sb.append("未找到明确的入口点。\n");
        }

        sb.append("\n请严格按照系统提示中的推理方法和输出格式进行因果推理。\n");

        return sb.toString();
    }

    // ========== Fallback: 单轮综合分析 ==========

    /**
     * Round 1 失败时的专用 fallback prompt。
     * 合并因果链 + 修复建议为一套输出格式，避免 Round1/Round2 格式矛盾。
     */
    public String buildFallbackSystemPrompt() {
        return """
你是资深运维与代码根因分析专家。对以下错误日志和代码上下文进行一次性综合分析。

## 推理方法（必须遵循）

1. 因果链推理: 从异常表象逐步追溯至根因，形成 A→B→C→D 的因果链路。每一步必须说明机制（为什么 A 导致了 B）。
2. 多因素叠加分析: 识别是否有多个因素共同作用。分析各因素之间的交互和叠加效应。
3. 证据交叉引用: 每个推断步骤必须引用具体的堆栈帧（类名#方法名:行号）或代码片段作为依据。
4. 时序重建: 对并发/时序问题，重建事件演进时间线（T1→T2→T3），标注关键事件和持续时间。

## 输出格式（严格遵循）

返回 JSON:
{
  "causalChain": [
    { "step": 1, "event": "描述", "mechanism": "机制", "evidence": "证据" }
  ],
  "multiFactorAnalysis": {
    "primaryFactor": "主要因素",
    "contributingFactors": [{ "factor": "辅助因素", "interaction": "交互" }],
    "cascadeEffect": "级联效应"
  },
  "timeline": [
    { "phase": "T1", "event": "事件", "duration": "持续时间", "evidence": "佐证" }
  ],
  "rootCause": "一句话根因",
  "rootCauseDetail": "详细分析",
  "confidence": "high/medium/low",
  "confidenceReason": "置信度理由",
  "fixSuggestions": [
    { "suggestion": "修复描述", "priority": "P0/P1/P2", "affectedCode": "代码位置", "expectedEffect": "预期效果" }
  ]
}

注意:
- causalChain 至少 3 步
- fixSuggestions 至少 2 条，必须包含至少 1 条 P0 和 1 条 P1
- priority 使用 P0/P1/P2，不要用 high/medium/low
""";
    }

    // ========== Round 3: 修复方案 ==========

    public String buildRound3SystemPrompt() {
        return """
你是修复方案设计专家。基于以下因果链分析结果，设计分优先级的修复方案。

## 优先级定义

- P0 (立即修复): 影响业务运行的紧急问题，必须在 24h 内修复
- P1 (短期修复): 影响系统稳定性，应在 1-2 周内修复
- P2 (长期优化): 提升系统韧性或性能，可纳入迭代规划

## 输出格式（严格遵循）

返回 JSON:
{
  "fixSuggestions": [
    {
      "suggestion": "修复描述",
      "priority": "P0/P1/P2",
      "affectedCode": "涉及的代码位置（文件:方法名:行号）",
      "expectedEffect": "修复后预期效果",
      "implementationSteps": ["具体实施步骤1", "步骤2"]
    }
  ],
  "verificationChecklist": ["验证项1", "验证项2"],
  "riskAssessment": "修复可能引入的风险评估"
}

注意:
- fixSuggestions 至少 3 条，必须包含至少 1 条 P0 和 1 条 P1
- implementationSteps 要具体可执行，不要写"请手动检查"这类模糊描述
- verificationChecklist 至少 3 项验证点
- priority 使用 P0/P1/P2，不要用 high/medium/low
""";
    }

    @SuppressWarnings("unchecked")
    public String buildRound3UserPrompt(Map<String, Object> round2Result) {
        StringBuilder sb = new StringBuilder();

        sb.append("## Round 2 因果推理结果\n\n");

        sb.append("根因: ").append(round2Result.getOrDefault("rootCause", "未确定")).append("\n");
        sb.append("详细分析: ").append(round2Result.getOrDefault("rootCauseDetail", "无详细分析")).append("\n");
        sb.append("置信度: ").append(round2Result.getOrDefault("confidence", "unknown")).append("\n");
        sb.append("置信度理由: ").append(round2Result.getOrDefault("confidenceReason", "无")).append("\n\n");

        // causalChain
        Object chainObj = round2Result.get("causalChain");
        if (chainObj instanceof List<?> chain && !chain.isEmpty()) {
            sb.append("因果链:\n");
            for (Object item : chain) {
                if (item instanceof Map<?, ?> step) {
                    sb.append("  Step ").append(strOr(step.get("step"), "?")).append(": ");
                    sb.append(strOr(step.get("event"), "?")).append("\n");
                    sb.append("    机制: ").append(strOr(step.get("mechanism"), "?")).append("\n");
                    sb.append("    证据: ").append(strOr(step.get("evidence"), "?")).append("\n");
                }
            }
            sb.append("\n");
        }

        // multiFactorAnalysis
        Object mfaObj = round2Result.get("multiFactorAnalysis");
        if (mfaObj instanceof Map<?, ?> mfa && !mfa.isEmpty()) {
            sb.append("多因素叠加:\n");
            sb.append("  主要因素: ").append(strOr(mfa.get("primaryFactor"), "?")).append("\n");
            sb.append("  级联效应: ").append(strOr(mfa.get("cascadeEffect"), "?")).append("\n");
            Object cfObj = mfa.get("contributingFactors");
            if (cfObj instanceof List<?> cfs) {
                for (Object cf : cfs) {
                    if (cf instanceof Map<?, ?> m) {
                        sb.append("  辅助因素: ").append(strOr(m.get("factor"), "?"));
                        sb.append(" — 交互: ").append(strOr(m.get("interaction"), "?")).append("\n");
                    }
                }
            }
            sb.append("\n");
        }

        // timeline
        Object tlObj = round2Result.get("timeline");
        if (tlObj instanceof List<?> tl && !tl.isEmpty()) {
            sb.append("时序重建:\n");
            for (Object item : tl) {
                if (item instanceof Map<?, ?> phase) {
                    sb.append("  ").append(strOr(phase.get("phase"), "?")).append(": ");
                    sb.append(strOr(phase.get("event"), "?")).append("\n");
                    sb.append("    持续: ").append(strOr(phase.get("duration"), "?")).append("\n");
                }
            }
            sb.append("\n");
        }

        sb.append("请基于以上因果推理结果，设计分优先级的修复方案，输出 JSON。\n");

        return sb.toString();
    }

    // ========== 领域因果模型提示 ==========

    private String getDomainHint(String patternType) {
        if (patternType == null) return "";

        return switch (patternType.toUpperCase()) {
            case "LOCK_AVALANCHE" -> """
## 领域提示: 锁雪崩模式

锁雪崩的典型因果链:
1. 线程A持锁执行长事务 → 2. 线程B等待锁超时 → 3. 等待线程堆积耗尽连接池 → 4. 新请求无法获取连接超时 → 5. 重试机制放大负载

关键检查点:
- 锁持有时间是否异常（SQL慢查询、事务过大）
- 等待超时配置是否合理
- 连接池大小 vs 并发量是否匹配
- 是否存在重试/自动恢复机制放大效应
""";

            case "OOM_CASCADE" -> """
## 领域提示: OOM 级联模式

OOM 级联的典型因果链:
1. 内存泄漏(大对象未释放/缓存无限增长) → 2. GC频率上升 → 3. GC停顿导致请求超时 → 4. 堆积请求进一步消耗内存 → 5. 最终OOM

关键检查点:
- 堆栈中是否有大集合操作(批量查询未分页、缓存未设上限)
- 是否有ThreadLocal泄漏
- 是否有流/连接未关闭
""";

            case "NPE_CHAIN" -> """
## 领域提示: NPE 级联模式

NPE 级联的典型因果链:
1. 上游返回null/空对象 → 2. 下游未做空值检查直接访问 → 3. NPE抛出 → 4. 调用方未捕获导致级联失败

关键检查点:
- 堆栈中NPE位置的具体对象为何为null
- 上游方法是否有返回null的逻辑路径
- 是否缺少Optional/null检查
""";

            case "CONNECTION_EXHAUSTION" -> """
## 领域提示: 连接池耗尽模式

连接池耗尽的典型因果链:
1. 并发请求量突增 → 2. 连接池占满 → 3. 新请求排队等待 → 4. 等待超时 → 5. 业务中断

关键检查点:
- 连接池配置(maxActive/maxIdle/timeout)是否匹配实际并发量
- 是否有连接泄漏(未关闭/未归还)
- 是否有慢查询占用连接过久
""";

            case "SLOW_QUERY" -> """
## 领域提示: 慢查询阻塞模式

慢查询阻塞的典型因果链:
1. 大查询/缺索引 → 2. SQL执行耗时过长 → 3. 占用DB连接和锁 → 4. 级联等待其他操作 → 5. 业务超时

关键检查点:
- 堆栈中的SQL是否有全文扫描/缺少索引
- 是否有批量操作未分页
- 是否有N+1查询
""";

            default -> "";
        };
    }

    // ========== 截断工具 ==========

    private static String strOr(Object value, String fallback) {
        return value != null ? String.valueOf(value) : fallback;
    }

    private String truncateCode(String code, int maxLen) {
        if (code == null) return "";
        if (code.length() <= maxLen) return code;

        String[] lines = code.split("\n");
        if (lines.length <= 40) return code;

        StringBuilder truncated = new StringBuilder();
        for (int i = 0; i < Math.min(20, lines.length); i++) {
            truncated.append(lines[i]).append("\n");
        }
        truncated.append("\n... (中间 ").append(lines.length - 25).append(" 行省略)\n\n");
        for (int i = Math.max(lines.length - 5, 20); i < lines.length; i++) {
            truncated.append(lines[i]).append("\n");
        }
        return truncated.toString();
    }
}
