package com.huawei.hisi.knowledgegraph.aggregation.stage;

import com.huawei.hisi.knowledgegraph.aggregation.AggregationCheckpointManager;
import com.huawei.hisi.knowledgegraph.aggregation.llm.DeepseekJsonClient;
import com.fasterxml.jackson.annotation.JsonClassDescription;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Stage 5: 领域检测（Louvain 技术耦合 + LLM 全局业务语义归纳）
 *
 * <p>信号 A（技术耦合）：纯 CALLS 边 Louvain 社区检测，由 {@link CommunityDetector}
 * 写入 MethodNode.communityId，仅用于计算领域 confidence（语义-耦合一致性）。
 *
 * <p>信号 B（业务语义，主信号）：LLM 一次性全局归纳全部类为业务领域（取代逐社区命名），
 * 输出「领域 → 类列表」，直接写入 DomainNode 并通过 {@code BELONGS_TO} 边关联 MethodNode。
 * 领域归属以 BELONGS_TO 边为唯一真相（不再写 MethodNode.businessNoun）。
 */
@Slf4j
@Component
public class MultiDimensionCommunityDetector {

    private final Driver neo4jDriver;
    private final SessionConfig neo4jSessionConfig;
    private final CommunityDetector communityDetector;
    private final AggregationCheckpointManager checkpointManager;
    /** 领域归纳用 deepseek 网关 OpenAI 链路（json_object 强制），替代 anthropic 中转 deepseek */
    private final DeepseekJsonClient deepseekJsonClient;

    public MultiDimensionCommunityDetector(
            Driver neo4jDriver,
            SessionConfig neo4jSessionConfig,
            CommunityDetector communityDetector,
            AggregationCheckpointManager checkpointManager,
            DeepseekJsonClient deepseekJsonClient) {
        this.neo4jDriver = neo4jDriver;
        this.neo4jSessionConfig = neo4jSessionConfig;
        this.communityDetector = communityDetector;
        this.checkpointManager = checkpointManager;
        this.deepseekJsonClient = deepseekJsonClient;
    }

    /** 每个类传给 LLM 的方法数上限（压缩上下文时逐级递减：5 → 2 → 0，0 表示只保留类名） */
    private static final int[] METHODS_PER_CLASS_LEVELS = {5, 2, 0};
    /** 单块类数阈值：超过则分块归纳，避免输出 JSON 超 maxTokens 被截断 */
    private static final int CLASSES_PER_BATCH = 120;
    /** 每块传给下一轮的已有领域代表类数（用于跨块领域归并，避免领域分裂） */
    private static final int REPRESENTATIVE_CLASSES = 3;

    public void detect(String projectPath) {
        log.info("[Aggregation][Community] 领域检测开始, projectPath={}", projectPath);

        // 信号 A：技术耦合 —— Louvain 社区（仅作 confidence 输入）
        communityDetector.detect(projectPath);

        // 信号 B：业务语义 —— LLM 全局归纳「领域 → 类列表」
        Map<String, List<String>> methodsByClass = loadClassMethods(projectPath);
        log.info("[Aggregation][Community] 待归纳的类: {} 个", methodsByClass.size());

        List<DomainClassList> domains = extractDomains(methodsByClass);
        if (domains.isEmpty()) {
            log.warn("[Aggregation] Stage=Community 降级: 领域归纳失败");
            checkpointManager.markSuccess(projectPath, "Community", "semantic-degraded;domains=0");
            return;
        }

        // 清理旧 DomainNode（DETACH DELETE 连带清理旧 BELONGS_TO 边）
        cleanupOldDomainNodes(projectPath);

        // 写 DomainNode + BELONGS_TO 边
        Map<String, Integer> communityByClass = loadCommunityByClass(projectPath);
        writeDomains(projectPath, domains, communityByClass, methodsByClass);

        checkpointManager.markSuccess(projectPath, "Community",
            "domains=" + domains.size() + ";classes=" + methodsByClass.size());
        log.info("[Aggregation][Community] 领域检测完成, 业务域={}, 类={}",
            domains.size(), methodsByClass.size());
    }

    // ── 读类 → 方法描述列表（逐方法 COALESCE：description 非空用描述，否则用签名） ──

