package com.huawei.hisi.loganalysis;

import com.huawei.hisi.loganalysis.orchestrator.LogAnalysisDagOrchestrator;
import com.huawei.hisi.neo4j.repository.Neo4jMethodNodeRepository;
import com.huawei.hisi.neo4j.service.HybridSearchService;
import com.huawei.hisi.service.FingerprintService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.neo4j.core.Neo4jClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 日志分析流程调试 Demo
 * 使用模拟的真实异常日志数据，打印全流程中间变量
 */
@Slf4j
@SpringBootTest
public class LogAnalysisFlowDebugTest {

    @Autowired
    private FingerprintService fingerprintService;

    @Autowired(required = false)
    private LogAnalysisDagOrchestrator dagOrchestrator;

    @Autowired(required = false)
    private HybridSearchService hybridSearchService;

    @Autowired(required = false)
    private Neo4jClient neo4jClient;

    @Autowired(required = false)
    private Neo4jMethodNodeRepository methodNodeRepository;

    /**
     * 模拟一个真实的 Java 异常日志（包含错误消息和完整堆栈）
     */
    @Test
    void testFullFlowWithMockExceptionLog() {
        // ========== 1. 构造模拟日志数据 ==========
        String mockMessage = """
            2026-06-24 10:30:15.123 [http-nio-8080-exec-1 - trace-abc123] ERROR com.huawei.rms.service.impl.RequireStatusServiceImpl - 同步需求状态失败

            org.springframework.dao.DataAccessException: Cannot execute statement
            at org.springframework.jdbc.core.JdbcTemplate.execute(JdbcTemplate.java:420)
            at com.huawei.rms.service.impl.RequireStatusServiceImpl.syncReqStatus(RequireStatusServiceImpl.java:156)
            at com.huawei.rms.service.impl.RequireStatusServiceImpl$$FastClassBySpringCGLIB$$1.invoke(<generated>)
            at org.springframework.cglib.proxy.MethodProxy.invoke(MethodProxy.java:218)
            at org.springframework.aop.framework.CglibAopProxy$DynamicAdvisedInterceptor.intercept(CglibAopProxy.java:687)
            at com.huawei.rms.controller.RequireController.syncStatus(RequireController.java:89)
            at sun.reflect.NativeMethodAccessorImpl.invoke0(Native Method)
            at org.apache.tomcat.util.threads.TaskThread.run(TaskThread.java:61)
            Caused by: java.sql.SQLException: Connection refused
            at com.mysql.jdbc.JDBC4Connection.connect(JDBC4Connection.java:47)
            at org.springframework.jdbc.core.JdbcTemplate.execute(JdbcTemplate.java:398)
            ... 10 common frames omitted
            """;

        // 注意：标准 Java 堆栈每行 "at" 前面有 4 个空格，这是正则 ^\s+at\s+ 匹配的必要条件
        // 这里用 \n 显式构造，确保每行有正确的缩进
        String mockStackTrace =
            "org.springframework.dao.DataAccessException: Cannot execute statement\n" +
            "    at org.springframework.jdbc.core.JdbcTemplate.execute(JdbcTemplate.java:420)\n" +
            "    at com.huawei.rms.service.impl.RequireStatusServiceImpl.syncReqStatus(RequireStatusServiceImpl.java:156)\n" +
            "    at com.huawei.rms.controller.RequireController.syncStatus(RequireController.java:89)\n" +
            "Caused by: java.sql.SQLException: Connection refused\n" +
            "    at com.mysql.jdbc.JDBC4Connection.connect(JDBC4Connection.java:47)\n" +
            "    ... 10 common frames omitted";

        // 模拟 projectPath（逗号分隔多项目）
        String mockProjectPath = "D:/hisi-code-analyser/codeKnowledge1/remote-repos/rms,D:/hisi-code-analyser/codeKnowledge1/remote-repos/rms2";

        log.info("========== [DEBUG-TEST] 开始日志分析流程调试 ==========");
        log.info("[DEBUG-TEST] 模拟 message 长度: {}", mockMessage.length());
        log.info("[DEBUG-TEST] 模拟 stackTrace 长度: {}", mockStackTrace.length());
        log.info("[DEBUG-TEST] 模拟 projectPath: {}", mockProjectPath);

        // ========== 2. 测试指纹生成 ==========
        log.info("\n========== [DEBUG-TEST] Step 1: 指纹生成测试 ==========");
        String combinedContent = mockMessage + "\n" + mockStackTrace;
        String fingerprint = fingerprintService.generateFingerprint(combinedContent);
        log.info("[DEBUG-TEST] 生成的指纹: {}", fingerprint);
        log.info("[DEBUG-TEST] 指纹是否为全零: {}", fingerprint.equals("00000000000000000000000000000000"));

        // ========== 3. 测试 DAG 分析流程 ==========
        if (dagOrchestrator == null) {
            log.warn("[DEBUG-TEST] dagOrchestrator 未注入（Neo4j 可能未配置），跳过 DAG 测试");
            return;
        }

        log.info("\n========== [DEBUG-TEST] Step 2: DAG 分析流程测试 ==========");
        Map<String, Object> result = dagOrchestrator.analyzeLog(
                mockMessage,
                mockStackTrace,
                mockProjectPath,
                "rms-service",
                "trace-abc123"
        );

        log.info("[DEBUG-TEST] DAG 执行完成，output.keys: {}", result.keySet());

        // ========== 4. 检查各节点输出 ==========
        log.info("\n========== [DEBUG-TEST] Step 3: 检查各节点输出 ==========");

        // ParseNode 输出
        Object parsedError = result.get("parsedError");
        log.info("[DEBUG-TEST] parsedError: {}", parsedError);

        Object keyFrames = result.get("keyFrames");
        log.info("[DEBUG-TEST] keyFrames 数量: {}", keyFrames instanceof java.util.List l ? l.size() : "null");
        log.info("[DEBUG-TEST] keyFrames 内容: {}", keyFrames);

        Object searchTerms = result.get("searchTerms");
        log.info("[DEBUG-TEST] searchTerms 数量: {}", searchTerms instanceof java.util.List l ? l.size() : "null");
        log.info("[DEBUG-TEST] searchTerms 内容: {}", searchTerms);

        // KgSearchNode 输出
        Object matchedMethods = result.get("matchedMethods");
        log.info("[DEBUG-TEST] matchedMethods 数量: {}", matchedMethods instanceof java.util.List l ? l.size() : "null");

        Object callChains = result.get("callChains");
        log.info("[DEBUG-TEST] callChains 数量: {}", callChains instanceof java.util.List l ? l.size() : "null");

        Object entryPoints = result.get("entryPoints");
        log.info("[DEBUG-TEST] entryPoints 数量: {}", entryPoints instanceof java.util.List l ? l.size() : "null");

        // CodeContextNode 输出
        Object codeBodies = result.get("codeBodies");
        log.info("[DEBUG-TEST] codeBodies 数量: {}", codeBodies instanceof java.util.List l ? l.size() : "null");

        // 最终报告
        Object finalReport = result.get("finalReport");
        log.info("[DEBUG-TEST] finalReport: {}", finalReport);

        log.info("\n========== [DEBUG-TEST] 调试完成 ==========");
    }

