package com.huawei.hisi.knowledgegraph.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 测试 BuildMode 枚举的字符串解析（大小写不敏感、未知值回退 REUSE）。
 */
class BuildModeTest {

    @Test
    @DisplayName("解析 reuse（默认）")
    void shouldParseReuse() {
        assertThat(BuildMode.fromString("reuse")).isEqualTo(BuildMode.REUSE);
    }

    @Test
    @DisplayName("解析 wipe")
    void shouldParseWipe() {
        assertThat(BuildMode.fromString("wipe")).isEqualTo(BuildMode.WIPE);
    }

    @Test
    @DisplayName("解析 incremental")
    void shouldParseIncremental() {
        assertThat(BuildMode.fromString("incremental")).isEqualTo(BuildMode.INCREMENTAL);
    }

    @Test
    @DisplayName("大小写不敏感")
    void shouldBeCaseInsensitive() {
        assertThat(BuildMode.fromString("REUSE")).isEqualTo(BuildMode.REUSE);
        assertThat(BuildMode.fromString("Wipe")).isEqualTo(BuildMode.WIPE);
    }

    @Test
    @DisplayName("null 或空串回退 REUSE")
    void shouldDefaultToReuseWhenNullOrBlank() {
        assertThat(BuildMode.fromString(null)).isEqualTo(BuildMode.REUSE);
        assertThat(BuildMode.fromString("")).isEqualTo(BuildMode.REUSE);
        assertThat(BuildMode.fromString("  ")).isEqualTo(BuildMode.REUSE);
    }

    @Test
    @DisplayName("未知值回退 REUSE")
    void shouldDefaultToReuseWhenUnknown() {
        assertThat(BuildMode.fromString("unknown")).isEqualTo(BuildMode.REUSE);
    }
}
