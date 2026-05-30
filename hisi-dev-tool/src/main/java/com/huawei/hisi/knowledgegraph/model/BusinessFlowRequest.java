package com.huawei.hisi.knowledgegraph.model;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import jakarta.validation.constraints.NotBlank;

/**
 * 业务流程生成请求 DTO
 * 接收调用链数据用于生成业务流程图
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BusinessFlowRequest {

    /**
     * 调用链数据（JSON 格式）
     * 包含入口点、调用关系、方法节点等信息
     */
    @NotBlank(message = "调用链数据不能为空")
    private String callChainData;

    /**
     * 项目路径
     */
    private String projectPath;

    /**
     * 入口点标识（可选，用于指定分析的入口）
     */
    private String entryPointKey;

    /**
     * 最大深度限制（可选，用于限制分析的调用深度）
     */
    private Integer maxDepth;

    /**
     * 是否包含详细说明
     */
    @Builder.Default
    private Boolean includeDescription = true;
}
