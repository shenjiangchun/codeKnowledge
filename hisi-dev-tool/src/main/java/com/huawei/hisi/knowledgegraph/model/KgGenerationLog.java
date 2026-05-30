package com.huawei.hisi.knowledgegraph.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 知识图谱生成日志
 * 记录每次生成的 Git 状态和统计信息，用于增量更新
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KgGenerationLog {

    /**
     * 主键ID
     */
    private Long id;

    /**
     * 项目路径
     */
    private String projectPath;

    /**
     * Git commit hash
     */
    private String commitHash;

    /**
     * Git 分支名
     */
    private String branch;

    /**
     * 生成模式：FULL / INCREMENTAL
     */
    private String generationMode;

    /**
     * 方法总数
     */
    private Integer totalMethods;

    /**
     * 新增方法数
     */
    private Integer newMethods;

    /**
     * 更新方法数
     */
    private Integer updatedMethods;

    /**
     * 删除方法数
     */
    private Integer deletedMethods;

    /**
     * 开始时间
     */
    private LocalDateTime startTime;

    /**
     * 结束时间
     */
    private LocalDateTime endTime;

    /**
     * 耗时（毫秒）
     */
    private Long costTimeMs;

    /**
     * 错误信息
     */
    private String errorMessage;
}
