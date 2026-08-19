package com.huawei.hisi.knowledgegraph.aggregation.stage;

import com.huawei.hisi.knowledgegraph.aggregation.AggregationCheckpointManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Stage 6: 领域交互边构建
 *
 * <p>DomainNode 及其 BELONGS_TO 边已由 Stage 5（{@link MultiDimensionCommunityDetector}）写入。
 * 本 Stage 仅负责计算领域之间的 INTERACTS_WITH 交互边（基于 BELONGS_TO 边 + 底层 CALLS 边），
 * 领域归属的唯一真相是 BELONGS_TO 边，不再依赖 businessNoun 属性。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DomainNameGenerator {

    private final Driver neo4jDriver;
    private final AggregationCheckpointManager checkpointManager;

    public void generate(String projectPath, boolean onlyIfDrifted) {
        log.info("[Aggregation][DomainName] generate 开始, projectPath={}", projectPath);

        long domainCount = countDomains(projectPath);
        buildDomainInteractions(projectPath);

        checkpointManager.markSuccess(projectPath, "DomainName", "domains=" + domainCount);
        log.info("[Aggregation] Stage=DomainName 完成, domains={}", domainCount);
    }

    private long countDomains(String projectPath) {
        try (Session session = neo4jDriver.session()) {
            var r = session.run(
                "MATCH (d:DomainNode {projectPath: $projectPath})\n" +
                "RETURN count(d) AS cnt",
                Map.of("projectPath", projectPath));
            return r.hasNext() ? r.next().get("cnt").asLong() : 0L;
        }
    }

    /** 领域间交互：基于三层结构（Domain -[:BELONGS_TO]-> Class -[:HAS_METHOD]-> Method）+ 底层 CALLS 边，聚合跨领域调用权重 */
    private void buildDomainInteractions(String projectPath) {
        try (Session session = neo4jDriver.session()) {
            var recs = session.run(
                "MATCH (a:Method {projectPath: $projectPath})-[r:CALLS]->(b:Method {projectPath: $projectPath})\n" +
                "MATCH (d1:DomainNode)-[:BELONGS_TO]->(:Class)-[:HAS_METHOD]->(a)\n" +
                "MATCH (d2:DomainNode)-[:BELONGS_TO]->(:Class)-[:HAS_METHOD]->(b)\n" +
                "WHERE d1.domainId <> d2.domainId\n" +
                "RETURN d1.domainId AS src, d2.domainId AS tgt, count(r) AS weight",
                Map.of("projectPath", projectPath));
            while (recs.hasNext()) {
                var r = recs.next();
                String src = r.get("src").asString();
                String tgt = r.get("tgt").asString();
                int weight = r.get("weight").asInt(0);
                session.run(
                    "MATCH (d1:DomainNode {domainId: $src})\n" +
                    "MATCH (d2:DomainNode {domainId: $tgt})\n" +
                    "MERGE (d1)-[x:INTERACTS_WITH]->(d2)\n" +
                    "SET x.weight = $weight",
                    Map.of("src", src, "tgt", tgt, "weight", weight));
            }
        }
    }
}
