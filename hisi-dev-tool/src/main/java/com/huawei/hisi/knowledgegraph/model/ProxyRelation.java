package com.huawei.hisi.knowledgegraph.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 代理类关系模型
 * 记录代理类与被代理类的关系
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProxyRelation {
    /**
     * 代理类全限定名
     */
    private String proxyClass;

    /**
     * 被代理类全限定名
     */
    private String targetClass;

    /**
     * 代理类型 (MYBATIS/JPA/AOP/FEIGN_PROXY等)
     */
    private String proxyType;

    /**
     * 所属项目路径
     */
    private String projectPath;
}
