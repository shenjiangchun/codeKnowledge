package com.huawei.hisi.neo4j.service;

import com.huawei.hisi.neo4j.model.QueryType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * QueryTypeDetector 单元测试
 * 测试多规则评分机制识别查询类型的正确性
 */
class QueryTypeDetectorTest {

    private QueryTypeDetector detector;

    @BeforeEach
    void setUp() {
        detector = new QueryTypeDetector();
    }

    // ================== HTTP_URI 测试 ==================

    @Nested
    @DisplayName("HTTP_URI 检测")
    class HttpUriTests {

        @Test
        @DisplayName("POST /api/user/login 应识别为 HTTP_URI")
        void testPostUri() {
            assertEquals(QueryType.HTTP_URI, detector.detect("POST /api/user/login"));
        }

        @Test
        @DisplayName("GET /orders/{id} 应识别为 HTTP_URI")
        void testGetUri() {
            assertEquals(QueryType.HTTP_URI, detector.detect("GET /orders/{id}"));
        }

        @Test
        @DisplayName("/api/user 应识别为 HTTP_URI")
        void testPathOnlyUri() {
            assertEquals(QueryType.HTTP_URI, detector.detect("/api/user"));
        }

        @Test
        @DisplayName("PUT /api/user/{id} 应识别为 HTTP_URI")
        void testPutUri() {
            assertEquals(QueryType.HTTP_URI, detector.detect("PUT /api/user/{id}"));
        }

        @Test
        @DisplayName("DELETE /api/user/{id} 应识别为 HTTP_URI")
        void testDeleteUri() {
            assertEquals(QueryType.HTTP_URI, detector.detect("DELETE /api/user/{id}"));
        }

        @Test
        @DisplayName("PATCH /api/user/{id} 应识别为 HTTP_URI")
        void testPatchUri() {
            assertEquals(QueryType.HTTP_URI, detector.detect("PATCH /api/user/{id}"));
        }
    }

    // ================== SQL_SNIPPET 测试 ==================

    @Nested
    @DisplayName("SQL_SNIPPET 检测")
    class SqlSnippetTests {

        @Test
        @DisplayName("SELECT * FROM user 应识别为 SQL_SNIPPET")
        void testSelectSql() {
            assertEquals(QueryType.SQL_SNIPPET, detector.detect("SELECT * FROM user"));
        }

        @Test
        @DisplayName("INSERT INTO order 应识别为 SQL_SNIPPET")
        void testInsertSql() {
            assertEquals(QueryType.SQL_SNIPPET, detector.detect("INSERT INTO order"));
        }

        @Test
        @DisplayName("UPDATE user SET name=? 应识别为 SQL_SNIPPET")
        void testUpdateSql() {
            assertEquals(QueryType.SQL_SNIPPET, detector.detect("UPDATE user SET name=?"));
        }

        @Test
        @DisplayName("DELETE FROM user WHERE id=? 应识别为 SQL_SNIPPET")
        void testDeleteSql() {
            assertEquals(QueryType.SQL_SNIPPET, detector.detect("DELETE FROM user WHERE id=?"));
        }

        @Test
        @DisplayName("WITH cte AS (...) 应识别为 SQL_SNIPPET")
        void testWithSql() {
            assertEquals(QueryType.SQL_SNIPPET, detector.detect("WITH cte AS (SELECT 1) SELECT * FROM cte"));
        }
    }

    // ================== FULL_QUALIFIED_NAME 测试 ==================

    @Nested
    @DisplayName("FULL_QUALIFIED_NAME 检测")
    class FullQualifiedNameTests {

        @Test
        @DisplayName("com.example.mapper.UserMapper.selectById 应识别为 FULL_QUALIFIED_NAME")
        void testFullQualifiedName() {
            assertEquals(QueryType.FULL_QUALIFIED_NAME, detector.detect("com.example.mapper.UserMapper.selectById"));
        }

        @Test
        @DisplayName("com.huawei.hisi.service.UserService.createUser 应识别为 FULL_QUALIFIED_NAME")
        void testLongFullQualifiedName() {
            assertEquals(QueryType.FULL_QUALIFIED_NAME, detector.detect("com.huawei.hisi.service.UserService.createUser"));
        }
    }