    /**
     * 测试边缘情况：只有消息没有堆栈
     */
    @Test
    void testEdgeCase_NoStackTrace() {
        log.info("========== [DEBUG-TEST] 边缘情况测试：无堆栈 ==========");

        String mockMessage = "同步需求状态失败，数据库连接异常";
        String mockStackTrace = "";  // 空

        String fingerprint = fingerprintService.generateFingerprint(mockMessage + "\n" + mockStackTrace);
        log.info("[DEBUG-TEST] 无堆栈情况下生成的指纹: {}", fingerprint);

        if (dagOrchestrator != null) {
            Map<String, Object> result = dagOrchestrator.analyzeLog(
                    mockMessage, mockStackTrace, "D:/hisi-code-analyser/codeKnowledge1/remote-repos/rms", null, null);
            log.info("[DEBUG-TEST] 无堆栈情况下 searchTerms: {}", result.get("searchTerms"));
        }
    }

    /**
     * 测试边缘情况：空消息
     */
    @Test
    void testEdgeCase_EmptyMessage() {
        log.info("========== [DEBUG-TEST] 边缘情况测试：空消息 ==========");

        String mockMessage = "";
        String mockStackTrace = "";

        String fingerprint = fingerprintService.generateFingerprint(mockMessage + "\n" + mockStackTrace);
        log.info("[DEBUG-TEST] 空消息情况下生成的指纹: {}", fingerprint);
    }

