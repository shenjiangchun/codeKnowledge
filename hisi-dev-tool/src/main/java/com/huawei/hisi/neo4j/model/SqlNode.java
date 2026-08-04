package com.huawei.hisi.neo4j.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Property;

/**
 * SQL 节点实体 (Neo4j)
 * 存储从 MyBatis Mapper XML 解析的 SQL 语句信息
 * 替代 PostgreSQL 中的 code_mybatis_sql 表
 *
 * 主键设计：nodeId = projectPath + ":" + sqlId，确保全局唯一
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Node("Sql")
public class SqlNode {

    /**
     * 唯一节点标识符
     * 格式：projectPath + ":" + sqlId
     * 确保不同项目的同名 Mapper SQL 不会冲突
     */
    @Id
    @Property("nodeId")
    private String nodeId;

    /**
     * SQL ID：namespace.statementId
     * 例如：com.example.mapper.UserMapper.selectById
     */
    @Property("sqlId")
    private String sqlId;

    /**
     * 语句类型：SELECT/INSERT/UPDATE/DELETE
     */
    @Property("statementType")
    private String statementType;

    /**
     * SQL 语句内容
     */
    @Property("sqlStatement")
    private String sqlStatement;

    /**
     * 参数类型
     */
    @Property("parameterType")
    private String parameterType;

    /**
     * 返回类型
     */
    @Property("resultType")
    private String resultType;

    /**
     * ResultMap ID
     */
    @Property("resultMap")
    private String resultMap;

    /**
     * 关联的 Mapper 接口全限定名
     */
    @Property("mapperInterface")
    private String mapperInterface;

    /**
     * 方法名
     */
    @Property("methodName")
    private String methodName;

    /**
     * 来源 XML 文件路径
     */
    @Property("xmlFilePath")
    private String xmlFilePath;

    /**
     * 项目路径（数据隔离）
     */
    @Property("projectPath")
    private String projectPath;

    /**
     * 编程语言: java/python/...
     */
    @Property("language")
    private String language;

    /**
     * 框架: spring/fastapi/django/...
     */
    @Property("framework")
    private String framework;

    /**
     * SQL 嵌入向量 (基于 SQL 语句的向量)
     * 用于 SQL 语义搜索
     * 维度: 2048 (ZhipuAI embedding-3)
     */
    @Property("sqlEmbedding")
    private float[] sqlEmbedding;

    /**
     * 生成唯一的节点 ID
     * @param projectPath 项目路径
     * @param sqlId SQL ID
     * @return 唯一节点 ID
     */
    public static String generateNodeId(String projectPath, String sqlId) {
        return projectPath + ":" + sqlId;
    }
}