    // ================== CLASS_NAME 测试 ==================

    @Nested
    @DisplayName("CLASS_NAME 检测")
    class ClassNameTests {

        @Test
        @DisplayName("UserService 应识别为 CLASS_NAME")
        void testUserService() {
            assertEquals(QueryType.CLASS_NAME, detector.detect("UserService"));
        }

        @Test
        @DisplayName("OrderController 应识别为 CLASS_NAME")
        void testOrderController() {
            assertEquals(QueryType.CLASS_NAME, detector.detect("OrderController"));
        }

        @Test
        @DisplayName("UserMapper 应识别为 CLASS_NAME")
        void testUserMapper() {
            assertEquals(QueryType.CLASS_NAME, detector.detect("UserMapper"));
        }
    }

    // ================== ANNOTATION 测试 ==================

    @Nested
    @DisplayName("ANNOTATION 检测")
    class AnnotationTests {

        @Test
        @DisplayName("@Transactional 应识别为 ANNOTATION")
        void testTransactional() {
            assertEquals(QueryType.ANNOTATION, detector.detect("@Transactional"));
        }

        @Test
        @DisplayName("@Async 应识别为 ANNOTATION")
        void testAsync() {
            assertEquals(QueryType.ANNOTATION, detector.detect("@Async"));
        }

        @Test
        @DisplayName("@Override 应识别为 ANNOTATION")
        void testOverride() {
            assertEquals(QueryType.ANNOTATION, detector.detect("@Override"));
        }

        @Test
        @DisplayName("@RequestMapping(value=\"/api\") 应识别为 ANNOTATION")
        void testAnnotationWithParams() {
            assertEquals(QueryType.ANNOTATION, detector.detect("@RequestMapping(value=\"/api\")"));
        }

        // 注意：@org.springframework.stereotype.Service 格式的全限定注解
        // 当前不被支持，需要增强 ANNOTATION_PATTERN 正则以支持包名前缀
    }

    // ================== EXCEPTION_TYPE 测试 ==================

    @Nested
    @DisplayName("EXCEPTION_TYPE 检测")
    class ExceptionTypeTests {

        @Test
        @DisplayName("BusinessException 应识别为 EXCEPTION_TYPE")
        void testBusinessException() {
            assertEquals(QueryType.EXCEPTION_TYPE, detector.detect("BusinessException"));
        }

        @Test
        @DisplayName("NullPointerException 应识别为 EXCEPTION_TYPE")
        void testNullPointerException() {
            assertEquals(QueryType.EXCEPTION_TYPE, detector.detect("NullPointerException"));
        }

        @Test
        @DisplayName("IllegalArgumentException 应识别为 EXCEPTION_TYPE")
        void testIllegalArgumentException() {
            assertEquals(QueryType.EXCEPTION_TYPE, detector.detect("IllegalArgumentException"));
        }

        @Test
        @DisplayName("OutOfMemoryError 应识别为 EXCEPTION_TYPE")
        void testOutOfMemoryError() {
            assertEquals(QueryType.EXCEPTION_TYPE, detector.detect("OutOfMemoryError"));
        }
    }

    // ================== CODE_SNIPPET 测试 ==================

    @Nested
    @DisplayName("CODE_SNIPPET 检测")
    class CodeSnippetTests {

        @Test
        @DisplayName("return userMapper.selectById(id) 应识别为 CODE_SNIPPET")
        void testReturnStatement() {
            assertEquals(QueryType.CODE_SNIPPET, detector.detect("return userMapper.selectById(id)"));
        }

        @Test
        @DisplayName("if (user != null) { 应识别为 CODE_SNIPPET")
        void testIfStatement() {
            assertEquals(QueryType.CODE_SNIPPET, detector.detect("if (user != null) {"));
        }

        @Test
        @DisplayName("for (int i = 0; i < list.size(); i++) 应识别为 CODE_SNIPPET")
        void testForLoop() {
            assertEquals(QueryType.CODE_SNIPPET, detector.detect("for (int i = 0; i < list.size(); i++)"));
        }

