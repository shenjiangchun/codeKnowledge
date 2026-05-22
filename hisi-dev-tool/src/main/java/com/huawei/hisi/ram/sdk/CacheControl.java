package com.huawei.hisi.ram.sdk;

import java.util.Map;
import java.util.Objects;

/**
 * Utility records and factories for annotating LLM prompt fragments with
 * cache-control metadata for Anthropic's prompt caching feature.
 *
 * <p>Three logical tiers are exposed so callers can express intent without
 * hardcoding cache TTLs (Anthropic currently exposes a single
 * {@code ephemeral} cache type, mapped uniformly across all tiers):
 * <ul>
 *   <li>{@link CacheTier#L1_SYSTEM} — long-lived system prompt fragments</li>
 *   <li>{@link CacheTier#L2_PROJECT} — per-project context (repo layout,
 *       conventions)</li>
 *   <li>{@link CacheTier#L3_SESSION} — short-lived per-session context</li>
 * </ul>
 */
public final class CacheControl {

    private CacheControl() {
    }

    public enum CacheTier {
        L1_SYSTEM,
        L2_PROJECT,
        L3_SESSION
    }

    public record CacheBlock(String text, CacheTier tier) {
        public CacheBlock {
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(tier, "tier");
        }

        public Map<String, Object> toAnthropicBlock() {
            return Map.of(
                    "type", "text",
                    "text", text,
                    "cache_control", Map.of("type", "ephemeral")
            );
        }
    }

    public static CacheBlock system(String text) {
        return new CacheBlock(text, CacheTier.L1_SYSTEM);
    }

    public static CacheBlock project(String text) {
        return new CacheBlock(text, CacheTier.L2_PROJECT);
    }

    public static CacheBlock session(String text) {
        return new CacheBlock(text, CacheTier.L3_SESSION);
    }
}
