package com.huawei.hisi.archdiagnosis.domain;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 垂直领域聚合：同领域词 × 分层角色贯穿 → 领域；骨架判定剔除孤立数据对象。
 *
 * <p>只读 ClassNode 的 {@code classRole} 做纯计算，不落库、不碰生成侧。
 * 领域边界来自命名领域词（LLM 只做消歧/中文名，不猜边界）——
 * 与图谱社区检测（{@code DomainNode}）是两条独立链路，互不影响。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VerticalDomainAggregator {

    /** 业务分层角色：出现其一才算业务骨架（纯 MODEL/UTILITY 不算领域）。 */
    private static final Set<String> BUSINESS_ROLES = Set.of("CONTROLLER", "SERVICE", "REPOSITORY");

    private final Driver neo4jDriver;
    private final SessionConfig neo4jSessionConfig;

    private final VerticalDomainWordExtractor wordExtractor = new VerticalDomainWordExtractor();

    /**
     * 纯计算聚合：按剥离技术后缀后的领域词分组，统计分层角色，做骨架判定。
     *
     * @param records 类名 + 分层角色列表（诊断侧最小输入）
     * @return 完整领域 + 孤立数据对象
     */
    public VerticalDomainResult aggregate(List<ClassRoleRecord> records) {
        if (records == null || records.isEmpty()) {
            return new VerticalDomainResult(List.of(), List.of());
        }
        Map<String, List<ClassRoleRecord>> byWord = new LinkedHashMap<>();
        for (ClassRoleRecord record : records) {
            String word = wordExtractor.extract(record.className());
            if (word == null || word.isBlank()) {
                continue;
            }
            byWord.computeIfAbsent(word, k -> new ArrayList<>()).add(record);
        }

        List<VerticalDomain> domains = new ArrayList<>();
        List<VerticalDomain> isolated = new ArrayList<>();
        for (Map.Entry<String, List<ClassRoleRecord>> entry : byWord.entrySet()) {
            List<ClassRoleRecord> members = entry.getValue();
            Set<String> roles = new LinkedHashSet<>();
            for (ClassRoleRecord member : members) {
                String role = member.classRole();
                if (role != null && !role.isBlank() && !"UNKNOWN".equals(role)) {
                    roles.add(role);
                }
            }
            boolean complete = isComplete(roles);
            VerticalDomain domain = new VerticalDomain(entry.getKey(), members, roles, complete);
            (complete ? domains : isolated).add(domain);
        }

        Comparator<VerticalDomain> bySize =
            Comparator.comparingInt((VerticalDomain d) -> d.members().size()).reversed();
        domains.sort(bySize);
        isolated.sort(bySize);
        return new VerticalDomainResult(List.copyOf(domains), List.copyOf(isolated));
    }

    /** 骨架判定：≥2 分层角色且含业务角色（CONTROLLER/SERVICE/REPOSITORY）。 */
    private boolean isComplete(Set<String> roles) {
        if (roles.size() < 2) {
            return false;
        }
        return roles.stream().anyMatch(BUSINESS_ROLES::contains);
    }

    /**
     * 从 Neo4j 读取类名 + 分层角色（诊断侧只读，过滤无角色类）。
     *
     * @param paths 项目路径列表
     * @return 类名 + 分层角色记录
     */
    public List<ClassRoleRecord> loadClassRoles(List<String> paths) {
        List<ClassRoleRecord> records = new ArrayList<>();
        try (Session session = neo4jDriver.session(neo4jSessionConfig)) {
            var recs = session.run(
                "MATCH (c:Class) WHERE c.projectPath IN $paths AND c.classRole IS NOT NULL\n" +
                "RETURN c.className AS className, c.classRole AS classRole",
                Map.of("paths", paths));
            while (recs.hasNext()) {
                var r = recs.next();
                records.add(new ClassRoleRecord(
                    r.get("className").asString(""),
                    r.get("classRole").asString("")));
            }
        }
        return records;
    }
}
