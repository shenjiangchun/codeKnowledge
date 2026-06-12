package com.huawei.hisi.model;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * 应用日志配置
 * 用于配置定时日志拉取任务
 *
 * Task 6: Configuration model for scheduled log pulling
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AppLogConfig {

    /**
     * 配置ID
     */
    private Long id;

    /**
     * 应用ID (e.g., "hiapm")
     */
    private String appId;

    /**
     * 本地项目路径
     */
    private String projectPath;

    /**
     * DSL查询语句 (JSON格式)
     */
    private String dslQuery;

    /**
     * 拉取间隔（分钟）
     */
    private Integer pullIntervalMinutes;

    /**
     * 是否启用
     */
    private Boolean enabled;

    /**
     * 上次拉取时间
     */
    private Long lastPullAt;
}