    /**
     * 测试多种真实日志格式：不同缩进风格
     */
    @Test
    void testVariousStackTraceFormats() {
        log.info("========== [DEBUG-TEST] 多种堆栈格式测试 ==========");

        // 1. 标准 4 空格缩进
        String standardFormat =
            "java.lang.NullPointerException: Cannot invoke method on null object\n" +
            "    at com.huawei.rms.service.impl.OrderServiceImpl.processOrder(OrderServiceImpl.java:123)\n" +
            "    at com.huawei.rms.controller.OrderController.handleRequest(OrderController.java:45)\n" +
            "    at org.springframework.web.servlet.FrameworkServlet.processRequest(FrameworkServlet.java:101)\n" +
            "    ... 5 more";

        // 2. 无缩进（某些日志聚合系统）
        String noIndentFormat =
            "java.lang.IllegalArgumentException: Invalid parameter value\n" +
            "at com.huawei.rms.service.impl.UserService.validateInput(UserService.java:78)\n" +
            "at com.huawei.rms.controller.UserController.create(UserController.java:32)\n" +
            "at sun.reflect.NativeMethodAccessorImpl.invoke0(Native Method)\n" +
            "... 3 more";

        // 3. 制表符缩进
        String tabIndentFormat =
            "java.io.IOException: File not found\n" +
            "\tat com.huawei.rms.util.FileUtil.readFile(FileUtil.java:56)\n" +
            "\tat com.huawei.rms.service.impl.ReportServiceImpl.generateReport(ReportServiceImpl.java:89)\n" +
            "\t... 2 more";

        // 4. 包含 CGLIB 代理（应过滤）
        String cglibProxyFormat =
            "org.springframework.transaction.TransactionException: Transaction failed\n" +
            "    at com.huawei.rms.service.impl.PaymentServiceImpl.process(PaymentServiceImpl.java:67)\n" +
            "    at com.huawei.rms.service.impl.PaymentServiceImpl$$FastClassBySpringCGLIB$$1.invoke(<generated>)\n" +
            "    at org.springframework.cglib.proxy.MethodProxy.invoke(MethodProxy.java:218)\n" +
            "    at org.springframework.aop.framework.CglibAopProxy.intercept(CglibAopProxy.java:123)\n" +
            "    at com.huawei.rms.controller.PaymentController.pay(PaymentController.java:28)\n" +
            "    ... 8 more";

        // 5. 多层嵌套异常
        String nestedExceptionFormat =
            "org.springframework.dao.DataIntegrityViolationException: could not execute statement\n" +
            "    at com.huawei.rms.repository.OrderRepository.save(OrderRepository.java:45)\n" +
            "    at com.huawei.rms.service.impl.OrderServiceImpl.create(OrderServiceImpl.java:78)\n" +
            "Caused by: java.sql.SQLIntegrityConstraintViolationException: Duplicate entry '123' for key 'PRIMARY'\n" +
            "    at com.mysql.jdbc.JDBC4PreparedStatement.execute(JDBC4PreparedStatement.java:89)\n" +
            "Caused by: java.sql.SQLException: Duplicate key error\n" +
            "    at com.mysql.jdbc.MysqlIO.checkErrorPacket(MysqlIO.java:123)\n" +
            "    ... 10 common frames omitted";

        // 测试每种格式
        testStackTraceFormat("标准缩进", standardFormat);
        testStackTraceFormat("无缩进", noIndentFormat);
        testStackTraceFormat("制表符缩进", tabIndentFormat);
        testStackTraceFormat("CGLIB代理", cglibProxyFormat);
        testStackTraceFormat("嵌套异常", nestedExceptionFormat);
    }

    private void testStackTraceFormat(String formatName, String stackTrace) {
        log.info("\n[DEBUG-TEST] 测试格式: {}", formatName);
        String fingerprint = fingerprintService.generateFingerprint(stackTrace);
        log.info("[DEBUG-TEST] 指纹: {}", fingerprint);

        if (dagOrchestrator != null) {
            Map<String, Object> result = dagOrchestrator.analyzeLog(
                stackTrace.split("\n")[0], stackTrace,
                "D:/hisi-code-analyser/codeKnowledge1/remote-repos/rms", null, null);
            List<?> keyFrames = (List<?>) result.get("keyFrames");
            log.info("[DEBUG-TEST] keyFrames数量: {}, 内容: {}", keyFrames.size(), keyFrames);
        }
    }

