package com.huawei.hisi.knowledgegraph.model;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 单元测试生成响应 DTO
 * 包含生成的 JUnit5 + Mockito 测试代码和相关信息
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UnitTestResponse {

    /**
     * 请求 ID
     */
    private String requestId;

    /**
     * 测试类名
     */
    private String testClassName;

    /**
     * 生成的测试代码
     */
    private String testCode;

    /**
     * 需要导入的依赖列表
     */
    private List<String> imports;

    /**
     * 需要 Mock 的依赖列表
     */
    private List<MockDependency> mockDependencies;

    /**
     * 生成的测试方法列表
     */
    private List<TestMethod> testMethods;

    /**
     * 生成时间
     */
    private LocalDateTime generatedAt;

    /**
     * 生成是否成功
     */
    @Builder.Default
    private Boolean success = true;

    /**
     * 错误信息（如果失败）
     */
    private String errorMessage;

    /**
     * 原始方法信息
     */
    private MethodInfo methodInfo;

    /**
     * Mock 依赖信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MockDependency {
        /**
         * 依赖类型（字段类型全限定名）
         */
        private String type;

        /**
         * 字段名
         */
        private String fieldName;

        /**
         * 是否需要 @Mock 注解
         */
        @Builder.Default
        private Boolean needsMock = true;

        /**
         * 是否需要 @InjectMocks 注解
         */
        @Builder.Default
        private Boolean needsInjectMocks = false;
    }

    /**
     * 测试方法信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TestMethod {
        /**
         * 测试方法名
         */
        private String methodName;

        /**
         * 测试描述
         */
        private String description;

        /**
         * 测试类型（NORMAL, EXCEPTION, BOUNDARY）
         */
        private String testType;

        /**
         * 测试代码
         */
        private String code;
    }

    /**
     * 原始方法信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MethodInfo {
        /**
         * 类名
         */
        private String className;

        /**
         * 方法名
         */
        private String methodName;

        /**
         * 方法签名
         */
        private String signature;

        /**
         * 返回类型
         */
        private String returnType;

        /**
         * 参数列表
         */
        private List<Parameter> parameters;

        /**
         * 抛出的异常列表
         */
        private List<String> thrownExceptions;
    }

    /**
     * 方法参数
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Parameter {
        /**
         * 参数类型
         */
        private String type;

        /**
         * 参数名
         */
        private String name;
    }
}
