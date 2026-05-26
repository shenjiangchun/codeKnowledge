package com.huawei.hisi.ram.kg.dto;

/** A seed node returned by hybrid search. */
public record Seed(String nodeId, double score, String summary) {
}