    /**
     * 验证 KG 检索：使用实际项目路径测试
     */
    @Test
    void testKgSearchWithRealProjects() {
        log.info("========== [DEBUG-TEST] KG 检索验证 ==========");

        if (hybridSearchService == null) {
            log.warn("[DEBUG-TEST] hybridSearchService 未注入，跳过 KG 检索测试");
            return;
        }

        // 使用 KG 中实际存在的项目路径
        List<String> projectPaths = List.of(
            "C:/Users/47583/projects/hisi_dev_tool v5.0",
            "C:/Users/47583/projects/hisi-dev-tool"
        );

        // 测试检索不同的类（KG 中存在的类）
        String[] searchTerms = {
            "com.huawei.hisi.neo4j.service.HybridSearchService",
            "HybridSearchService",
            "知识图谱",
            "日志分析"
        };

        for (String term : searchTerms) {
            log.info("\n[DEBUG-TEST] 检索词: {}", term);
            try {
                // 使用正确的方法签名，传入 KG 中存在的项目路径
                var result = hybridSearchService.hybridSearch(term, projectPaths.get(0));
                log.info("[DEBUG-TEST] 检索结果数量: {}", result != null && result.getResults() != null ? result.getResults().size() : 0);
                if (result != null && result.getResults() != null && !result.getResults().isEmpty()) {
                    result.getResults().stream().limit(3).forEach(m -> {
                        log.info("[DEBUG-TEST] 结果: className={}, methodName={}, projectPath={}",
                            m.getClassName(), m.getMethodName(), m.getProjectPath());
                    });
                }
            } catch (Exception e) {
                log.error("[DEBUG-TEST] 检索失败: {}", e.getMessage());
            }
        }
    }

    /**
     * 全流程测试：真实 KG 项目路径
     */
    @Test
    void testFullFlowWithRealKgProject() {
        log.info("========== [DEBUG-TEST] 全流程测试（真实 KG 项目） ==========");

        // 使用当前项目作为 KG 测试对象
        String realProjectPath = "C:/Users/47583/projects/hisi_dev_tool v5.0/hisi-dev-tool";

        // 模拟一个当前项目的异常日志
        String mockLog =
            "2026-06-24 11:00:00.000 [main] ERROR com.huawei.hisi.neo4j.service.HybridSearchService - 检索失败\n" +
            "java.lang.RuntimeException: Vector index not found\n" +
            "    at com.huawei.hisi.neo4j.service.HybridSearchService.hybridSearch(HybridSearchService.java:156)\n" +
            "    at com.huawei.hisi.loganalysis.nodes.KgSearchNode.execute(KgSearchNode.java:89)\n" +
            "    at com.huawei.hisi.loganalysis.orchestrator.LogAnalysisDagOrchestrator.runNode(LogAnalysisDagOrchestrator.java:45)\n" +
            "    ... 10 common frames omitted";

        String stackTrace =
            "java.lang.RuntimeException: Vector index not found\n" +
            "    at com.huawei.hisi.neo4j.service.HybridSearchService.hybridSearch(HybridSearchService.java:156)\n" +
            "    at com.huawei.hisi.loganalysis.nodes.KgSearchNode.execute(KgSearchNode.java:89)\n" +
            "    at com.huawei.hisi.loganalysis.orchestrator.LogAnalysisDagOrchestrator.runNode(LogAnalysisDagOrchestrator.java:45)\n" +
            "    ... 10 common frames omitted";

        log.info("[DEBUG-TEST] 使用真实项目路径: {}", realProjectPath);

        if (dagOrchestrator == null) {
            log.warn("[DEBUG-TEST] dagOrchestrator 未注入，跳过");
            return;
        }

        Map<String, Object> result = dagOrchestrator.analyzeLog(
            mockLog, stackTrace, realProjectPath, "hisi-dev-tool", "trace-real-001");

        // 输出详细结果
        log.info("\n========== [DEBUG-TEST] 解析结果 ==========");
        log.info("[DEBUG-TEST] parsedError: {}", result.get("parsedError"));

        List<?> keyFrames = (List<?>) result.get("keyFrames");
        log.info("[DEBUG-TEST] keyFrames数量: {}, 内容: {}", keyFrames.size(), keyFrames);

        List<?> searchTerms = (List<?>) result.get("searchTerms");
        log.info("[DEBUG-TEST] searchTerms数量: {}, 内容: {}", searchTerms.size(), searchTerms);

        log.info("\n========== [DEBUG-TEST] KG 检索结果 ==========");
        List<?> matchedMethods = (List<?>) result.get("matchedMethods");
        log.info("[DEBUG-TEST] matchedMethods数量: {}", matchedMethods.size());
        if (!matchedMethods.isEmpty()) {
            matchedMethods.forEach(m -> log.info("[DEBUG-TEST] matchedMethod: {}", m));
        }

        List<?> callChains = (List<?>) result.get("callChains");
        log.info("[DEBUG-TEST] callChains数量: {}", callChains.size());

        List<?> entryPoints = (List<?>) result.get("entryPoints");
        log.info("[DEBUG-TEST] entryPoints数量: {}", entryPoints.size());

        log.info("\n========== [DEBUG-TEST] KG 检索诊断结论 ==========");
        if (matchedMethods.isEmpty() && !keyFrames.isEmpty()) {
            log.warn("[DEBUG-TEST] KG 无数据！keyFrames提取成功但KG检索返回0");
            log.warn("[DEBUG-TEST] 原因可能：KG未为该项目生成图谱，或 projectPath 配置不匹配");
        } else if (!matchedMethods.isEmpty()) {
            log.info("[DEBUG-TEST] KG 检索正常，成功匹配 {} 个方法", matchedMethods.size());
        } else {
            log.warn("[DEBUG-TEST] keyFrames 和 matchedMethods 都为 0，检查堆栈解析");
        }
    }

