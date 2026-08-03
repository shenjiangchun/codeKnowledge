package com.huawei.hisi.loganalysis.orchestrator;

import com.huawei.hisi.loganalysis.nodes.*;
import com.huawei.hisi.ram.kg.KgMcpClient;
import com.huawei.hisi.ram.kg.dto.CallTreeNode;
import com.huawei.hisi.ram.kg.dto.Entry;
import com.huawei.hisi.ram.kg.dto.MethodBodyInfo;
import com.huawei.hisi.ram.kg.dto.Seed;
import com.huawei.hisi.ram.nodes.impl.RamClaudeJsonClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Integration tests for LogAnalysisDagOrchestrator - complete DAG chain validation.
 * Tests multiple exception scenarios and validates layered extraction + KG tracking.
 */
@ExtendWith(MockitoExtension.class)
class LogAnalysisDagOrchestratorTest {

    @Mock
    private KgMcpClient kgMcpClient;

    @Mock
    private RamClaudeJsonClient claudeClient;

    private ParseNode parseNode;
    private KgSearchNode kgSearchNode;
    private CodeContextNode codeContextNode;
    private ClaudeAnalyzeNode claudeAnalyzeNode;
    private ReportNode reportNode;

    private LogAnalysisDagOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        // captureDecoder=null: ParseNode guards with captureDecoder != null; tests don't use HISI_CAPTURE
        parseNode = new ParseNode(null);
        kgSearchNode = new KgSearchNode(kgMcpClient);
        codeContextNode = new CodeContextNode(kgMcpClient);
        claudeAnalyzeNode = new ClaudeAnalyzeNode(claudeClient, new RoundPromptBuilder());
        reportNode = new ReportNode();

        orchestrator = new LogAnalysisDagOrchestrator(
                parseNode, kgSearchNode, codeContextNode, claudeAnalyzeNode, reportNode
        );

