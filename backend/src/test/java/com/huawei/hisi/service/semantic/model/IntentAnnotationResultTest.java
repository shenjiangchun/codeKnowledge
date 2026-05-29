package com.huawei.hisi.service.semantic.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * IntentAnnotationResult 单元测试
 *
 * 测试意图标注结果模型的功能
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@DisplayName("IntentAnnotationResult 单元测试")
class IntentAnnotationResultTest {

    @Test
    @DisplayName("测试构建成功的标注结果")
    void testBuildSuccessResult() {
        IntentAnnotationResult result = IntentAnnotationResult.builder()
                .success(true)
                .intent("处理用户登录请求")
                .keywords(List.of("登录", "验证", "用户"))
                .category(MethodCategory.API_ENDPOINT)
                .annotationTimeMs(150)
                .build();

        assertTrue(result.isSuccess());
        assertEquals("处理用户登录请求", result.getIntent());
        assertEquals(3, result.getKeywords().size());
        assertEquals(MethodCategory.API_ENDPOINT, result.getCategory());
        assertEquals(150, result.getAnnotationTimeMs());
        assertNull(result.getErrorMessage());
    }

    @Test
    @DisplayName("测试构建失败的标注结果")
    void testBuildFailureResult() {
        IntentAnnotationResult result = IntentAnnotationResult.builder()
                .success(false)
                .errorMessage("LLM服务不可用")
                .intent("无法解析")
                .keywords(List.of())
                .category(MethodCategory.OTHER)
                .build();

        assertFalse(result.isSuccess());
        assertEquals("LLM服务不可用", result.getErrorMessage());
        assertEquals("无法解析", result.getIntent());
        assertTrue(result.getKeywords().isEmpty());
        assertEquals(MethodCategory.OTHER, result.getCategory());
    }

    @Test
    @DisplayName("测试静态工厂方法 - failure")
    void testStaticFailureMethod() {
        IntentAnnotationResult result = IntentAnnotationResult.failure("解析超时");

        assertFalse(result.isSuccess());
        assertEquals("解析超时", result.getErrorMessage());
        assertEquals("无法解析", result.getIntent());
        assertTrue(result.getKeywords().isEmpty());
        assertEquals(MethodCategory.OTHER, result.getCategory());
    }

    @Test
    @DisplayName("测试静态工厂方法 - success")
    void testStaticSuccessMethod() {
        List<String> keywords = List.of("数据库", "查询");
        IntentAnnotationResult result = IntentAnnotationResult.success(
                "执行数据库查询",
                keywords,
                MethodCategory.DATA_ACCESS,
                200
        );

        assertTrue(result.isSuccess());
        assertEquals("执行数据库查询", result.getIntent());
        assertEquals(keywords, result.getKeywords());
        assertEquals(MethodCategory.DATA_ACCESS, result.getCategory());
        assertEquals(200, result.getAnnotationTimeMs());
    }

    @Test
    @DisplayName("测试空关键词列表")
    void testEmptyKeywords() {
        IntentAnnotationResult result = IntentAnnotationResult.builder()
                .success(true)
                .keywords(List.of())
                .build();

        assertTrue(result.getKeywords().isEmpty());
    }

    @Test
    @DisplayName("测试长意图描述")
    void testLongIntent() {
        String longIntent = "这是一个非常长的方法意图描述，包含了详细的业务逻辑说明和功能描述，用于测试长文本的处理能力";
        IntentAnnotationResult result = IntentAnnotationResult.builder()
                .intent(longIntent)
                .success(true)
                .build();

        assertEquals(longIntent, result.getIntent());
    }

    @Test
    @DisplayName("测试多个关键词")
    void testMultipleKeywords() {
        List<String> keywords = List.of("用户", "登录", "验证", "token", "session");
        IntentAnnotationResult result = IntentAnnotationResult.builder()
                .keywords(keywords)
                .build();

        assertEquals(5, result.getKeywords().size());
        assertTrue(result.getKeywords().contains("用户"));
        assertTrue(result.getKeywords().contains("token"));
    }