    private Map<String, List<String>> loadClassMethods(String projectPath) {
        Map<String, List<String>> methodsByClass = new LinkedHashMap<>();
        try (Session s = neo4jDriver.session(neo4jSessionConfig)) {
            var r = s.run(
                "MATCH (m:Method {projectPath: $path})\n" +
                "WHERE m.className IS NOT NULL\n" +
                "RETURN m.className AS cls, m.methodName AS method, m.description AS desc, m.signature AS sig\n" +
                "ORDER BY m.className",
                Map.of("path", projectPath));
            while (r.hasNext()) {
                var rec = r.next();
                String cls = rec.get("cls").asString();
                if (isTestClass(cls)) continue;  // 测试类不属于业务领域
                String method = rec.get("method").asString("");
                String desc = rec.get("desc").asString(null);
                String sig = rec.get("sig").asString("");
                // 逐方法 COALESCE：有描述用描述，否则用方法签名
                String text = (desc != null && !desc.isBlank()) ? desc : (method + "(" + sig + ")");
                methodsByClass.computeIfAbsent(cls, k -> new ArrayList<>()).add(text);
            }
        }
        return methodsByClass;
    }

    private boolean isTestClass(String className) {
        String simple = className.substring(className.lastIndexOf('.') + 1);
        return simple.endsWith("Test") || simple.endsWith("Tests")
            || className.contains(".test.") || className.contains(".tests.");
    }

    // ── 信号 B：LLM 全局归纳「领域 → 类列表」 ──

    /** 结构化输出目标：领域名 + 该领域包含的完整类名列表 */
    @JsonClassDescription("领域分类：domainName 业务领域名词（2-4 字中文），classNames 该领域包含的完整类名列表（逐字回显，不要缩写）")
    public record DomainClassList(String domainName, List<String> classNames) {}
    @JsonClassDescription("领域全局归纳结果：domains 覆盖输入的全部类，每个类归属且仅归属一个领域，不要遗漏、不要重复")
    public record DomainGrouping(List<DomainClassList> domains) {}

    private List<DomainClassList> extractDomains(Map<String, List<String>> methodsByClass) {
        if (methodsByClass.isEmpty()) return List.of();

        List<String> classNames = new ArrayList<>(methodsByClass.keySet());
        // 预判分块：类数超过阈值时，分块归纳 + 跨块领域传递，避免单次输出超 maxTokens 被截断
        if (classNames.size() <= CLASSES_PER_BATCH) {
            return extractDomainsSingle(methodsByClass);
        }
        return extractDomainsBatched(methodsByClass, classNames);
    }

    /** 单次全局归纳（类数少时） */
    private List<DomainClassList> extractDomainsSingle(Map<String, List<String>> methodsByClass) {
        try {
            List<DomainClassList> domains = extractDomainsWithRetry(methodsByClass, List.of());
            if (domains.isEmpty()) {
                log.warn("[Aggregation] LLM 领域归纳返回空");
                return List.of();
            }
            logCoverage(methodsByClass, domains);
            return domains;
        } catch (Exception e) {
            log.warn("[Aggregation] LLM 领域归纳失败: {}", e.getMessage());
            return List.of();
        }
    }

    /** 分块归纳 + 跨块领域传递：每块只输出本块类的归属，代码侧累积合并 */
    private List<DomainClassList> extractDomainsBatched(Map<String, List<String>> methodsByClass,
                                                       List<String> classNames) {
        // 累积：领域名 → 类名列表（代码侧合并，保证跨块领域一致）
        Map<String, List<String>> domainToClasses = new LinkedHashMap<>();
        try {
            for (int start = 0; start < classNames.size(); start += CLASSES_PER_BATCH) {
                int end = Math.min(start + CLASSES_PER_BATCH, classNames.size());
                List<String> batchClasses = classNames.subList(start, end);
                Map<String, List<String>> batchMap = new LinkedHashMap<>();
                for (String cls : batchClasses) {
                    batchMap.put(cls, methodsByClass.get(cls));
                }

                // 传上一轮的「领域名 + 代表类」给 LLM，使新块类能并入已有领域
                List<DomainClassList> accumulated = toRepresentativeDomainList(domainToClasses);
                List<DomainClassList> domains = extractDomainsWithRetry(batchMap, accumulated);
                if (domains.isEmpty()) {
                    log.warn("[Aggregation] 分块 {} 归纳返回空，跳过", start / CLASSES_PER_BATCH);
                    continue;
                }
                // 代码侧累积：新块类并入对应领域（已有领域追加，新领域新建）
                for (DomainClassList d : domains) {
                    if (d.classNames() == null || d.classNames().isEmpty()) continue;
                    domainToClasses.computeIfAbsent(d.domainName(), k -> new ArrayList<>())
                        .addAll(d.classNames());
                }
            }
        } catch (Exception e) {
            log.warn("[Aggregation] 分块归纳失败: {}", e.getMessage());
        }

        // 转回 List<DomainClassList>
        List<DomainClassList> result = new ArrayList<>();
        for (var e : domainToClasses.entrySet()) {
            result.add(new DomainClassList(e.getKey(), e.getValue()));
        }
        logCoverage(methodsByClass, result);
        return result;
    }

