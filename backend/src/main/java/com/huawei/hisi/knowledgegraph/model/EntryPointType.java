package com.huawei.hisi.knowledgegraph.model;

/**
 * 代码入口点类型枚举
 * 定义了系统中所有可能的代码入口类型
 */
public enum EntryPointType {
    HTTP("HTTP", "REST API入口"),
    SCHEDULED("SCHEDULED", "定时任务入口"),
    MQ("MQ", "消息队列消费者入口"),
    EVENT("EVENT", "事件监听器入口"),
    WEBSOCKET("WEBSOCKET", "WebSocket入口"),
    RPC("RPC", "远程服务入口"),
    LIFECYCLE("LIFECYCLE", "生命周期入口"),
    FASTAPI_ROUTE("FASTAPI_ROUTE", "FastAPI 路由入口"),
    FLASK_ROUTE("FLASK_ROUTE", "Flask 路由入口"),
    DJANGO_VIEW("DJANGO_VIEW", "Django 视图入口"),
    CELERY_TASK("CELERY_TASK", "Celery 任务入口"),
    FEIGN_CLIENT("FEIGN_CLIENT", "Feign 客户端入口");

    private final String code;
    private final String description;

    EntryPointType(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }
}
