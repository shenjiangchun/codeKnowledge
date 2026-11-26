package com.huawei.hisi.agentflow;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一条可被 LLM 引用的证据：来自日志堆栈、图谱或源码的确定性事实。
 *
 * <p>v2 agent 流要求每条结论以 {@code [[E n]]} 引用证据编号，验证环节据此判定结论是否有据。
 *
 * @param id        证据编号，如 E1
 * @param kind      证据类型：frame / method / semantic / source / upstream / entry / downstream / sql / bridge
 * @param summary   给模型看的一句话描述
 * @param nodeId    图谱节点 ID，非图谱证据为 null
 * @param className 相关类名，可为 null
 * @param methodName 相关方法名，可为 null
 * @param filePath  源码文件路径，可为 null
 * @param exact     是否精确命中（模糊检索得到的为 false）
 */
public record EvidenceAnchor(String id,
                             String kind,
                             String summary,
                             String nodeId,
                             String className,
                             String methodName,
                             String filePath,
                             boolean exact) {

    /** 类型对应的中文标签，用于渲染给模型和写入报告。 */
    public String kindLabel() {
        return switch (kind == null ? "" : kind) {
            case "frame" -> "堆栈帧";
            case "method" -> "图谱·精确命中";
            case "semantic" -> "图谱·模糊匹配";
            case "source" -> "源码";
            case "upstream" -> "上游调用链";
            case "entry" -> "入口点";
            case "downstream" -> "下游调用";
            case "sql" -> "SQL 映射";
            case "bridge" -> "跨服务调用";
            default -> kind == null ? "其他" : kind;
        };
    }

    /** 类名.方法名，两者任一为空时返回 null。 */
    public String methodRef() {
        return className != null && methodName != null ? className + "." + methodName : null;
    }

    /** 落库 / 出接口用的扁平结构，只含非空字段。 */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("kind", kind);
        m.put("kindLabel", kindLabel());
        m.put("summary", summary);
        if (nodeId != null) m.put("nodeId", nodeId);
        if (className != null) m.put("className", className);
        if (methodName != null) m.put("methodName", methodName);
        if (filePath != null) m.put("filePath", filePath);
        m.put("exact", exact);
        return m;
    }
}
