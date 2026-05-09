
package com.huawei.hisi.knowledgegraph.model;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * 类继承关系模型
 * 记录子类与父类之间的继承关系
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClassExtends {
    /**
     * 子类全限定名
     */
    private String subclass;

    /**
     * 父类全限定名
     */
    private String superclass;

    /**
     * 所属项目路径
     */
    private String projectPath;
}

