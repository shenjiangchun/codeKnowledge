package com.huawei.hisi.neo4j.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 迁移结果模型
 * 记录数据迁移的统计信息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MigrationResult {

    /**
     * 迁移类型: METHOD_NODE/CALL_RELATION/ENTRY_POINT/ALL
     */
    private String migrationType;

    /**
     * 总记录数
     */
    private long totalCount;

    /**
     * 成功迁移数量
     */
    private long migratedCount;

    /**
     * 失败数量
     */
    private long failedCount;

    /**
     * 跳过数量
     */
    private long skippedCount;

    /**
     * 是否成功
     */
    private boolean success;

    /**
     * 错误信息
     */
    private String errorMessage;

    /**
     * 迁移耗时(毫秒)
     */
    private long durationMs;

    /**
     * 项目路径
     */
    private String projectPath;

    /**
     * 创建成功结果
     */
    public static MigrationResult success(String migrationType, String projectPath, long total, long migrated) {
        return MigrationResult.builder()
            .migrationType(migrationType)
            .projectPath(projectPath)
            .totalCount(total)
            .migratedCount(migrated)
            .failedCount(0)
            .skippedCount(0)
            .success(true)
            .build();
    }

    /**
     * 创建失败结果
     */
    public static MigrationResult failure(String migrationType, String projectPath, String errorMessage) {
        return MigrationResult.builder()
            .migrationType(migrationType)
            .projectPath(projectPath)
            .success(false)
            .errorMessage(errorMessage)
            .build();
    }

    /**
     * 迁移类型常量
     */
    public static final String TYPE_METHOD_NODE = "METHOD_NODE";
    public static final String TYPE_CALL_RELATION = "CALL_RELATION";
    public static final String TYPE_ENTRY_POINT = "ENTRY_POINT";
    public static final String TYPE_ALL = "ALL";
}
