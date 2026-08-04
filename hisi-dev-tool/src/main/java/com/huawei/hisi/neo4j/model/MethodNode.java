package com.huawei.hisi.neo4j.model;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Property;

import java.util.List;

/**
 * 方法节点实体 (Neo4j)
 * 表示代码中的一个方法，用于图数据库存储
 *
 * 注意：不在此实体中定义 CALLS 关系，避免 Spring Data Neo4j 自动加载关系导致 N+1 查询。
 * 调用关系通过 Repository 中的自定义查询获取。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Node("Method")
public class MethodNode {

    /**
     * 节点唯一标识 (类名.方法名.签名hash)
     */
    @Id
    @Property("nodeId")
    private String nodeId;

    /**
     * 全限定类名
     */
    @Property("className")
    private String className;

    /**
     * 方法名
     */
    @Property("methodName")
    private String methodName;

    /**
     * 方法签名
     */
    @Property("signature")
    private String signature;

    /**
     * 源文件路径
     */
    @Property("filePath")
    private String filePath;

    /**
     * 起始行号
     */
    @Property("startLine")
    private Integer startLine;

    /**
     * 结束行号
     */
    @Property("endLine")
    private Integer endLine;

    /**
     * 圈复杂度
     */
    @Property("complexity")
    private Integer complexity;

    /**
     * 抛出的异常列表
     */
    @Property("thrownExceptions")
    private List<String> thrownExceptions;

    /**
     * 捕获的异常列表
     */
    @Property("caughtExceptions")
    private List<String> caughtExceptions;

    /**
     * 方法体内容
     */
    @Property("methodBody")
    private String methodBody;

    /**
     * 所属项目路径
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
     * 所属服务名
     */
    @Property("serviceName")
    private String serviceName;

    /**
     * 方法注释
     */
    @Property("comment")
    private String comment;

    /**
     * 方法描述 (LLM 生成的自然语言描述)
     */
    @Property("description")
    private String description;

    /**
     * 描述嵌入向量 (基于方法体生成的 LLM 描述向量)
     * 用于自然语言搜索
     * 维度: 2048 (ZhipuAI embedding-3)
     */
    @Property("descriptionEmbedding")
    private float[] descriptionEmbedding;

    /**
     * 代码嵌入向量 (基于方法签名+方法体原文的向量)
     * 用于代码片段搜索
     * 维度: 2048 (ZhipuAI embedding-3)
     */
    @Property("codeEmbedding")
    private float[] codeEmbedding;

    /**
     * 代码指纹 (SHA-256，用于二期增量跳过逻辑)
     * 计算方式: SHA-256({@code className.methodName(signature)\nmethodBody})
     * 一期仅创建字段和索引，不实现跳过逻辑；二期启用
     */
    @Property("codeHash")
    private String codeHash;
}