    /**
     * 检查 KG 中实际存在的项目路径和方法数量
     */
    @Test
    void checkKgDatabaseStatus() {
        log.info("========== [DEBUG-TEST] KG 数据库状态检查 ==========");

        if (neo4jClient == null) {
            log.warn("[DEBUG-TEST] neo4jClient 未注入，Neo4j 可能未配置");
            return;
        }

        // 1. 检查总节点数
        try {
            Optional<Long> totalMethods = neo4jClient.query("MATCH (m:MethodNode) RETURN count(m) as total")
                .fetchAs(Long.class)
                .one();
            log.info("[DEBUG-TEST] KG 中 MethodNode 总数: {}", totalMethods.orElse(0L));
        } catch (Exception e) {
            log.error("[DEBUG-TEST] 查询 MethodNode 总数失败: {}", e.getMessage());
        }

        // 2. 检查项目路径分布
        try {
            var projectPaths = neo4jClient.query(
                "MATCH (m:MethodNode) RETURN m.projectPath as path, count(*) as count ORDER BY count DESC LIMIT 10"
            ).fetch().all();
            log.info("[DEBUG-TEST] KG 中项目路径分布:");
            projectPaths.forEach(row -> {
                log.info("[DEBUG-TEST]   path={}, count={}", row.get("path"), row.get("count"));
            });
        } catch (Exception e) {
            log.error("[DEBUG-TEST] 查询项目路径分布失败: {}", e.getMessage());
        }

        // 3. 检查有 embedding 的节点数
        try {
            Optional<Long> withEmbedding = neo4jClient.query(
                "MATCH (m:MethodNode) WHERE m.descriptionEmbedding IS NOT NULL RETURN count(m) as total"
            ).fetchAs(Long.class).one();
            log.info("[DEBUG-TEST] 有 descriptionEmbedding 的 MethodNode 数量: {}", withEmbedding.orElse(0L));
        } catch (Exception e) {
            log.error("[DEBUG-TEST] 查询 embedding 数量失败: {}", e.getMessage());
        }

        // 4. 检查 EntryPointNode
        try {
            Optional<Long> entryPoints = neo4jClient.query(
                "MATCH (e:EntryPointNode) RETURN count(e) as total"
            ).fetchAs(Long.class).one();
            log.info("[DEBUG-TEST] EntryPointNode 总数: {}", entryPoints.orElse(0L));
        } catch (Exception e) {
            log.error("[DEBUG-TEST] 查询 EntryPointNode 失败: {}", e.getMessage());
        }

        // 5. 检查 CALLS 关系
        try {
            Optional<Long> calls = neo4jClient.query(
                "MATCH ()-[r:CALLS]->() RETURN count(r) as total"
            ).fetchAs(Long.class).one();
            log.info("[DEBUG-TEST] CALLS 关系总数: {}", calls.orElse(0L));
        } catch (Exception e) {
            log.error("[DEBUG-TEST] 查询 CALLS 关系失败: {}", e.getMessage());
        }

        log.info("\n========== [DEBUG-TEST] KG 检查完成 ==========");
    }
}