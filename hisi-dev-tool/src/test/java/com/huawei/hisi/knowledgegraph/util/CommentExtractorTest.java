package com.huawei.hisi.knowledgegraph.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 注释提取工具测试
 * TDD: 测试先行
 */
class CommentExtractorTest {

    @TempDir
    Path tempDir;

    // ================== Javadoc 注释提取测试 ==================

    @Test
    void testExtractJavadocComment() throws IOException {
        // 准备测试文件
        String javaCode = """
            package com.example;

            /**
             * 用户服务接口
             * 提供用户相关操作
             */
            public class UserService {

                /**
                 * 根据ID获取用户信息
                 * @param userId 用户ID
                 * @return 用户对象
                 */
                public User getUserById(String userId) {
                    return null;
                }

                /**
                 * 创建新用户
                 * @param user 用户对象
                 * @return 是否成功
                 */
                public boolean createUser(User user) {
                    return true;
                }
            }
            """;

        Path javaFile = tempDir.resolve("UserService.java");
        Files.writeString(javaFile, javaCode);

        // 执行测试
        Map<String, String> comments = CommentExtractor.extractMethodComments(javaFile);

        // 验证
        assertNotNull(comments);
        assertEquals("根据ID获取用户信息", comments.get("getUserById"));
        assertEquals("创建新用户", comments.get("createUser"));
    }

    @Test
    void testExtractJavadocComment_withMultipleTags() throws IOException {
        // 测试包含多个标签的Javadoc
        String javaCode = """
            package com.example;

            public class OrderService {

                /**
                 * 查询订单列表
                 * 支持分页和排序
                 * @param page 页码
                 * @param size 每页数量
                 * @param sort 排序字段
                 * @return 订单列表
                 * @throws IllegalArgumentException 参数非法
                 */
                public List<Order> findOrders(int page, int size, String sort) {
                    return null;
                }
            }
            """;

        Path javaFile = tempDir.resolve("OrderService.java");
        Files.writeString(javaFile, javaCode);

        Map<String, String> comments = CommentExtractor.extractMethodComments(javaFile);

        assertNotNull(comments);
        // 应提取描述部分（第一个句子）
        assertTrue(comments.get("findOrders").contains("查询订单列表"));
    }

    @Test
    void testExtractJavadocComment_emptyComment() throws IOException {
        // 测试空Javadoc
        String javaCode = """
            package com.example;

            public class DataService {

                /**
                 *
                 */
                public void doSomething() {
                }
            }
            """;

        Path javaFile = tempDir.resolve("DataService.java");
        Files.writeString(javaFile, javaCode);

        Map<String, String> comments = CommentExtractor.extractMethodComments(javaFile);

        assertNotNull(comments);
        // 空注释应该通过方法名推断，doSomething没有匹配前缀，返回原方法名
        assertEquals("doSomething", comments.get("doSomething"));
    }

    // ================== 行注释提取测试 ==================

    @Test
    void testExtractLineComment() throws IOException {
        // 测试行注释提取
        String javaCode = """
            package com.example;

            public class Calculator {

                // 加法运算
                public int add(int a, int b) {
                    return a + b;
                }

                // 减法运算
                public int subtract(int a, int b) {
                    return a - b;
                }
            }
            """;

        Path javaFile = tempDir.resolve("Calculator.java");
        Files.writeString(javaFile, javaCode);

        Map<String, String> comments = CommentExtractor.extractMethodComments(javaFile);

        assertNotNull(comments);
        assertEquals("加法运算", comments.get("add"));
        assertEquals("减法运算", comments.get("subtract"));
    }

    @Test
    void testExtractLineComment_inlineComment() throws IOException {
        // 测试方法内行注释（不应被提取）
        String javaCode = """
            package com.example;

            public class Processor {

                // 处理数据
                public void process(String data) {
                    // 这是方法内注释，不应被提取
                    System.out.println(data);
                }
            }
            """;

        Path javaFile = tempDir.resolve("Processor.java");
        Files.writeString(javaFile, javaCode);

        Map<String, String> comments = CommentExtractor.extractMethodComments(javaFile);

        assertNotNull(comments);
        assertEquals("处理数据", comments.get("process"));
    }

    // ================== 方法名推断测试 ==================

    @Test
    void testInferFromMethodName_getter() {
        // 测试getter方法推断
        assertEquals("获取Username", CommentExtractor.inferFromMethodName("getUsername"));
        assertEquals("获取Id", CommentExtractor.inferFromMethodName("getId"));
        assertEquals("获取Name", CommentExtractor.inferFromMethodName("getName"));
    }

    @Test
    void testInferFromMethodName_setter() {
        // 测试setter方法推断
        assertEquals("设置Username", CommentExtractor.inferFromMethodName("setUsername"));
        assertEquals("设置Id", CommentExtractor.inferFromMethodName("setId"));
    }

    @Test
    void testInferFromMethodName_find() {
        // 测试find方法推断
        assertEquals("查询User", CommentExtractor.inferFromMethodName("findUser"));
        assertEquals("查询All", CommentExtractor.inferFromMethodName("findAll"));
        assertEquals("查询OrderById", CommentExtractor.inferFromMethodName("findOrderById"));
    }

