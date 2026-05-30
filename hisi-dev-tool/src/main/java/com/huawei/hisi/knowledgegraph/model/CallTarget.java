package com.huawei.hisi.knowledgegraph.model;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * 调用目标模型
 * 表示方法调用的目标，支持多种调用类型
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CallTarget {

    /**
     * 调用目标类型常量
     */
    public static final String TYPE_DIRECT = "DIRECT";
    public static final String TYPE_MAPPER = "MAPPER";
    public static final String TYPE_JPA = "JPA";
    public static final String TYPE_FEIGN = "FEIGN";
    public static final String TYPE_MQ = "MQ";
    public static final String TYPE_HTTP = "HTTP";

    /**
     * 调用类型: DIRECT/MAPPER/JPA/FEIGN/MQ/HTTP
     */
    private String type;

    /**
     * 接口/类名
     */
    private String interfaceName;

    /**
     * 方法名
     */
    private String methodName;

    /**
     * SQL ID (用于 Mapper 调用)
     * 格式: namespace.methodId
     */
    private String sqlId;

    /**
     * 目标服务名 (用于 Feign/HTTP 调用)
     */
    private String serviceName;

    /**
     * MQ Topic/Queue 名称 (用于 MQ 调用)
     */
    private String topic;

    /**
     * 创建直接调用目标
     */
    public static CallTarget direct(String interfaceName, String methodName) {
        return CallTarget.builder()
                .type(TYPE_DIRECT)
                .interfaceName(interfaceName)
                .methodName(methodName)
                .build();
    }

    /**
     * 创建 Mapper 调用目标
     */
    public static CallTarget mapper(String interfaceName, String methodName, String sqlId) {
        return CallTarget.builder()
                .type(TYPE_MAPPER)
                .interfaceName(interfaceName)
                .methodName(methodName)
                .sqlId(sqlId)
                .build();
    }

    /**
     * 创建 JPA Repository 调用目标
     */
    public static CallTarget jpaRepository(String interfaceName, String methodName) {
        return CallTarget.builder()
                .type(TYPE_JPA)
                .interfaceName(interfaceName)
                .methodName(methodName)
                .build();
    }

    /**
     * 创建 Feign Client 调用目标
     */
    public static CallTarget feignClient(String interfaceName, String methodName, String serviceName) {
        return CallTarget.builder()
                .type(TYPE_FEIGN)
                .interfaceName(interfaceName)
                .methodName(methodName)
                .serviceName(serviceName)
                .build();
    }

    /**
     * 创建 MQ Producer 调用目标
     */
    public static CallTarget mqProducer(String interfaceName, String methodName, String topic) {
        return CallTarget.builder()
                .type(TYPE_MQ)
                .interfaceName(interfaceName)
                .methodName(methodName)
                .topic(topic)
                .build();
    }

    /**
     * 创建 HTTP Client 调用目标
     */
    public static CallTarget httpClient(String interfaceName, String methodName, String serviceName) {
        return CallTarget.builder()
                .type(TYPE_HTTP)
                .interfaceName(interfaceName)
                .methodName(methodName)
                .serviceName(serviceName)
                .build();
    }

    /**
     * 检查是否是 Mapper 调用
     */
    public boolean isMapperCall() {
        return TYPE_MAPPER.equals(type);
    }

    /**
     * 检查是否是 JPA Repository 调用
     */
    public boolean isJpaRepositoryCall() {
        return TYPE_JPA.equals(type);
    }

    /**
     * 检查是否是 Feign Client 调用
     */
    public boolean isFeignCall() {
        return TYPE_FEIGN.equals(type);
    }

    /**
     * 检查是否是 MQ 调用
     */
    public boolean isMqCall() {
        return TYPE_MQ.equals(type);
    }

    /**
     * 检查是否是 HTTP 调用
     */
    public boolean isHttpCall() {
        return TYPE_HTTP.equals(type);
    }

    /**
     * 检查是否是直接调用
     */
    public boolean isDirectCall() {
        return TYPE_DIRECT.equals(type);
    }

    /**
     * 获取完整的方法签名
     */
    public String getFullSignature() {
        if (interfaceName == null || methodName == null) {
            return null;
        }
        return interfaceName + "." + methodName;
    }
}