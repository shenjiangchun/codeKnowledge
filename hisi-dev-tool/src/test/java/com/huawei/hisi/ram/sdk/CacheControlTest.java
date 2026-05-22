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
}
