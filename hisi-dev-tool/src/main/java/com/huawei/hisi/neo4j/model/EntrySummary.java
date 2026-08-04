package com.huawei.hisi.neo4j.model;

/**
 * 入口摘要信息
 */
public record EntrySummary(
    String entryId,
    String entryType,
    String entryKey,
    String briefDescription
) {}