        // Default: Claude unavailable (use fallback analysis)
        lenient().when(claudeClient.isAvailable()).thenReturn(false);
    }

    @Test
    @DisplayName("完整链路：默认模式 - 业务帧提取 + KG 入口点")
    void completeChain_defaultMode() {
        // Mock KG responses
        when(kgMcpClient.hybridSearch(anyString(), anyList(), anyInt()))
                .thenReturn(List.of(new Seed("node-1", 0.85, "com.hisilicon.app.service.AppService.process")));

        when(kgMcpClient.rootEntries(anyString(), anyString(), anyList()))
                .thenReturn(List.of(
                        new Entry("entry-1", "com.hisilicon.app.controller.AppController", "handleRequest", "CONTROLLER"),
                        new Entry("entry-2", "com.hisilicon.app.listener.AppListener", "onMessage", "MQ_LISTENER")
                ));

        when(kgMcpClient.calleesTree(anyString(), anyString(), anyList(), anyInt()))
                .thenReturn(new CallTreeNode("node-1", "com.hisilicon.app.service.AppService", "process", 0, null));

        when(kgMcpClient.loadMethodBodies(anyList(), anyList()))
                .thenReturn(List.of(new MethodBodyInfo("node-1", "com.hisilicon.app.service.AppService", "process", "Process method", "public void process() { ... }", "AppService.java")));

        // Input with project package prefixes (default mode)
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("message", "Application error occurred");
        input.put("stackTrace", """
java.lang.RuntimeException: Application error
	at com.hisilicon.app.service.AppService.process(AppService.java:120)
	at com.hisilicon.app.handler.AppHandler.handle(AppHandler.java:50)
	at org.springframework.web.servlet.FrameworkServlet.service(FrameworkServlet.java:1002)
	at jakarta.servlet.http.HttpServlet.service(HttpServlet.java:590)
""");
        input.put("projectPath", "/path/to/project");
        input.put("projectPackagePrefixes", List.of("com.hisilicon"));
        input.put("deepMode", false); // Default mode

        // Execute DAG
        Map<String, Object> result = orchestrator.run(input);

        // Verify ParseNode output - layered extraction
        assertThat(result).containsKeys("parsedError", "keyFrames", "businessFrames", "rootCauseFrames");

        List<Map<String, Object>> businessFrames = (List<Map<String, Object>>) result.get("businessFrames");
        assertThat(businessFrames).hasSize(2); // AppService, AppHandler (project frames)

        // Verify KgSearchNode output - KG entry points
        assertThat(result).containsKeys("entryPoints", "businessEntryPoints");

        List<Entry> businessEntryPoints = (List<Entry>) result.get("businessEntryPoints");
        assertThat(businessEntryPoints).isNotEmpty(); // KG found entry points

        // Verify ReportNode output
        assertThat(result).containsKey("finalReport");
    }

    @Test
    @DisplayName("完整链路：深度模式 - 业务帧 + 根因帧提取")
    void completeChain_deepMode() {
        // Mock KG responses
        when(kgMcpClient.hybridSearch(anyString(), anyList(), anyInt()))
                .thenReturn(List.of(new Seed("node-1", 0.90, "com.hisilicon.core.CoreEngine.run")));

        when(kgMcpClient.rootEntries(anyString(), anyString(), anyList()))
                .thenReturn(List.of(
                        new Entry("entry-1", "com.hisilicon.app.controller.AppController", "handleRequest", "CONTROLLER"),
                        new Entry("entry-2", "com.hisilicon.core.scheduler.CoreScheduler", "schedule", "SCHEDULED")
                ));

        when(kgMcpClient.calleesTree(anyString(), anyString(), anyList(), anyInt()))
                .thenReturn(new CallTreeNode("node-1", "com.hisilicon.core.CoreEngine", "run", 0, null));

        lenient().when(kgMcpClient.loadMethodBodies(anyList(), anyList()))
                .thenReturn(List.of(new MethodBodyInfo("node-1", "com.hisilicon.core.CoreEngine", "run", "Core engine run", "public void run() { ... }", "CoreEngine.java")));

        // Input with nested exception and deep mode enabled
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("message", "Request failed");
        input.put("stackTrace", """
java.lang.RuntimeException: Surface error
	at com.hisilicon.app.controller.AppController.handle(AppController.java:50)
	at org.springframework.web.servlet.FrameworkServlet.service(FrameworkServlet.java:1002)
Caused by: java.lang.IllegalStateException: Invalid state
	at com.hisilicon.app.service.AppService.process(AppService.java:120)
Caused by: java.lang.NullPointerException: Root cause
	at com.hisilicon.core.util.CoreUtils.compute(CoreUtils.java:200)
	at com.hisilicon.core.engine.CoreEngine.run(CoreEngine.java:88)
""");
        input.put("projectPath", "/path/to/project");
        input.put("projectPackagePrefixes", List.of("com.hisilicon"));
        input.put("deepMode", true); // Deep mode enabled

        // Execute DAG
        Map<String, Object> result = orchestrator.run(input);

        // Verify ParseNode output - root cause exception identified
        Map<String, Object> parsedError = (Map<String, Object>) result.get("parsedError");
        assertThat(parsedError.get("rootCauseException")).isEqualTo("NullPointerException");

        // Verify layered extraction - business + root cause frames
        List<Map<String, Object>> businessFrames = (List<Map<String, Object>>) result.get("businessFrames");
        assertThat(businessFrames).hasSize(2); // AppController, AppService (before last Caused by)

        List<Map<String, Object>> rootCauseFrames = (List<Map<String, Object>>) result.get("rootCauseFrames");
        assertThat(rootCauseFrames).hasSize(2); // CoreUtils, CoreEngine (after last Caused by)

        // Verify key frames include both layers (deep mode)
        List<Map<String, Object>> keyFrames = (List<Map<String, Object>>) result.get("keyFrames");
        assertThat(keyFrames.size()).isGreaterThanOrEqualTo(3); // business (3) + root cause (some)

        // Verify KG entry points for both layers
        assertThat(result).containsKeys("businessEntryPoints", "rootCauseEntryPoints");
    }

    @Test
    @DisplayName("完整链路：KG降级 - 无KG数据时fallback生效")
    void completeChain_kgFallback() {
        // Mock KG returning empty (KG coverage gap) - use lenient for unused stubs
        lenient().when(kgMcpClient.hybridSearch(anyString(), anyList(), anyInt()))
                .thenReturn(Collections.emptyList());

        lenient().when(kgMcpClient.rootEntries(anyString(), anyString(), anyList()))
                .thenReturn(Collections.emptyList()); // KG returns empty

        lenient().when(kgMcpClient.calleesTree(anyString(), anyString(), anyList(), anyInt()))
                .thenReturn(null);

        lenient().when(kgMcpClient.loadMethodBodies(anyList(), anyList()))
                .thenReturn(Collections.emptyList());

        // Input without KG data
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("message", "Unknown error");
        input.put("stackTrace", """
java.lang.Exception: Unknown error
	at com.vendor.external.VendorService.process(VendorService.java:100)
	at com.other.lib.OtherLib.run(OtherLib.java:50)
""");
        input.put("projectPath", "/path/to/project");
        input.put("projectPackagePrefixes", List.of("com.hisilicon")); // Vendor not in project

        // Execute DAG
        Map<String, Object> result = orchestrator.run(input);

        // Verify ParseNode - otherNonFramework frames extracted
        List<Map<String, Object>> otherNonFrameworkFrames = (List<Map<String, Object>>) result.get("otherNonFrameworkFrames");
        assertThat(otherNonFrameworkFrames).hasSize(2); // VendorService, OtherLib

        // Verify KgSearchNode - fallback entry points generated (in entryPointsWithLayers)
        List<Map<String, Object>> entryPointsWithLayers = (List<Map<String, Object>>) result.get("entryPointsWithLayers");
        assertThat(entryPointsWithLayers).isNotEmpty(); // Fallback generated

        // Check fallback marker
        Map<String, Object> firstEntry = entryPointsWithLayers.get(0);
        assertThat(firstEntry.get("source")).isEqualTo("stack_trace"); // Fallback source
        assertThat(firstEntry.get("layer")).isEqualTo("fallback"); // Fallback layer
    }

    @Test
    @DisplayName("完整链路：堆栈极深 - 项目代码在50+位置")
    void completeChain_deepStack() {
        // Mock KG responses
        when(kgMcpClient.hybridSearch(anyString(), anyList(), anyInt()))
                .thenReturn(List.of(new Seed("node-1", 0.92, "com.hisilicon.core.ProcessEngine.execute")));

        when(kgMcpClient.rootEntries(anyString(), anyString(), anyList()))
                .thenReturn(List.of(new Entry("entry-1", "com.hisilicon.core.CoreScheduler", "schedule", "SCHEDULED")));

        when(kgMcpClient.calleesTree(anyString(), anyString(), anyList(), anyInt()))
                .thenReturn(new CallTreeNode("node-1", "com.hisilicon.core.ProcessEngine", "execute", 0, null));

        lenient().when(kgMcpClient.loadMethodBodies(anyList(), anyList()))
                .thenReturn(List.of(new MethodBodyInfo("node-1", "com.hisilicon.core.ProcessEngine", "execute", "Process engine execute", "public void execute() { ... }", "ProcessEngine.java")));

        // Build extremely deep stack trace (50+ frames before project code)
        StringBuilder stackTrace = new StringBuilder();
        stackTrace.append("java.lang.RuntimeException: Deep stack error\n");

        // Add 45 framework frames
        for (int i = 1; i <= 45; i++) {
            stackTrace.append("	at java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:").append(1100 + i).append(")\n");
        }

        // Add project code at position 46+
        stackTrace.append("	at com.hisilicon.core.engine.ProcessEngine.execute(ProcessEngine.java:125)\n");
        stackTrace.append("	at com.hisilicon.core.handler.RequestHandler.process(RequestHandler.java:88)\n");
        stackTrace.append("	at com.hisilicon.core.service.CoreService.doWork(CoreService.java:45)\n");

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("message", "Deep stack error");
        input.put("stackTrace", stackTrace.toString());
        input.put("projectPath", "/path/to/project");
        input.put("projectPackagePrefixes", List.of("com.hisilicon"));

        // Execute DAG
        Map<String, Object> result = orchestrator.run(input);

        // Verify ParseNode - project frames extracted even at deep position
        List<Map<String, Object>> businessFrames = (List<Map<String, Object>>) result.get("businessFrames");
        assertThat(businessFrames).hasSize(3); // ProcessEngine, RequestHandler, CoreService

        // Verify key frames
        List<Map<String, Object>> keyFrames = (List<Map<String, Object>>) result.get("keyFrames");
        assertThat(keyFrames).isNotEmpty();
        assertThat((String) keyFrames.get(0).get("className")).startsWith("com.hisilicon");
    }

    @Test
    @DisplayName("完整链路：无projectPackagePrefixes - 按顺序提取非框架帧")
    void completeChain_noPrefixes() {
        // Mock KG responses - use lenient for unused stubs
        lenient().when(kgMcpClient.hybridSearch(anyString(), anyList(), anyInt()))
                .thenReturn(Collections.emptyList());

        lenient().when(kgMcpClient.rootEntries(anyString(), anyString(), anyList()))
                .thenReturn(Collections.emptyList());

        lenient().when(kgMcpClient.loadMethodBodies(anyList(), anyList()))
                .thenReturn(Collections.emptyList());

        // Input without project package prefixes
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("message", "Error");
        input.put("stackTrace", """
java.lang.Exception: Error
	at com.vendor.external.VendorService.process(VendorService.java:100)
	at com.other.lib.OtherLib.run(OtherLib.java:50)
	at org.springframework.web.servlet.FrameworkServlet.service(FrameworkServlet.java:1002)
""");
        input.put("projectPath", "/path/to/project");
        // No projectPackagePrefixes

        // Execute DAG
        Map<String, Object> result = orchestrator.run(input);

        // Verify ParseNode - non-framework frames extracted in order
        List<Map<String, Object>> businessFrames = (List<Map<String, Object>>) result.get("businessFrames");
        assertThat(businessFrames).isEmpty(); // No project frames without prefixes

        List<Map<String, Object>> otherNonFrameworkFrames = (List<Map<String, Object>>) result.get("otherNonFrameworkFrames");
        assertThat(otherNonFrameworkFrames).hasSize(2); // VendorService, OtherLib

        // Verify key frames (fallback to otherNonFramework)
        List<Map<String, Object>> keyFrames = (List<Map<String, Object>>) result.get("keyFrames");
        assertThat(keyFrames).hasSize(2);
        assertThat(keyFrames.get(0).get("simpleClassName")).isEqualTo("VendorService");
    }

    @Test
    @DisplayName("递进搜索：第一批空时继续搜索后续帧，找到 KG 数据后停止")
    void progressiveSearch_firstBatchEmptyThenFound() {
        // 模拟第一批搜索返回空，第二批返回数据
        // 使用 lenient 避免 strict stubbing 问题

        // 第一批：keyFrames 中的帧返回空
        lenient().when(kgMcpClient.hybridSearch(anyString(), anyList(), anyInt()))
                .thenReturn(Collections.emptyList());

        // 重新设置第二批返回数据 - 使用 Answer 来根据参数返回不同结果
        when(kgMcpClient.hybridSearch(anyString(), anyList(), anyInt()))
                .thenAnswer(invocation -> {
                    String term = invocation.getArgument(0);
                    // 如果是 OtherLib.run，返回数据
                    if (term.contains("OtherLib")) {
                        return List.of(new Seed("node-other-1", 0.90, "com.other.lib.OtherLib#run"));
                    }
                    // 其他返回空
                    return Collections.emptyList();
                });

        lenient().when(kgMcpClient.rootEntries(anyString(), anyString(), anyList()))
                .thenReturn(Collections.emptyList());

        lenient().when(kgMcpClient.calleesTree(anyString(), anyString(), anyList(), anyInt()))
                .thenReturn(null);

        lenient().when(kgMcpClient.loadMethodBodies(anyList(), anyList()))
                .thenReturn(Collections.emptyList());

        lenient().when(claudeClient.isAvailable()).thenReturn(false);

        // Input with deep stack - first frames not in KG, later frames in KG
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("message", "Error");
        input.put("stackTrace", """
java.lang.Exception: Error
	at com.vendor.external.VendorService.process(VendorService.java:100)
	at com.other.lib.OtherLib.run(OtherLib.java:50)
	at com.kg.project.KgService.execute(KgService.java:30)
	at org.springframework.web.servlet.FrameworkServlet.service(FrameworkServlet.java:1002)
""");
        input.put("projectPath", "/path/to/project");
        // No projectPackagePrefixes - will use otherNonFrameworkFrames for progressive search

        // Execute DAG
        Map<String, Object> result = orchestrator.run(input);

        // Verify ParseNode - otherNonFrameworkFrames has 3 frames
        List<Map<String, Object>> otherNonFrameworkFrames = (List<Map<String, Object>>) result.get("otherNonFrameworkFrames");
        assertThat(otherNonFrameworkFrames).hasSize(3); // VendorService, OtherLib, KgService

        // Verify KgSearchNode - matchedMethods should have results from progressive search
        List<?> matchedMethods = (List<?>) result.get("matchedMethods");
        assertThat(matchedMethods).isNotEmpty(); // Should have found KG data via progressive search

        // Verify keyFrames was updated with KG-found frames
        List<Map<String, Object>> keyFrames = (List<Map<String, Object>>) result.get("keyFrames");
        assertThat(keyFrames.size()).isGreaterThanOrEqualTo(2); // Original + KG-found frames
    }

    @Test
    @DisplayName("递进搜索：所有批次都空 - 最终返回空结果但不报错")
    void progressiveSearch_allBatchesEmpty() {
        // 模拟所有搜索都返回空
        lenient().when(kgMcpClient.hybridSearch(anyString(), anyList(), anyInt()))
                .thenReturn(Collections.emptyList());

        lenient().when(kgMcpClient.rootEntries(anyString(), anyString(), anyList()))
                .thenReturn(Collections.emptyList());

        lenient().when(kgMcpClient.calleesTree(anyString(), anyString(), anyList(), anyInt()))
                .thenReturn(null);

        lenient().when(kgMcpClient.loadMethodBodies(anyList(), anyList()))
                .thenReturn(Collections.emptyList());

        lenient().when(claudeClient.isAvailable()).thenReturn(false);

        // Input with many frames - none in KG
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("message", "Error");
        input.put("stackTrace", """
java.lang.Exception: Error
	at com.vendor1.external.Vendor1Service.process(Vendor1Service.java:100)
	at com.vendor2.external.Vendor2Service.run(Vendor2Service.java:50)
	at com.vendor3.external.Vendor3Service.execute(Vendor3Service.java:30)
	at com.vendor4.external.Vendor4Service.handle(Vendor4Service.java:20)
	at com.vendor5.external.Vendor5Service.work(Vendor5Service.java:10)
	at org.springframework.web.servlet.FrameworkServlet.service(FrameworkServlet.java:1002)
""");
        input.put("projectPath", "/path/to/project");

        // Execute DAG - should not fail even with empty KG results
        Map<String, Object> result = orchestrator.run(input);

        // Verify matchedMethods is empty (all batches searched, none found)
        List<?> matchedMethods = (List<?>) result.get("matchedMethods");
        assertThat(matchedMethods).isEmpty();

        // Verify fallback entryPoints were generated
        List<Map<String, Object>> entryPointsWithLayers = (List<Map<String, Object>>) result.get("entryPointsWithLayers");
        assertThat(entryPointsWithLayers).isNotEmpty();
        assertThat(entryPointsWithLayers.get(0).get("source")).isEqualTo("stack_trace"); // Fallback source
    }
}