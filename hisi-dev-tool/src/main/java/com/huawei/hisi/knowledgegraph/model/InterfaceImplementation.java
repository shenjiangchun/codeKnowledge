package com.huawei.hisi.knowledgegraph.model;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * 接口-实现关系模型
 * 记录接口与其实现类之间的映射关系
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InterfaceImplementation {
    /**
     * 接口全限定名
     */
    private String interfaceName;

    /**
     * 实现类全限定名
     */
    private String implementationName;

    /**
     * 所属项目路径
     */
    private String projectPath;

    /**
     * 实现类型: LOCAL（本地实现）或 FEIGN_PROXY（Feign 代理）
     */
    @lombok.Builder.Default
    private String implType = "LOCAL";
}
