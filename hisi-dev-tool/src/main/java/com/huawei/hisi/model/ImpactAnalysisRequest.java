package com.huawei.hisi.model;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 影响范围分析请求
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@Data
public class ImpactAnalysisRequest {
    /**
     * 类名（全限定名）
     */
    @NotBlank(message = "类名不能为空")
    private String className;

    /**
     * 方法名
     */
    @NotBlank(message = "方法名不能为空")
    private String methodName;

    /**
     * 项目名称（可选）
     */
    private String project;
}