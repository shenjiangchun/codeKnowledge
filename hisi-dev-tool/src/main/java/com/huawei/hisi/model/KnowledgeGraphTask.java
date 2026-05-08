package com.huawei.hisi.model;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 知识图谱生成任务模型类
 * 对应 knowledge_graph_task 数据库表
 */
@Data
public class KnowledgeGraphTask {
    /**
     * 任务ID
     */
    private Long id;

    /**
     * 项目名称
     */
    private String projectName;

    /**
     * 项目完整路径
     */
    private String projectPath;

    /**
     * 任务状态: PENDING/RUNNING/COMPLETED/FAILED
     */
    private String status;

    /**
     * 任务开始时间
     */
    private LocalDateTime startTime;

    /**
     * 任务结束时间
     */
    private LocalDateTime endTime;

    /**
     * 失败时的错误信息
     */
    private String errorMessage;

    /**
     * 已处理的记录数
     */
    private Integer recordsProcessed;

    /**
     * 方法节点数量
     */
    private Integer methodNodeCount;

    /**
     * 调用关系数量
     */
    private Integer callRelationCount;

    /**
     * 入口点数量
     */
    private Integer entryPointCount;

    /**
     * 调用链数量
     */
    private Integer callChainCount;

    /**
     * 接口实现数量
     */
    private Integer interfaceImplCount;

    /**
     * 执行耗时（毫秒）
     */
    private Long costTimeMs;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
}