    @Test
    @DisplayName("测试不同分类")
    void testDifferentCategories() {
        IntentAnnotationResult businessResult = IntentAnnotationResult.success(
                "业务处理",
                List.of("业务"),
                MethodCategory.BUSINESS_LOGIC,
                100
        );
        assertEquals(MethodCategory.BUSINESS_LOGIC, businessResult.getCategory());

        IntentAnnotationResult dataResult = IntentAnnotationResult.success(
                "数据访问",
                List.of("数据库"),
                MethodCategory.DATA_ACCESS,
                50
        );
        assertEquals(MethodCategory.DATA_ACCESS, dataResult.getCategory());

        IntentAnnotationResult testResult = IntentAnnotationResult.success(
                "单元测试",
                List.of("测试"),
                MethodCategory.TEST_METHOD,
                30
        );
        assertEquals(MethodCategory.TEST_METHOD, testResult.getCategory());
    }

    @Test
    @DisplayName("测试标注耗时边界值")
    void testAnnotationTimeBounds() {
        // 最小耗时
        IntentAnnotationResult minResult = IntentAnnotationResult.builder()
                .annotationTimeMs(0)
                .build();
        assertEquals(0, minResult.getAnnotationTimeMs());

        // 大耗时
        IntentAnnotationResult maxResult = IntentAnnotationResult.builder()
                .annotationTimeMs(5000)
                .build();
        assertEquals(5000, maxResult.getAnnotationTimeMs());
    }

    @Test
    @DisplayName("测试空构造和setter")
    void testEmptyConstructorAndSetter() {
        IntentAnnotationResult result = new IntentAnnotationResult();
        result.setSuccess(true);
        result.setIntent("测试意图");
        result.setKeywords(List.of("test"));
        result.setCategory(MethodCategory.UTIL_METHOD);
        result.setAnnotationTimeMs(100);

        assertTrue(result.isSuccess());
        assertEquals("测试意图", result.getIntent());
        assertEquals(MethodCategory.UTIL_METHOD, result.getCategory());
    }

    @Test
    @DisplayName("测试失败结果默认耗时为0")
    void testFailureResultWithoutTime() {
        IntentAnnotationResult result = IntentAnnotationResult.failure("错误");

        // 失败结果默认annotationTimeMs为0（基本类型默认值）
        assertEquals(0L, result.getAnnotationTimeMs());
    }

    @Test
    @DisplayName("测试特殊字符意图")
    void testSpecialCharactersIntent() {
        IntentAnnotationResult result = IntentAnnotationResult.builder()
                .intent("处理JSON格式数据: {\"key\": \"value\"}")
                .success(true)
                .build();

        assertEquals("处理JSON格式数据: {\"key\": \"value\"}", result.getIntent());
    }

    @Test
    @DisplayName("测试中文关键词")
    void testChineseKeywords() {
        List<String> keywords = List.of("用户管理", "权限控制", "登录验证");
        IntentAnnotationResult result = IntentAnnotationResult.builder()
                .keywords(keywords)
                .success(true)
                .build();

        assertEquals(3, result.getKeywords().size());
        assertTrue(result.getKeywords().contains("用户管理"));
    }

    @Test
    @DisplayName("测试英文关键词")
    void testEnglishKeywords() {
        List<String> keywords = List.of("user", "management", "authentication");
        IntentAnnotationResult result = IntentAnnotationResult.builder()
                .keywords(keywords)
                .success(true)
                .build();

        assertEquals(3, result.getKeywords().size());
        assertTrue(result.getKeywords().contains("authentication"));
    }

    @Test
    @DisplayName("测试混合关键词")
    void testMixedKeywords() {
        List<String> keywords = List.of("用户", "User", "登录", "Login");
        IntentAnnotationResult result = IntentAnnotationResult.builder()
                .keywords(keywords)
                .build();

        assertEquals(4, result.getKeywords().size());
    }
}