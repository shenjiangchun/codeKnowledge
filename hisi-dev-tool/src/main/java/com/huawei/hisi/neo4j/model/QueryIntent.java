package com.huawei.hisi.neo4j.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 查询意图模型
 * 表示从用户查询中解析出的意图信息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueryIntent {

    /**
     * 核心实体
     * 例如: UserService, OrderController
     */
    private String entity;

    /**
     * 方法类型
     * 例如: create, delete, update, query
     */
    private String methodType;

    /**
     * 微服务名
     * 例如: user-service, order-service
     */
    private String serviceName;

    /**
     * 关键词列表
     * 从查询中提取的关键词
     */
    private List<String> keywords;
}
