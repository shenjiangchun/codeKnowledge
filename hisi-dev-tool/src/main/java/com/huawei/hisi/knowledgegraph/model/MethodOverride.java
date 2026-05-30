package com.huawei.hisi.knowledgegraph.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 方法重写关系模型
 * 记录子类方法重写父类方法的关系
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MethodOverride {
    /**
     * 子类全限定名
     */
    private String subclass;

    /**
     * 父类全限定名
     */
    private String superclass;

    /**
     * 方法名
     */
    private String methodName;

    /**
     * 所属项目路径
     */
    private String projectPath;
}