        @Test
        @DisplayName("String name = user.getName(); 应识别为 CODE_SNIPPET")
        void testAssignment() {
            assertEquals(QueryType.CODE_SNIPPET, detector.detect("String name = user.getName();"));
        }

        // 注意：lambda 表达式需要足够的代码特征才能被识别为 CODE_SNIPPET
        // 当前实现中 "list.stream().filter(x -> x > 0)" 的分数可能不足以超过阈值
    }

    // ================== METHOD_NAME 测试 ==================

    @Nested
    @DisplayName("METHOD_NAME 检测")
    class MethodNameTests {

        @Test
        @DisplayName("selectById 应识别为 METHOD_NAME")
        void testSelectById() {
            assertEquals(QueryType.METHOD_NAME, detector.detect("selectById"));
        }

        @Test
        @DisplayName("getUserOrder 应识别为 METHOD_NAME")
        void testGetUserOrder() {
            assertEquals(QueryType.METHOD_NAME, detector.detect("getUserOrder"));
        }

        @Test
        @DisplayName("createUser 应识别为 METHOD_NAME")
        void testCreateUser() {
            assertEquals(QueryType.METHOD_NAME, detector.detect("createUser"));
        }

        @Test
        @DisplayName("UserService.createUser 应识别为 METHOD_NAME (ClassName.methodName)")
        void testClassDotMethod() {
            assertEquals(QueryType.METHOD_NAME, detector.detect("UserService.createUser"));
        }

        @Test
        @DisplayName("findById 应识别为 METHOD_NAME")
        void testFindById() {
            assertEquals(QueryType.METHOD_NAME, detector.detect("findById"));
        }

        @Test
        @DisplayName("saveUser 应识别为 METHOD_NAME")
        void testSaveUser() {
            assertEquals(QueryType.METHOD_NAME, detector.detect("saveUser"));
        }

        @Test
        @DisplayName("queryList 应识别为 METHOD_NAME")
        void testQueryList() {
            assertEquals(QueryType.METHOD_NAME, detector.detect("queryList"));
        }

        @Test
        @DisplayName("countByCondition 应识别为 METHOD_NAME")
        void testCountByCondition() {
            assertEquals(QueryType.METHOD_NAME, detector.detect("countByCondition"));
        }

        @Test
        @DisplayName("existsById 应识别为 METHOD_NAME")
        void testExistsById() {
            assertEquals(QueryType.METHOD_NAME, detector.detect("existsById"));
        }
    }

    // ================== NATURAL_LANGUAGE 测试 ==================

    @Nested
    @DisplayName("NATURAL_LANGUAGE 检测")
    class NaturalLanguageTests {

        @Test
        @DisplayName("查询用户订单信息的方法 应识别为 NATURAL_LANGUAGE")
        void testChineseQuery() {
            assertEquals(QueryType.NATURAL_LANGUAGE, detector.detect("查询用户订单信息的方法"));
        }

        @Test
        @DisplayName("如何创建用户 应识别为 NATURAL_LANGUAGE")
        void testChineseHowTo() {
            assertEquals(QueryType.NATURAL_LANGUAGE, detector.detect("如何创建用户"));
        }

        @Test
        @DisplayName("how to create user 应识别为 NATURAL_LANGUAGE")
        void testEnglishNaturalLanguage() {
            assertEquals(QueryType.NATURAL_LANGUAGE, detector.detect("how to create user"));
        }
    }

    // ================== 边界情况测试 ==================

    @Nested
    @DisplayName("边界情况检测")
    class EdgeCaseTests {

        @Test
        @DisplayName("select 应识别为 METHOD_NAME 而不是 SQL_SNIPPET")
        void testSelectAlone() {
            assertEquals(QueryType.METHOD_NAME, detector.detect("select"));
        }

        @Test
        @DisplayName("get 应识别为 METHOD_NAME 而不是 HTTP_URI")
        void testGetAlone() {
            assertEquals(QueryType.METHOD_NAME, detector.detect("get"));
        }

        @Test
        @DisplayName("insert 应识别为 METHOD_NAME")
        void testInsertAlone() {
            assertEquals(QueryType.METHOD_NAME, detector.detect("insert"));
        }

