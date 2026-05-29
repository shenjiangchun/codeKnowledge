package com.huawei.hisi.neo4j.model;

/**
 * 查询类型枚举
 * 用于多规则评分识别查询类型，支持多种搜索策略路由
 */
public enum QueryType {
    /**
     * 自然语言查询
     * 例如："查询用户订单信息的方法"
     * 搜索策略: descriptionEmbedding 向量检索 + 图扩展
     */
    NATURAL_LANGUAGE,

    /**
     * 方法名查询
     * 例如："selectById"
     * 搜索策略: methodName 模糊匹配 + 向量补充 + 图扩展
     */
    METHOD_NAME,

    /**
     * 全限定名查询（含方法）
     * 例如："com.example.mapper.UserMapper.selectById"
     * 搜索策略: className + methodName 精确匹配 + 图扩展
     */
    FULL_QUALIFIED_NAME,

    /**
     * 全限定类名查询（只有类，没方法）
     * 例如："com.huawei.hisi.agent.controller.DiagnosisController"
     * 搜索策略: className 精确匹配所有方法，不做图扩展也不做跨目录模糊
     */
    FULL_QUALIFIED_CLASS_NAME,

    /**
     * 类名查询
     * 例如："UserService"、"OrderController"
     * 搜索策略: className 精确/模糊匹配 + 图扩展
     */
    CLASS_NAME,

    /**
     * SQL片段查询
     * 例如："SELECT * FROM user"
     * 搜索策略: sqlEmbedding 向量检索 -> EXECUTES_SQL 反查
     */
    SQL_SNIPPET,

    /**
     * HTTP URI 查询
     * 例如："POST /api/user/login"
     * 搜索策略: entryKey 模糊匹配 -> methodNodeId 关联
     */
    HTTP_URI,

    /**
     * 代码片段查询
     * 例如："return userMapper.selectById(id)"
     * 搜索策略: codeEmbedding 向量检索 + 图扩展
     */
    CODE_SNIPPET,

    /**
     * 注解查询
     * 例如："@Transactional"
     * 搜索策略: methodBody/comment CONTAINS 匹配
     */
    ANNOTATION,

    /**
     * 异常类型查询
     * 例如："BusinessException"
     * 搜索策略: thrownExceptions/caughtExceptions CONTAINS 匹配
     */
    EXCEPTION_TYPE
}