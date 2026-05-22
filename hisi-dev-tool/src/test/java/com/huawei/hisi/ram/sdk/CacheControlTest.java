package com.huawei.hisi.ram.sdk;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CacheControl unit tests")
class CacheControlTest {

    @Test
    @DisplayName("CacheBlock serializes to Anthropic ephemeral cache_control format")
    @SuppressWarnings("unchecked")
    void cacheBlock_serializesAnthropicFormat() {
        Map<String, Object> block = CacheControl.system("hello").toAnthropicBlock();

        assertThat(block).containsEntry("type", "text");
        assertThat(block).containsEntry("text", "hello");
        Map<String, Object> cacheControl = (Map<String, Object>) block.get("cache_control");
        assertThat(cacheControl).containsEntry("type", "ephemeral");
    }

    @Test
    @DisplayName("L1 system tier marker is preserved in serialized block")
    @SuppressWarnings("unchecked")
    void cacheBlock_l1TierMarkerPreserved() {
        Map<String, Object> cacheControl =
                (Map<String, Object>) CacheControl.system("sys").toAnthropicBlock().get("cache_control");
        assertThat(cacheControl).containsEntry("tier", "L1_SYSTEM");
    }

    @Test
    @DisplayName("L2 project tier marker is preserved in serialized block")
    @SuppressWarnings("unchecked")
    void cacheBlock_l2TierMarkerPreserved() {
        Map<String, Object> cacheControl =
                (Map<String, Object>) CacheControl.project("proj").toAnthropicBlock().get("cache_control");
        assertThat(cacheControl).containsEntry("tier", "L2_PROJECT");
    }

    @Test
    @DisplayName("L3 session tier marker is preserved in serialized block")
    @SuppressWarnings("unchecked")
    void cacheBlock_l3TierMarkerPreserved() {
        Map<String, Object> cacheControl =
                (Map<String, Object>) CacheControl.session("sess").toAnthropicBlock().get("cache_control");
        assertThat(cacheControl).containsEntry("tier", "L3_SESSION");
    }
}
