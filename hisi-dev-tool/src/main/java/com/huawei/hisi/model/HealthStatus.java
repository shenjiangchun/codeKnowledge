package com.huawei.hisi.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 健康状态响应
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HealthStatus {
    /**
     * 整体状态: UP, DOWN, DEGRADED
     */
    private String status;

    /**
     * 各组件状态
     */
    private Map<String, String> components;

    /**
     * 检查时间
     */
    private String checkTime;
}