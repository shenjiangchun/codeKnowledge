package com.huawei.hisi.neo4j.model;

import org.springframework.http.HttpStatus;

/**
 * 搜索相关错误码枚举
 * 定义搜索 API 可能返回的错误类型和用户友好消息
 */
public enum SearchErrorCode {

    /**
     * 查询内容过短
     */
    QUERY_TOO_SHORT(HttpStatus.BAD_REQUEST, "搜索内容过短，请输入至少 2 个字符"),

    /**
     * Embedding 服务不可用
     */
    EMBEDDING_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "语义搜索暂时不可用，已切换到关键词搜索"),

    /**
     * 图谱服务异常
     */
    GRAPH_SERVICE_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "图谱服务异常，请稍后重试"),

    /**
     * 搜索超时
     */
    TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "搜索超时，请尝试简化搜索条件"),

    /**
     * 未知错误
     */
    UNKNOWN_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "搜索服务异常，请稍后重试");

    private final HttpStatus httpStatus;
    private final String userMessage;

    SearchErrorCode(HttpStatus httpStatus, String userMessage) {
        this.httpStatus = httpStatus;
        this.userMessage = userMessage;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    public String getUserMessage() {
        return userMessage;
    }

    /**
     * 获取错误码数值
     */
    public int getCode() {
        return httpStatus.value();
    }
}