    /** 把累积领域转成「领域名 + 前 N 个代表类」的列表，传给下一轮 */
    private List<DomainClassList> toRepresentativeDomainList(Map<String, List<String>> domainToClasses) {
        List<DomainClassList> rep = new ArrayList<>();
        for (var e : domainToClasses.entrySet()) {
            List<String> classes = e.getValue();
            List<String> repClasses = classes.subList(0, Math.min(REPRESENTATIVE_CLASSES, classes.size()));
            rep.add(new DomainClassList(e.getKey(), repClasses));
        }
        return rep;
    }

    private DomainGrouping callLlm(String prompt) {
        return deepseekJsonClient.chatJson(null, prompt, DomainGrouping.class);
    }

    /**
     * 压缩上下文重试：从「每个类最多 5 个方法描述」逐级降到「只保留类名」，
     * 每降一级重试一次，最多 {@code METHODS_PER_CLASS_LEVELS.length} 次。
     * 用于应对 JSON 输出被 maxTokens 截断导致提取失败的情况。
     */
    private List<DomainClassList> extractDomainsWithRetry(Map<String, List<String>> methodsByClass,
                                                          List<DomainClassList> accumulatedDomains) {
        for (int methodsPerClass : METHODS_PER_CLASS_LEVELS) {
            String prompt = buildPrompt(methodsByClass, accumulatedDomains, methodsPerClass);
            DomainGrouping grouping = callLlm(prompt);
            if (grouping != null && grouping.domains() != null && !grouping.domains().isEmpty()) {
                return grouping.domains();
            }
            log.warn("[Aggregation] LLM 归纳返回空（methodsPerClass={}），压缩上下文重试", methodsPerClass);
        }
        return List.of();
    }

    /** 组装 prompt：类列表（含方法描述）+ 可选已有领域上下文。methodsPerClass 控制每个类附带的方法描述数。 */
    private String buildPrompt(Map<String, List<String>> methodsByClass, List<DomainClassList> accumulatedDomains,
                               int methodsPerClass) {
        StringBuilder sb = new StringBuilder();
        for (var entry : methodsByClass.entrySet()) {
            sb.append(entry.getKey()).append(": ");
            List<String> methods = entry.getValue();
            if (methodsPerClass > 0) {
                List<String> head = methods.subList(0, Math.min(methodsPerClass, methods.size()));
                sb.append(String.join("; ", head));
                if (methods.size() > methodsPerClass) {
                    sb.append(" 等 ").append(methods.size()).append(" 个方法");
                }
            }
            sb.append('\n');
        }

        StringBuilder prompt = new StringBuilder(
            "你是代码架构分析专家。请把以下 Java 类归纳为业务领域。\n" +
            "规则：\n" +
            "1. 按业务主题归纳（如 订单/支付/代码扫描/日志分析），不要按技术分层（controller/service/repository/model/util 等是技术分层，不是业务领域）\n" +
            "2. 领域数量由你根据业务语义判断，不要为了凑数而拆分或合并\n" +
            "3. 每个类必须归属且仅归属一个领域，classNames 必须逐字回显完整类名（含包名），不要缩写\n" +
            "4. 覆盖输入的全部类，不要遗漏\n");

        if (!accumulatedDomains.isEmpty()) {
            prompt.append("\n以下是之前批次已归纳出的领域（含代表类）。本批次的新类如果属于其中某个领域，请直接并入该领域（domainName 与之一致）；如果不属于任何已有领域，才新建领域：\n");
            for (DomainClassList d : accumulatedDomains) {
                prompt.append("- ").append(d.domainName()).append(": ")
                    .append(String.join(", ", d.classNames())).append('\n');
            }
        }

        prompt.append("\n类列表（格式：完整类名: 方法描述列表）：\n").append(sb);
        prompt.append("\n只输出一个 JSON 对象，格式：{\"domains\":[{\"domainName\":\"领域名\",\"classNames\":[\"完整类名\"]}]}，")
             .append("不要 markdown 不要数组不要解释。");
        return prompt.toString();
    }

    /** 核对覆盖：统计归纳的类数 vs 输入类数，遗漏则告警 */
    private void logCoverage(Map<String, List<String>> methodsByClass, List<DomainClassList> domains) {
        Set<String> covered = new LinkedHashSet<>();
        for (DomainClassList d : domains) {
            if (d.classNames() != null) covered.addAll(d.classNames());
        }
        if (covered.size() < methodsByClass.size()) {
            log.warn("[Aggregation] LLM 领域归纳遗漏：输入 {} 类，仅覆盖 {} 类",
                methodsByClass.size(), covered.size());
        }
    }

