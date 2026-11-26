package com.huawei.hisi.agentflow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次 agent 流里收集到的证据集合，按加入顺序编号（E1、E2 …）。
 *
 * <p>证据包在 LLM 调用之前由确定性代码构建；模型只能引用已有编号，不能自行补充事实。
 */
public final class EvidencePack {

    private final Map<String, EvidenceAnchor> byId = new LinkedHashMap<>();

    /** 追加一条证据并返回。 */
    public EvidenceAnchor add(String kind, String summary, String nodeId,
                              String className, String methodName, String filePath, boolean exact) {
        String id = "E" + (byId.size() + 1);
        EvidenceAnchor anchor = new EvidenceAnchor(id, kind, summary, nodeId, className, methodName, filePath, exact);
        byId.put(id, anchor);
        return anchor;
    }

    public EvidenceAnchor get(String id) {
        return id == null ? null : byId.get(id);
    }

    public boolean contains(String id) {
        return id != null && byId.containsKey(id);
    }

    public boolean isEmpty() {
        return byId.isEmpty();
    }

    public int size() {
        return byId.size();
    }

    public List<EvidenceAnchor> list() {
        return new ArrayList<>(byId.values());
    }

    /**
     * 图谱中是否精确存在该方法：依据必须是来自图谱且精确命中的证据。
     * 堆栈帧登记的证据没有 nodeId，不会被算作「在图谱中」。
     */
    public boolean hasGraphMethod(String className, String methodName) {
        if (className == null || methodName == null) {
            return false;
        }
        return byId.values().stream()
                .anyMatch(a -> a.nodeId() != null && a.exact()
                        && className.equals(a.className()) && methodName.equals(a.methodName()));
    }

    /** 渲染成给模型看的证据表，超过 maxChars 时截断并注明。 */
    public String render(int maxChars) {
        StringBuilder sb = new StringBuilder();
        for (EvidenceAnchor a : byId.values()) {
            sb.append(a.id()).append(" | ").append(a.kindLabel()).append(" | ").append(a.summary()).append('\n');
            if (sb.length() > maxChars) {
                sb.append("…（证据过多，已截断，共 ").append(byId.size()).append(" 条）");
                break;
            }
        }
        return sb.toString();
    }

    /** 所有证据的扁平结构，用于落库与接口附加字段。 */
    public List<Map<String, Object>> toMaps() {
        List<Map<String, Object>> out = new ArrayList<>();
        byId.values().forEach(a -> out.add(a.toMap()));
        return out;
    }
}
