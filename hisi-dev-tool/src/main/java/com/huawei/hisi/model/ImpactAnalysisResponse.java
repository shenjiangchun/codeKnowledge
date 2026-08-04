package com.huawei.hisi.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 影响范围分析响应
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImpactAnalysisResponse {
    /**
     * 目标方法（className.methodName）
     */
    private String targetMethod;

    /**
     * 受影响的方法列表
     */
    private List<String> affectedMethods;

    /**
     * 受影响的 URI 列表
     */
    private List<String> affectedUris;

    /**
     * 调用深度
     */
    private int depth;

    /**
     * 分析时间
     */
    private String analysisTime;
}