package com.huawei.hisi.loganalysis;

import com.huawei.hisi.loganalysis.orchestrator.LogAnalysisDagOrchestrator;
import com.huawei.hisi.service.FingerprintService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

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
}