    @Test
    void testInferFromMethodName_create() {
        // 测试create方法推断
        assertEquals("创建User", CommentExtractor.inferFromMethodName("createUser"));
        assertEquals("创建Order", CommentExtractor.inferFromMethodName("createOrder"));
    }

    @Test
    void testInferFromMethodName_add() {
        // 测试add方法推断
        assertEquals("添加User", CommentExtractor.inferFromMethodName("addUser"));
        assertEquals("添加Item", CommentExtractor.inferFromMethodName("addItem"));
    }

    @Test
    void testInferFromMethodName_delete() {
        // 测试delete方法推断
        assertEquals("删除User", CommentExtractor.inferFromMethodName("deleteUser"));
        assertEquals("删除All", CommentExtractor.inferFromMethodName("deleteAll"));
    }

    @Test
    void testInferFromMethodName_update() {
        // 测试update方法推断
        assertEquals("更新User", CommentExtractor.inferFromMethodName("updateUser"));
        assertEquals("更新Status", CommentExtractor.inferFromMethodName("updateStatus"));
    }

    @Test
    void testInferFromMethodName_validate() {
        // 测试validate方法推断
        assertEquals("校验User", CommentExtractor.inferFromMethodName("validateUser"));
        assertEquals("校验Params", CommentExtractor.inferFromMethodName("validateParams"));
    }

    @Test
    void testInferFromMethodName_process() {
        // 测试process方法推断
        assertEquals("处理Data", CommentExtractor.inferFromMethodName("processData"));
        assertEquals("处理Request", CommentExtractor.inferFromMethodName("processRequest"));
    }

    @Test
    void testInferFromMethodName_send() {
        // 测试send方法推断
        assertEquals("发送Message", CommentExtractor.inferFromMethodName("sendMessage"));
        assertEquals("发送Email", CommentExtractor.inferFromMethodName("sendEmail"));
    }

    @Test
    void testInferFromMethodName_save() {
        // 测试save方法推断
        assertEquals("保存User", CommentExtractor.inferFromMethodName("saveUser"));
    }

    @Test
    void testInferFromMethodName_load() {
        // 测试load方法推断
        assertEquals("加载Data", CommentExtractor.inferFromMethodName("loadData"));
    }

    @Test
    void testInferFromMethodName_is() {
        // 测试is方法推断
        assertEquals("是否Valid", CommentExtractor.inferFromMethodName("isValid"));
        assertEquals("是否Enabled", CommentExtractor.inferFromMethodName("isEnabled"));
    }

    @Test
    void testInferFromMethodName_has() {
        // 测试has方法推断
        assertEquals("是否包含Children", CommentExtractor.inferFromMethodName("hasChildren"));
        assertEquals("是否包含Permission", CommentExtractor.inferFromMethodName("hasPermission"));
    }

    @Test
    void testInferFromMethodName_unknown() {
        // 测试未知前缀的方法名 - 返回原方法名
        // 注意: execute 是已知前缀，会返回 "执行"
        assertEquals("执行", CommentExtractor.inferFromMethodName("execute"));
        assertEquals("run", CommentExtractor.inferFromMethodName("run"));
    }

    @Test
    void testInferFromMethodName_null() {
        // 测试null输入
        assertEquals("", CommentExtractor.inferFromMethodName(null));
    }

    @Test
    void testInferFromMethodName_empty() {
        // 测试空字符串输入
        assertEquals("", CommentExtractor.inferFromMethodName(""));
    }

    // ================== 综合测试 ==================

    @Test
    void testExtractMethodComments_noComments() throws IOException {
        // 测试无注释的情况，应使用方法名推断
        String javaCode = """
            package com.example;

            public class Service {

                public User getUserById(String id) {
                    return null;
                }

                public void createUser(User user) {
                }

                public void deleteUser(String id) {
                }
            }
            """;

        Path javaFile = tempDir.resolve("Service.java");
        Files.writeString(javaFile, javaCode);

        Map<String, String> comments = CommentExtractor.extractMethodComments(javaFile);

        assertNotNull(comments);
        assertEquals("获取UserById", comments.get("getUserById"));
        assertEquals("创建User", comments.get("createUser"));
        assertEquals("删除User", comments.get("deleteUser"));
    }

    @Test
    void testExtractMethodComments_mixedComments() throws IOException {
        // 测试混合注释类型
        String javaCode = """
            package com.example;

            public class MixedService {

                /**
                 * 查询用户
                 */
                public User findUser(String id) {
                    return null;
                }

                // 添加用户
                public void addUser(User user) {
                }

                public void removeUser(String id) {
                }
            }
            """;

        Path javaFile = tempDir.resolve("MixedService.java");
        Files.writeString(javaFile, javaCode);

        Map<String, String> comments = CommentExtractor.extractMethodComments(javaFile);

        assertNotNull(comments);
        assertEquals("查询用户", comments.get("findUser"));
        assertEquals("添加用户", comments.get("addUser"));
        // remove应通过方法名推断
        assertEquals("移除User", comments.get("removeUser"));
    }

    @Test
    void testExtractMethodComments_fileNotExists() {
        // 测试文件不存在的情况
        Path nonExistentFile = tempDir.resolve("NonExistent.java");

        Map<String, String> comments = CommentExtractor.extractMethodComments(nonExistentFile);

        assertNotNull(comments);
        assertTrue(comments.isEmpty());
    }
}
