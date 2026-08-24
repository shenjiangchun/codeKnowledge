package com.huawei.hisi.neo4j.model;

/**
 * 语义检索的显式类型（searchType 传参）。
 *
 * <p>显式传入时优先于 QueryTypeDetector 自动检测；未传入时回退自动检测（向后兼容）。
 */
public enum SearchType {
    /** 方法级检索（MethodNode.descriptionEmbedding） */
    METHOD,
    /** 类级检索（ClassNode.descriptionEmbedding） */
    CLASS,
    /** SQL 检索（SqlNode.sqlEmbedding） */
    SQL,
    /** 入口点检索（EntryPoint.briefEmbedding） */
    ENTRY,
    /** 多路合并（方法+类+SQL+入口） */
    ALL
}
