package com.huawei.hisi.knowledgegraph.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回归测试：VectorGenerationService.fileLog 的占位符渲染。
 *
 * <p>背景 bug：fileLog(String, Object...) 原本用 String.format，但调用方大量传 slf4j
 * 风格的 {@code {}} 占位符，导致 String.format 把 {@code {}} 当字面量输出、参数被丢弃，
 * 日志里出现 "id={}, total={}" 这种空占位符。
 *
 * <p>本测试锁定 formatLogMessage 的 {@code {}} 替换行为，防止回归。
 */
class VectorGenerationServiceLogFormatTest {

    @Test
    void replacesBracesWithArgs() {
        assertThat(VectorGenerationService.formatLogMessage("id={}, total={}", 257, 3))
            .isEqualTo("id=257, total=3");
    }

    @Test
    void replacesSingleArg() {
        assertThat(VectorGenerationService.formatLogMessage("总方法数={}", 3))
            .isEqualTo("总方法数=3");
    }

    @Test
    void noBraces_returnsFormatUnchanged() {
        assertThat(VectorGenerationService.formatLogMessage("开始处理"))
            .isEqualTo("开始处理");
    }

    @Test
    void moreBracesThanArgs_leavesTrailingBraces() {
        assertThat(VectorGenerationService.formatLogMessage("a={}, b={}", 1))
            .isEqualTo("a=1, b={}");
    }

    @Test
    void extraArgs_areAppended() {
        assertThat(VectorGenerationService.formatLogMessage("a={}", 1, 2))
            .isEqualTo("a=1 2");
    }

    @Test
    void nullArg_rendersNull() {
        assertThat(VectorGenerationService.formatLogMessage("error={}", (Object) null))
            .isEqualTo("error=null");
    }
}
