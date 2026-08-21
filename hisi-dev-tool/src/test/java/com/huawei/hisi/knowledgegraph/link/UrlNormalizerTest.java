package com.huawei.hisi.knowledgegraph.link;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("UrlNormalizer")
class UrlNormalizerTest {

    @Test
    @DisplayName("前端模板 URL 归一化去掉 /api 前缀并归一化路径参数")
    void normalizeFrontendUrl_templateAndApiPrefix() {
        assertThat(UrlNormalizer.normalizeFrontendUrl("/api/callchain/analysis/project/${projectName}"))
            .isEqualTo("/callchain/analysis/project/:param");
    }

    @Test
    @DisplayName("后端 entryKey 归一化提取方法并归一化路径参数")
    void normalizeEntryKey_methodAndPathParam() {
        assertThat(UrlNormalizer.normalizeEntryKey("DELETE /api/callchain/analysis/project/{projectName}"))
            .isEqualTo("DELETE /callchain/analysis/project/:param");
    }

    @Test
    @DisplayName("前后端归一化后可精确匹配")
    void normalizedValuesMatch() {
        String frontend = UrlNormalizer.normalizeFrontendUrl("/api/projects/${id}");
        String backend = UrlNormalizer.normalizeEntryKey("GET /api/projects/{id}");
        assertThat(frontend).isEqualTo("/projects/:param");
        assertThat(backend).isEqualTo("GET /projects/:param");
        // 路径部分一致（方法前缀由 caller 单独比对）
        assertThat(backend.substring(backend.indexOf(' ') + 1)).isEqualTo(frontend);
    }

    @Test
    @DisplayName("空输入安全")
    void nullInputs_safe() {
        assertThat(UrlNormalizer.normalizeFrontendUrl(null)).isEqualTo("");
        assertThat(UrlNormalizer.normalizeEntryKey(null)).isEqualTo("");
    }
}
