package com.huawei.hisi.knowledgegraph.model;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.List;

/**
 * 桥接关系响应 DTO
 * 表示方法与其他组件之间的桥接关系
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BridgeRelation {
    /**
     * 源方法节点 ID
     */
    private String sourceNodeId;

    /**
     * 源方法类名
     */
    private String sourceClassName;

    /**
     * 源方法名
     */
    private String sourceMethodName;

    /**
     * 桥接类型: DIRECT/MQ/FEIGN/HTTP/MAPPER/JPA/ASPECT
     */
    private String bridgeType;

    /**
     * 目标标识
     * - Mapper: SQL ID
     * - Feign/HTTP: 服务名 + URI
     * - MQ: Topic 名称
     */
    private String targetIdentifier;

    /**
     * 目标详细信息
     */
    private BridgeTargetDetail targetDetail;

    /**
     * 调用行号
     */
    private Integer callLine;

    /**
     * 是否可跳转（是否有目标端点）
     */
    private boolean jumpable;

    /**
     * 目标方法节点 ID（如果存在）
     */
    private String targetNodeId;

    /**
     * 目标端点信息列表（可能有多个消费者/处理者）
     */
    private List<BridgeTargetDetail> targetDetails;

    /**
     * 目标详细信息内部类
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BridgeTargetDetail {
        /**
         * 目标类型: METHOD/SQL/TOPIC/ENDPOINT
         */
        private String targetType;

        /**
         * 目标节点 ID（方法节点）
         */
        private String targetNodeId;

        /**
         * 目标类名
         */
        private String targetClassName;

        /**
         * 目标方法名
         */
        private String targetMethodName;

        /**
         * SQL 语句（针对 Mapper 调用）
         */
        private String sqlStatement;

        /**
         * SQL 语句类型
         */
        private String statementType;

        /**
         * 服务名（针对 Feign/HTTP 调用）
         */
        private String serviceName;

        /**
         * HTTP 方法
         */
        private String httpMethod;

        /**
         * URI 模式
         */
        private String uriPattern;

        /**
         * Topic 名称（针对 MQ 调用）
         */
        private String topic;

        /**
         * 消息类型
         */
        private String messageType;

        /**
         * 消费者组
         */
        private String consumerGroup;
    }
}
