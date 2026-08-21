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
     * 代码指纹 (SHA-256，用于全量-复用构建模式的节点复用判定)
     * 计算方式: SHA-256({@code className.methodName(signature)\ncomment\nmethodBody})
     * comment 为 null 时按空串参与；null 表示「未指纹」（向后兼容，首次复用构建回填）。
     */
    @Property("codeHash")
    private String codeHash;

    /**
     * 包名 (从全限定类名提取，如 com.example.service)
     * 构建时自动提取，迁移 API 可回填历史数据
     */
    @Property("packageName")
    private String packageName;

    /**
     * 入度 (被多少外部方法调用)
     * 聚合 Stage 计算，不走 mergeAll
     */
    @Property("inDegree")
    private Integer inDegree;

    /**
     * 出度 (调用了多少外部方法)
     * 聚合 Stage 计算，不走 mergeAll
     */
    @Property("outDegree")
    private Integer outDegree;

    /**
     * 社区检测结果 (Louvain 算法分配的社区 ID)
     * 聚合 Stage 计算，不走 mergeAll
     */
    @Property("communityId")
    private Integer communityId;

    /**
     * 综合风险分 (0.0-1.0)
     * 聚合 Stage 计算：复杂度×0.35 + churn×0.35 + 耦合×0.20 + 循环×0.10
     */
    @Property("riskScore")
    private Double riskScore;

    /**
     * 计算方法的代码指纹（SHA-256，64 位小写十六进制）。
     *
     * <p>计算输入：{@code className.methodName(signature)} + {@code "\n"} + {@code comment} + {@code "\n"} + {@code methodBody}。
     * comment 为 null 时按空串参与，保证同一方法在注释缺失/变更前后的 hash 稳定可比。
     * methodBody 为构建侧压缩后的方法体（已去注释），故注释变更的敏感性由 {@code comment} 参数单独承载。
     *
     * @return 64 位小写十六进制 SHA-256 摘要；各入参为 null 时按空串处理
     */
    public static String computeCodeHash(String className, String methodName, String signature,
                                         String comment, String methodBody) {
        String c = className == null ? "" : className;
        String m = methodName == null ? "" : methodName;
        String s = signature == null ? "" : signature;
        String cmt = comment == null ? "" : comment;
        String body = methodBody == null ? "" : methodBody;
        String input = c + "." + m + "(" + s + ")\n" + cmt + "\n" + body;
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 算法不可用", e);
        }
    }
}
