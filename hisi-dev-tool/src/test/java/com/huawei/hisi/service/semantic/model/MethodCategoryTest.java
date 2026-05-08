package com.huawei.hisi.service.semantic.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MethodCategory 单元测试
 *
 * 测试方法分类枚举的功能
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@DisplayName("MethodCategory 单元测试")
class MethodCategoryTest {

    @Test
    @DisplayName("测试枚举值数量")
    void testEnumCount() {
        MethodCategory[] categories = MethodCategory.values();
        assertEquals(8, categories.length);
    }

    @Test
    @DisplayName("测试枚举值名称")
    void testEnumNames() {
        assertEquals("BUSINESS_LOGIC", MethodCategory.BUSINESS_LOGIC.name());
        assertEquals("DATA_ACCESS", MethodCategory.DATA_ACCESS.name());
        assertEquals("UTIL_METHOD", MethodCategory.UTIL_METHOD.name());
        assertEquals("EXCEPTION_HANDLER", MethodCategory.EXCEPTION_HANDLER.name());
        assertEquals("CONFIGURATION", MethodCategory.CONFIGURATION.name());
        assertEquals("TEST_METHOD", MethodCategory.TEST_METHOD.name());
        assertEquals("API_ENDPOINT", MethodCategory.API_ENDPOINT.name());
        assertEquals("OTHER", MethodCategory.OTHER.name());
    }

    @Test
    @DisplayName("测试枚举描述")
    void testEnumDescriptions() {
        assertEquals("业务逻辑", MethodCategory.BUSINESS_LOGIC.getDescription());
        assertEquals("数据访问", MethodCategory.DATA_ACCESS.getDescription());
        assertEquals("工具方法", MethodCategory.UTIL_METHOD.getDescription());
        assertEquals("异常处理", MethodCategory.EXCEPTION_HANDLER.getDescription());
        assertEquals("配置/初始化", MethodCategory.CONFIGURATION.getDescription());
        assertEquals("测试方法", MethodCategory.TEST_METHOD.getDescription());
        assertEquals("API接口", MethodCategory.API_ENDPOINT.getDescription());
        assertEquals("其他", MethodCategory.OTHER.getDescription());
    }

    @Test
    @DisplayName("测试 fromString - 使用名称")
    void testFromStringWithEnumName() {
        assertEquals(MethodCategory.BUSINESS_LOGIC, MethodCategory.fromString("BUSINESS_LOGIC"));
        assertEquals(MethodCategory.DATA_ACCESS, MethodCategory.fromString("DATA_ACCESS"));
        assertEquals(MethodCategory.API_ENDPOINT, MethodCategory.fromString("API_ENDPOINT"));
    }

    @Test
    @DisplayName("测试 fromString - 使用小写名称")
    void testFromStringWithLowerCase() {
        assertEquals(MethodCategory.BUSINESS_LOGIC, MethodCategory.fromString("business_logic"));
        assertEquals(MethodCategory.DATA_ACCESS, MethodCategory.fromString("data_access"));
        assertEquals(MethodCategory.OTHER, MethodCategory.fromString("other"));
    }

    @Test
    @DisplayName("测试 fromString - 使用描述")
    void testFromStringWithDescription() {
        assertEquals(MethodCategory.BUSINESS_LOGIC, MethodCategory.fromString("业务逻辑"));
        assertEquals(MethodCategory.DATA_ACCESS, MethodCategory.fromString("数据访问"));
        assertEquals(MethodCategory.UTIL_METHOD, MethodCategory.fromString("工具方法"));
        assertEquals(MethodCategory.EXCEPTION_HANDLER, MethodCategory.fromString("异常处理"));
        assertEquals(MethodCategory.CONFIGURATION, MethodCategory.fromString("配置/初始化"));
        assertEquals(MethodCategory.TEST_METHOD, MethodCategory.fromString("测试方法"));
        assertEquals(MethodCategory.API_ENDPOINT, MethodCategory.fromString("API接口"));
        assertEquals(MethodCategory.OTHER, MethodCategory.fromString("其他"));
    }

    @Test
    @DisplayName("测试 fromString - null 输入")
    void testFromStringNull() {
        assertEquals(MethodCategory.OTHER, MethodCategory.fromString(null));
    }

    @Test
    @DisplayName("测试 fromString - 无效输入")
    void testFromStringInvalid() {
        assertEquals(MethodCategory.OTHER, MethodCategory.fromString("invalid_category"));
        assertEquals(MethodCategory.OTHER, MethodCategory.fromString(""));
        assertEquals(MethodCategory.OTHER, MethodCategory.fromString("未知分类"));
    }

    @Test
    @DisplayName("测试 fromString - 部分匹配")
    void testFromStringPartialMatch() {
        // 只有完全匹配才会返回正确的分类
        assertEquals(MethodCategory.OTHER, MethodCategory.fromString("业务"));
        assertEquals(MethodCategory.OTHER, MethodCategory.fromString("数据"));
    }

    @Test
    @DisplayName("测试枚举顺序")
    void testEnumOrder() {
        MethodCategory[] categories = MethodCategory.values();
        assertEquals(MethodCategory.BUSINESS_LOGIC, categories[0]);
        assertEquals(MethodCategory.DATA_ACCESS, categories[1]);
        assertEquals(MethodCategory.UTIL_METHOD, categories[2]);
        assertEquals(MethodCategory.EXCEPTION_HANDLER, categories[3]);
        assertEquals(MethodCategory.CONFIGURATION, categories[4]);
        assertEquals(MethodCategory.TEST_METHOD, categories[5]);
        assertEquals(MethodCategory.API_ENDPOINT, categories[6]);
        assertEquals(MethodCategory.OTHER, categories[7]);
    }

    @Test
    @DisplayName("测试 valueOf 方法")
    void testValueOf() {
        assertEquals(MethodCategory.BUSINESS_LOGIC, MethodCategory.valueOf("BUSINESS_LOGIC"));
        assertEquals(MethodCategory.TEST_METHOD, MethodCategory.valueOf("TEST_METHOD"));
    }

    @Test
    @DisplayName("测试 valueOf - 无效名称抛出异常")
    void testValueOfInvalid() {
        assertThrows(IllegalArgumentException.class, () -> {
            MethodCategory.valueOf("INVALID");
        });
    }

    @Test
    @DisplayName("测试所有分类都有描述")
    void testAllCategoriesHaveDescription() {
        for (MethodCategory category : MethodCategory.values()) {
            assertNotNull(category.getDescription());
            assertFalse(category.getDescription().isEmpty());
        }
    }

    @Test
    @DisplayName("测试 fromString 边界情况")
    void testFromStringEdgeCases() {
        // 空格
        assertEquals(MethodCategory.OTHER, MethodCategory.fromString("  "));
        // 特殊字符
        assertEquals(MethodCategory.OTHER, MethodCategory.fromString("@business"));
        // 数字
        assertEquals(MethodCategory.OTHER, MethodCategory.fromString("123"));
    }
}