package com.huawei.hisi.knowledgegraph.model;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 业务流程生成响应 DTO
 * 包含 Mermaid 格式的业务流程图和说明文字
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BusinessFlowResponse {

    /**
     * 请求 ID
     */
    private String requestId;

    /**
     * Mermaid 流程图代码
     */
    private String mermaidDiagram;

    /**
     * 业务流程说明
     */
    private String description;

    /**
     * 流程步骤列表
     */
    private List<FlowStep> steps;

    /**
     * 关键节点列表
     */
    private List<KeyNode> keyNodes;

    /**
     * 生成时间
     */
    private LocalDateTime generatedAt;

    /**
     * 生成是否成功
     */
    @Builder.Default
    private Boolean success = true;

    /**
     * 错误信息（如果失败）
     */
    private String errorMessage;

    /**
     * 流程步骤
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FlowStep {
        /**
         * 步骤序号
         */
        private Integer order;

        /**
         * 步骤名称
         */
        private String name;

        /**
         * 步骤描述
         */
        private String description;

        /**
         * 涉及的组件/类
         */
        private String component;

        /**
         * 操作类型
         */
        private String operationType;

        /**
         * 是否为关键步骤
         */
        @Builder.Default
        private Boolean isKeyStep = false;
    }

    /**
     * 关键节点
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class KeyNode {
        /**
         * 节点 ID
         */
        private String nodeId;

        /**
         * 节点名称
         */
        private String name;

        /**
         * 节点类型（ENTRY, DATABASE, EXTERNAL_SERVICE, CACHE, BUSINESS_LOGIC）
         */
        private String type;

        /**
         * 节点说明
         */
        private String description;

        /**
         * 风险等级（LOW, MEDIUM, HIGH, CRITICAL）
         */
        private String riskLevel;
    }
}
