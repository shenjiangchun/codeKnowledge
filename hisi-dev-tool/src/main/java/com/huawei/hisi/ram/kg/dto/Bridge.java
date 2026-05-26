package com.huawei.hisi.ram.kg.dto;

/** A bridge node connecting modules / services (Feign / MQ / generic bridge). */
public record Bridge(String nodeId, String bridgeType, String target) {
}
