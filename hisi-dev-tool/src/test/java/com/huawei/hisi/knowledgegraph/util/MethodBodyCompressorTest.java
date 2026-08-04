package com.huawei.hisi.knowledgegraph.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 方法体压缩工具测试
 */
class MethodBodyCompressorTest {

    @Test
    void testTruncate_nullInput() {
        assertNull(MethodBodyCompressor.truncate(null, 100));
    }

    @Test
    void testTruncate_shortString() {
        String input = "short string";
        assertEquals(input, MethodBodyCompressor.truncate(input, 100));
    }

    @Test
    void testTruncate_longString() {
        String input = "a".repeat(200);
        String result = MethodBodyCompressor.truncate(input, 100);
        assertEquals(100, result.length());
        assertEquals(input.substring(0, 100), result);
    }
}
