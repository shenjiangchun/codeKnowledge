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
 * 领域/DDD 边界检测结果节点
 * 通过 {@code BELONGS_TO} 关系关联 MethodNode，通过 {@code INTERACTS_WITH} 关系连接其他 DomainNode
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Node("DomainNode")
public class DomainNode {

    @Id
    @Property("domainId")
    private String domainId;  // projectPath + ":community-" + communityId

    @Property("domainName")
    private String domainName;  // LLM 生成的 2-4 字中文业务名

    @Property("confidence")
    private Double confidence;  // 0.0-1.0，包层次种子与 Louvain 结果的一致程度

    @Property("packageRoots")
    private List<String> packageRoots;  // 该领域包含的包前缀列表

    @Property("methodCount")
    private Integer methodCount;

    @Property("classCount")
    private Integer classCount;

    @Property("entryPoints")
    private List<String> entryPoints;  // 领域的入口点列表（API PATH / 定时任务等）

    @Property("projectPath")
    private String projectPath;
}
