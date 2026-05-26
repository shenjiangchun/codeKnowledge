package com.huawei.hisi.ram.safety;

/**
 * Circuit-breaker decision returned by {@link CircuitBreaker#check}.
 *
 * @param tripped  true when a hard limit has been exceeded
 * @param fallback recommended fallback action
 * @param reason   human-readable reason
 */
public record Decision(boolean tripped, Fallback fallback, String reason) {
}
