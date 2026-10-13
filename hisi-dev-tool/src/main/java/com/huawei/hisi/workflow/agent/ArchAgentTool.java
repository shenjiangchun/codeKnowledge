package com.huawei.hisi.workflow.agent;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * 架构分析 ReAct 智能体的一个只读工具：名字、用途描述、参数 schema 与
 * 委托到 {@link com.huawei.hisi.knowledgegraph.controller.KnowledgeGraphController}
 * 只读端点的调用逻辑，内聚在一处。
 *
 * <p>新增工具 = {@link ArchAgentToolRegistry} 里注册一行，不写个性化分支。</p>
 */
public final class ArchAgentTool {

    private final String name;
    private final String description;
    private final Map<String, String> parameterSchema;
    private final BiFunction<Map<String, Object>, List<String>, Object> invoker;

    public ArchAgentTool(String name, String description,
                         Map<String, String> parameterSchema,
                         BiFunction<Map<String, Object>, List<String>, Object> invoker) {
        this.name = name;
        this.description = description;
        this.parameterSchema = parameterSchema;
        this.invoker = invoker;
    }

    public static ArchAgentTool of(String name, String description,
                                   Map<String, String> parameterSchema,
                                   BiFunction<Map<String, Object>, List<String>, Object> invoker) {
        return new ArchAgentTool(name, description, parameterSchema, invoker);
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    /** 参数名 → 用途描述；供 system prompt 生成工具清单。 */
    public Map<String, String> parameterSchema() {
        return parameterSchema;
    }

    /** 执行工具，返回原始数据（由调用方 {@link ObservationTrimmer} 截断）。 */
    public Object invoke(Map<String, Object> args, List<String> projectPaths) {
        return invoker.apply(args, projectPaths);
    }
}
