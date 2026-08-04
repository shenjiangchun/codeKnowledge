package com.huawei.hisi.knowledgegraph.model;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

/**
 * 单元测试生成请求 DTO
 * 接收方法信息用于生成 JUnit5 + Mockito 测试代码
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UnitTestRequest {

    /**
     * 方法节点 ID（用于从数据库获取方法详细信息）
     */
    @NotBlank(message = "方法节点 ID 不能为空")
    private String methodNodeId;

    /**
     * 项目路径（可选，用于上下文）
     */
    private String projectPath;

    /**
     * 是否生成 Mock 依赖
     */
    @Builder.Default
    private Boolean generateMocks = true;

    /**
     * 是否包含异常测试
     */
    @Builder.Default
    private Boolean includeExceptionTests = true;

    /**
     * 额外的依赖类名列表（可选，用于指定需要 Mock 的类）
     */
    private List<String> additionalMockDependencies;

    /**
     * 测试类名后缀（默认为 "Test"）
     */
    @Builder.Default
    private String testClassSuffix = "Test";
}
