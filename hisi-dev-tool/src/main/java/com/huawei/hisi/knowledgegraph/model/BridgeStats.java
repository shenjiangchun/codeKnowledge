package com.huawei.hisi.knowledgegraph.model;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.Map;

/**
 * 桥接统计信息 DTO
 * 表示项目中各类桥接关系的统计信息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BridgeStats {
    /**
     * 项目路径
     */
    private String projectPath;

    /**
     * 总调用关系数
     */
    private int totalCallRelations;

    /**
     * 总桥接关系数（非 DIRECT 调用）
     */
    private int totalBridges;

    /**
     * 各类型桥接数量统计
     * key: bridgeType (MAPPER/MQ/FEIGN/HTTP/JPA/ASPECT)
     * value: 数量
     */
    private Map<String, Integer> bridgeTypeCounts;

    /**
     * Mapper 调用数
     */
    private int mapperCallCount;

    /**
     * Feign 调用数
     */
    private int feignCallCount;

    /**
     * HTTP 调用数
     */
    private int httpCallCount;

    /**
     * MQ 调用数
     */
    private int mqCallCount;

    /**
     * JPA 调用数
     */
    private int jpaCallCount;

    /**
     * Aspect 切面调用数
     */
    private int aspectCallCount;

    /**
     * MyBatis Mapper SQL 数量
     */
    private int myBatisSqlCount;

    /**
     * MyBatis Mapper 接口数量
     */
    private int myBatisMapperCount;

    /**
     * 跳转率（可跳转桥接数 / 总桥接数）
     */
    private double jumpableRate;

    /**
     * 外部服务调用统计
     * key: serviceName
     * value: 调用次数
     */
    private Map<String, Integer> externalServiceCalls;

    /**
     * MQ Topic 调用统计
     * key: topic
     * value: 调用次数
     */
    private Map<String, Integer> mqTopicCalls;
}
