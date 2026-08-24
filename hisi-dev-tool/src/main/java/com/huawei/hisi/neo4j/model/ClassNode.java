package com.huawei.hisi.neo4j.model;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Property;

/**
 * 类节点实体 (Neo4j)
 * 表示代码中的一个类，是比方法更宏观的理解单元。
 *
 * <p>classId = projectPath + ":" + className，与领域下钻虚拟类节点的稳定标识一致。
 * 领域归属走三层结构：{@code Domain -[:BELONGS_TO]-> ClassNode -[:HAS_METHOD]-> Method}。
 *
 * <p>注意：不在此实体中定义 BELONGS_TO / HAS_METHOD 关系，避免 Spring Data Neo4j
 * 自动加载关系导致 N+1 查询。关系通过 Repository 自定义查询维护。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Node("Class")
public class ClassNode {

    /**
     * 类唯一标识
     * 格式：projectPath + ":" + className（全限定类名）
     */
    @Id
    @Property("classId")
    private String classId;

    /**
     * 全限定类名
     */
    @Property("className")
    private String className;

    /**
     * 包名
     */
    @Property("packageName")
    private String packageName;

    /**
     * 类声明签名（如 class OrderService extends BaseService）
     */
    @Property("signature")
    private String signature;

    /**
     * 类注释 (Javadoc)
     * 类描述生成的优先来源；无注释时由 LLM 汇总方法描述生成
     */
    @Property("classComment")
    private String classComment;

    /**
     * 类描述（类注释优先，无则 LLM 汇总方法描述）
     */
    @Property("description")
    private String description;

    /**
     * 类描述嵌入向量（基于类描述生成）
     * 用于类级语义检索
     * 维度: 2048 (ZhipuAI embedding-3)
     */
    @Property("descriptionEmbedding")
    private float[] descriptionEmbedding;

    /**
     * 类内方法数量
     */
    @Property("methodCount")
    private Integer methodCount;

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
     * 类级分层职责（Spring 技术分层）：CONTROLLER / SERVICE / REPOSITORY / MODEL / UTILITY / UNKNOWN。
     * 三级回退识别：注解优先 → 类名后缀 → 包名后缀；LLM 补全游离类。仅 Java/Spring 项目有值。
     */
    @Property("classRole")
    private String classRole;

    /**
     * 类职责来源标记：ANNOTATION / NAME / PACKAGE / LLM / UNKNOWN。
     * 区分「注解/命名确定的层级（高置信）」与「LLM 推测（低置信）」。
     */
    @Property("classRoleSource")
    private String classRoleSource;

    /**
     * 源文件路径（增量构建按文件删除类节点用）。
     */
    @Property("filePath")
    private String filePath;

    /**
     * 生成唯一的类节点 ID
     * @param projectPath 项目路径
     * @param className 全限定类名
     * @return 唯一节点 ID
     */
    public static String generateClassId(String projectPath, String className) {
        return projectPath + ":" + className;
    }
}