        @Test
        @DisplayName("update 应识别为 METHOD_NAME")
        void testUpdateAlone() {
            assertEquals(QueryType.METHOD_NAME, detector.detect("update"));
        }

        @Test
        @DisplayName("delete 应识别为 METHOD_NAME")
        void testDeleteAlone() {
            assertEquals(QueryType.METHOD_NAME, detector.detect("delete"));
        }

        @Test
        @DisplayName("空字符串应返回 NATURAL_LANGUAGE")
        void testEmptyQuery() {
            assertEquals(QueryType.NATURAL_LANGUAGE, detector.detect(""));
        }

        @Test
        @DisplayName("null 应返回 NATURAL_LANGUAGE")
        void testNullQuery() {
            assertEquals(QueryType.NATURAL_LANGUAGE, detector.detect(null));
        }

        @Test
        @DisplayName("纯空格应返回 NATURAL_LANGUAGE")
        void testWhitespaceQuery() {
            assertEquals(QueryType.NATURAL_LANGUAGE, detector.detect("   "));
        }
    }

    // ================== 参数化测试 ==================

    @ParameterizedTest(name = "\"{0}\" 应识别为 {1}")
    @DisplayName("参数化查询类型检测")
    @CsvSource({
            "POST /api/user/login, HTTP_URI",
            "GET /orders/{id}, HTTP_URI",
            "/api/user, HTTP_URI",
            "SELECT * FROM user, SQL_SNIPPET",
            "INSERT INTO order, SQL_SNIPPET",
            "com.example.mapper.UserMapper.selectById, FULL_QUALIFIED_NAME",
            "UserService, CLASS_NAME",
            "OrderController, CLASS_NAME",
            "@Transactional, ANNOTATION",
            "@Async, ANNOTATION",
            "BusinessException, EXCEPTION_TYPE",
            "NullPointerException, EXCEPTION_TYPE",
            "selectById, METHOD_NAME",
            "getUserOrder, METHOD_NAME",
            "select, METHOD_NAME",
            "get, METHOD_NAME",
            "insert, METHOD_NAME",
            "update, METHOD_NAME",
            "delete, METHOD_NAME"
    })
    void testParameterizedDetection(String query, QueryType expected) {
        assertEquals(expected, detector.detect(query));
    }

    // ================== Python 多语言支持测试 ==================

    @Nested
    @DisplayName("Python 多语言支持")
    class PythonMultiLanguageTests {

        @Test
        @DisplayName("Python FQN: app.api.users.get_user 应识别为 FULL_QUALIFIED_NAME")
        void testPythonFqn() {
            assertEquals(QueryType.FULL_QUALIFIED_NAME, detector.detect("app.api.users.get_user"));
        }

        @Test
        @DisplayName("Python FQN: services.auth.login 应识别为 FULL_QUALIFIED_NAME")
        void testPythonFqnThreeSegments() {
            assertEquals(QueryType.FULL_QUALIFIED_NAME, detector.detect("services.auth.login"));
        }

        @Test
        @DisplayName("@app.get 应识别为 ANNOTATION（Python 装饰器）")
        void testFlaskDecorator() {
            assertEquals(QueryType.ANNOTATION, detector.detect("@app.get"));
        }

        @Test
        @DisplayName("@router.post(\"/users\") 应识别为 ANNOTATION（FastAPI 装饰器带路径）")
        void testFastApiDecoratorWithPath() {
            assertEquals(QueryType.ANNOTATION, detector.detect("@router.post(\"/users\")"));
        }

        @Test
        @DisplayName("@shared_task 应识别为 ANNOTATION（Celery 装饰器）")
        void testCeleryDecorator() {
            assertEquals(QueryType.ANNOTATION, detector.detect("@shared_task"));
        }

        @Test
        @DisplayName("@login_required 应识别为 ANNOTATION（Django 装饰器）")
        void testDjangoDecorator() {
            assertEquals(QueryType.ANNOTATION, detector.detect("@login_required"));
        }

        @Test
        @DisplayName("@Transactional 仍应识别为 ANNOTATION（Java 注解兼容）")
        void testJavaAnnotationStillWorks() {
            assertEquals(QueryType.ANNOTATION, detector.detect("@Transactional"));
        }
    }
}
