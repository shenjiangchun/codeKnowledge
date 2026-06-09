package com.huawei.hisi.neo4j.model;

import java.util.List;

/**
 * 服务入口分组
 * 用于按 serviceName 聚合入口点
 */
public record ServiceEntryGroup(
    String serviceName,
    List<EntrySummary> entries,
    long totalCount
) {}