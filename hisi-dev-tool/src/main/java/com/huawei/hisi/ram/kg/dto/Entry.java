package com.huawei.hisi.ram.kg.dto;

/** An entry / caller node (Controller, Scheduled, MQ listener, Feign client, ...). */
public record Entry(String nodeId, String className, String methodName, String type) {
}
