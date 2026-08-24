package com.huawei.hisi.knowledgegraph.service;

/**
 * 知识图谱构建模式。
 *
 * <p>三种互斥模式：
 * <ul>
 *   <li>{@link #INCREMENTAL} — 增量：依赖 git diff 只重建变更文件，未变更节点向量保留。</li>
 *   <li>{@link #REUSE} — 全量-复用：全量扫描 + codeHash 判定，命中复用向量，未命中重算，
 *       构建末尾清理孤儿节点。仅 Java 链路支持，非 Java 项目自动降级为 {@link #WIPE}。</li>
 *   <li>{@link #WIPE} — 全量-全删：cleanOldData 全删全插，确定性兜底。</li>
 * </ul>
 */
public enum BuildMode {
    INCREMENTAL,
    REUSE,
    WIPE;

    /**
     * 从字符串解析构建模式，大小写不敏感。未知值回退为 {@link #REUSE}。
     */
    public static BuildMode fromString(String value) {
        if (value == null || value.isBlank()) {
            return REUSE;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return REUSE;
        }
    }
}
