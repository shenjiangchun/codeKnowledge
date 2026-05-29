package com.huawei.hisi.knowledgegraph.model;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

/**
 * 向量生成任务模型
 * 用于追踪向量生成任务的状态和进度
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VectorGenerationTask {
    /**
     * 任务唯一标识
     */
    private Long id;

    /**
     * 项目路径
     */
    private String projectPath;

    /**
     * 任务状态: PENDING, RUNNING, COMPLETED, FAILED
     */
    private String status;

    /**
     * 总方法数
     */
    private Integer totalMethods;

    /**
     * 已处理方法数
     */
    private Integer processedMethods;

    /**
     * 开始时间
     */
    private LocalDateTime startTime;

    /**
     * 结束时间
     */
    private LocalDateTime endTime;

    /**
     * 耗时(毫秒)
     */
    private Long costTimeMs;

    /**
     * 错误信息
     */
    private String errorMessage;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;

    /**
     * 成功处理数量
     */
    private Integer successCount;

    /**
     * 失败处理数量
     */
    private Integer failCount;

    /**
     * 当前批次
     */
    private Integer currentBatch;

    /**
     * 平均每个方法耗时(毫秒)
     */
    private Integer avgTimePerMethodMs;
}
