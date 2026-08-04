package com.huawei.hisi.model;

/**
 * 代码关系类型枚举
 */
public enum RelationType {
    /** 方法调用关系 */
    CALLS,
    /** 接口实现关系 */
    IMPLEMENTS,
    /** 类继承关系 */
    EXTENDS,
    /** 异常抛出关系 */
    THROWS,
    /** 字段/变量使用关系 */
    USES,
    /** 依赖关系 */
    DEPENDS_ON,
    /** 方法重写关系 */
    OVERRIDES
}