    // ── 读每个类的 communityId（算 confidence 用） ──

    private Map<String, Integer> loadCommunityByClass(String projectPath) {
        Map<String, Integer> communityByClass = new LinkedHashMap<>();
        try (Session s = neo4jDriver.session(neo4jSessionConfig)) {
            var r = s.run(
                "MATCH (m:Method {projectPath: $path})\n" +
                "WHERE m.className IS NOT NULL AND m.communityId IS NOT NULL\n" +
                "RETURN m.className AS cls, m.communityId AS cid",
                Map.of("path", projectPath));
            while (r.hasNext()) {
                var rec = r.next();
                communityByClass.putIfAbsent(rec.get("cls").asString(), rec.get("cid").asInt());
            }
        }
        return communityByClass;
    }

    // ── 写 DomainNode + ClassNode + 三层边（Domain -[:BELONGS_TO]-> Class -[:HAS_METHOD]-> Method） ──

    private void writeDomains(String projectPath, List<DomainClassList> domains,
                              Map<String, Integer> communityByClass,
                              Map<String, List<String>> methodsByClass) {
        try (Session s = neo4jDriver.session(neo4jSessionConfig)) {
            for (DomainClassList domain : domains) {
                if (domain.classNames() == null || domain.classNames().isEmpty()) continue;
                List<String> classes = new ArrayList<>(new LinkedHashSet<>(domain.classNames()));
                int methodCount = classes.stream()
                    .mapToInt(c -> methodsByClass.getOrDefault(c, List.of()).size()).sum();

                // confidence = 语义-耦合一致性：领域内类落在同一技术社区的比例
                double confidence = computeConfidence(classes, communityByClass);

                String domainId = projectPath + ":domain:" + domain.domainName();
                s.run(
                    "MERGE (d:DomainNode {domainId: $id})\n" +
                    "SET d.domainName = $name, d.confidence = $conf, d.methodCount = $methods,\n" +
                    "    d.classCount = $classes, d.projectPath = $path",
                    Map.of("id", domainId, "name", domain.domainName(), "conf", confidence,
                        "methods", methodCount, "classes", classes.size(), "path", projectPath));

                // 三层结构：Domain -[:BELONGS_TO]-> Class -[:HAS_METHOD]-> Method
                // 注：ClassNode 结构已前置到图谱生成阶段（KnowledgeGraphBuilder），此处只建边
                for (String cls : classes) {
                    String classId = projectPath + ":" + cls;
                    // 1. Domain -[:BELONGS_TO]-> Class（ClassNode 已由图谱生成建好）
                    s.run(
                        "MATCH (d:DomainNode {domainId: $id})\n" +
                        "MATCH (c:Class {classId: $classId})\n" +
                        "MERGE (d)-[:BELONGS_TO]->(c)",
                        Map.of("id", domainId, "classId", classId));
                    // 2. Class -[:HAS_METHOD]-> Method
                    s.run(
                        "MATCH (c:Class {classId: $classId})\n" +
                        "MATCH (m:Method {projectPath: $path, className: $cls})\n" +
                        "MERGE (c)-[:HAS_METHOD]->(m)",
                        Map.of("classId", classId, "path", projectPath, "cls", cls));
                }
            }
        }
        log.info("[Aggregation][Community] 写入 {} 个领域 + ClassNode + 三层边", domains.size());
    }

    /** 从全限定类名提取包名（最后一个点之前的部分） */
    private String packageNameOf(String className) {
        int idx = className.lastIndexOf('.');
        return idx > 0 ? className.substring(0, idx) : "";
    }

    /** 语义-耦合一致性：领域内方法落在同一技术社区（communityId）的比例 */
    private double computeConfidence(List<String> classes, Map<String, Integer> communityByClass) {
        Map<Integer, Integer> cidCount = new HashMap<>();
        int total = 0;
        for (String cls : classes) {
            Integer cid = communityByClass.get(cls);
            if (cid != null) {
                cidCount.merge(cid, 1, Integer::sum);
                total++;
            }
        }
        if (total == 0) return 0.5;
        int max = cidCount.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        return (double) max / total;
    }

    private void cleanupOldDomainNodes(String projectPath) {
        try (Session s = neo4jDriver.session(neo4jSessionConfig)) {
            s.run(
                "MATCH (d:DomainNode {projectPath: $path}) DETACH DELETE d",
                Map.of("path", projectPath));
        }
        log.info("[Aggregation][Community] 已清理旧 DomainNode, projectPath={}", projectPath);
    }
}
