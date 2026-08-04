package com.huawei.hisi.neo4j.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 迁移状态模型
 * 对比 PostgreSQL 和 Neo4j 的数据状态
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MigrationStatus {

    /**
     * 项目路径
     */
    private String projectPath;

    /**
     * PostgreSQL 方法节点数量
     */
    private long pgMethodCount;

    /**
     * PostgreSQL 调用关系数量
     */
    private long pgCallRelationCount;

    /**
     * PostgreSQL 入口点数量
     */
    private long pgEntryPointCount;

    /**
     * Neo4j 方法节点数量
     */
    private long neo4jMethodCount;

    /**
     * Neo4j 入口点数量
     */
    private long neo4jEntryPointCount;

    /**
     * 计算方法节点迁移进度百分比
     */
    public double getMethodMigrationProgress() {
        if (pgMethodCount == 0) {
            return 100.0;
        }
        return Math.min(100.0, (neo4jMethodCount * 100.0 / pgMethodCount));
    }

    /**
     * 计算入口点迁移进度百分比
     */
    public double getEntryPointMigrationProgress() {
        if (pgEntryPointCount == 0) {
            return 100.0;
        }
        return Math.min(100.0, (neo4jEntryPointCount * 100.0 / pgEntryPointCount));
    }

    /**
     * 判断方法节点是否迁移完成
     */
    public boolean isMethodMigrationComplete() {
        return neo4jMethodCount >= pgMethodCount;
    }

    /**
     * 判断入口点是否迁移完成
     */
    public boolean isEntryPointMigrationComplete() {
        return neo4jEntryPointCount >= pgEntryPointCount;
    }

    /**
     * 判断是否全部迁移完成
     */
    public boolean isFullyMigrated() {
        return isMethodMigrationComplete() && isEntryPointMigrationComplete();
    }

    /**
     * 获取待迁移方法节点数量
     */
    public long getPendingMethodCount() {
        return Math.max(0, pgMethodCount - neo4jMethodCount);
    }

    /**
     * 获取待迁移入口点数量
     */
    public long getPendingEntryPointCount() {
        return Math.max(0, pgEntryPointCount - neo4jEntryPointCount);
    }
}
