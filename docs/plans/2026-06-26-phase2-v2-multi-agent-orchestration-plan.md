# Phase2 V2 多 Agent 协作实现计划

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 实现多 Agent 协作的 Phase2 V2 现状分析系统，支持链路拆分、并行分析、分层报告、SVG 图表生成。

**Architecture:** Orchestrator 继承 Phase1 数据 → KG entryPoints 优先匹配 + grep 补充 → 拆分 N 条链路 → 动态分配工具权限 → 并行执行 Chain Agents → 分层合并报告。

**Tech Stack:** Spring Boot 3.2 + Java 17 + Claude SDK (Artifacts) + KG MCP + CompletableFuture

---

## Phase 1: 基础架构 (2 天)

### Task 1.1: 创建数据模型

**Files:**
- Create: `hisi-dev-tool/src/main/java/com/huawei/hisi/ram/phase2v2/model/ChainComplexity.java`
- Create: `hisi-dev-tool/src/main/java/com/huawei/hisi/ram/phase2v2/model/ChainContext.java`
- Create: `hisi-dev-tool/src/main/java/com/huawei/hisi/ram/phase2v2/model/ChainReport.java`
- Create: `hisi-dev-tool/src/main/java/com/huawei/hisi/ram/phase2v2/model/Phase2V2Report.java`
- Create: `hisi-dev-tool/src/main/java/com/huawei/hisi/ram/phase2v2/model/SummaryLayer.java`
- Create: `hisi-dev-tool/src/main/java/com/huawei/hisi/ram/phase2v2/model/DetailLayer.java`

**Step 1: Write ChainComplexity enum**

```java
// hisi-dev-tool/src/main/java/com/huawei/hisi/ram/phase2v2/model/ChainComplexity.java
package com.huawei.hisi.ram.phase2v2.model;

/**
 * 链路复杂度分级，用于动态分配工具权限。
 */
public enum ChainComplexity {
    /** 单服务单模块链路 - 最小工具集 */
    SIMPLE,
    
    /** 跨服务 Feign/MQ 链路 - 增加 WebFetch */
    CROSS_SERVICE,
    
    /** 领域级复杂分析 - 增加 Bash */
    DOMAIN_ANALYSIS,
    
    /** 需要编译/测试验证 - 增加 Agent (嵌套) */
    VERIFICATION
}
```

**Step 2: Write ChainContext record**

```java
// hisi-dev-tool/src/main/java/com/huawei/hisi/ram/phase2v2/model/ChainContext.java
package com.huawei.hisi.ram.phase2v2.model;

import com.huawei.hisi.ram.kg.dto.Entry;
import com.huawei.hisi.ram.phase2v2.model.ChainComplexity;

import java.util.List;

/**
 * 单条链路的上下文数据，由 Orchestrator 创建并传递给 ChainAnalysisAgent。
 */
public record ChainContext(
    /** 链路唯一标识 */
    String chainId,
    
    /** 链路名称 (如: "订单创建链路") */
    String chainName,
    
    /** 链路入口点 */
    Entry entryPoint,
    
    /** 用户原始问题 */
    String question,
    
    /** 项目路径 */
    String projectPath,
    
    /** 父 session ID (Phase1) */
    String parentSessionId,
    
    /** 链路复杂度 */
    ChainComplexity complexity,
    
    /** 允许的工具集 */
    List<String> allowedTools,
    
    /** Phase1 继承的宏观数据 (可选) */
    Phase1InheritedData inheritedData
) {
    /**
     * Phase1 继承的数据 (entryPoints, bridgeStats, coreMethods)。
     */
    public record Phase1InheritedData(
        List<Entry> entryPoints,
        long totalBridges,
        long feignCount,
        long mqCount,
        List<String> coreMethodNodeIds
    ) {}
}
```

**Step 3: Write ChainReport record**

```java
// hisi-dev-tool/src/main/java/com/huawei/hisi/ram/phase2v2/model/ChainReport.java
package com.huawei.hisi.ram.phase2v2.model;

import java.util.List;
import java.util.Map;

/**
 * 单条链路的完整分析报告，由 ChainAnalysisAgent 产出。
 */
public record ChainReport(
    /** 链路唯一标识 */
    String chainId,
    
    /** 链路名称 */
    String chainName,
    
    /** 入口点信息 */
    EntryPointInfo entryPoint,
    
    /** 分析结果 */
    AnalysisResult analysis,
    
    /** KG 原始数据 (供 Orchestrator 合并) */
    KgRawData kgData,
    
    /** 执行状态 */
    String status,
    
    /** 错误信息 (如有) */
    String error
) {
    public record EntryPointInfo(
        String type,
        String className,
        String methodName,
        String nodeId
    ) {}
    
    public record AnalysisResult(
        /** 摘要 (≤100 字) */
        String summary,
        
        /** 调用链流程图 SVG */
        String callChainFlowSvg,
        
        /** 时序图 SVG */
        String sequenceDiagramSvg,
        
        /** 状态流转图 SVG (如适用) */
        String stateDiagramSvg,
        
        /** 代码片段列表 */
        List<CodeSnippet> codeSnippets,
        
        /** 建议列表 */
        List<Recommendation> recommendations,
        
        /** 置信度评估 */
        Confidence confidence
    ) {}
    
    public record CodeSnippet(
        String nodeId,
        String className,
        String methodName,
        String filePath,
        String snippet,
        String relevance
    ) {}
    
    public record Recommendation(
        int sequence,
        String action,
        String target,
        String reason
    ) {}
    
    public record Confidence(
        String level,
        KgCoverage kgCoverage,
        List<String> limitations
    ) {}
    
    public record KgCoverage(
        boolean upstreamComplete,
        boolean downstreamComplete,
        int codeBodiesLoaded,
        List<String> missingInfo
    ) {}
    
    public record KgRawData(
        List<Map<String, Object>> upstreamChains,
        List<Map<String, Object>> downstreamChains,
        List<Map<String, Object>> methodBodies,
        List<Map<String, Object>> bridgePoints
    ) {}
}
```

**Step 4: Write SummaryLayer record**

```java
// hisi-dev-tool/src/main/java/com/huawei/hisi/ram/phase2v2/model/SummaryLayer.java
package com.huawei.hisi.ram.phase2v2.model;

import java.util.List;

/**
 * 第一层：领域概览报告。
 */
public record SummaryLayer(
    /** 领域概览描述 */
    String domainOverview,
    
    /** 整体流程图 SVG (合并所有链路) */
    String overallFlowDiagramSvg,
    
    /** 关键发现列表 */
    List<KeyFinding> keyFindings,
    
    /** 跨链路影响分析 */
    List<CrossChainImpact> crossChainImpacts,
    
    /** 整体建议 */
    List<String> overallRecommendations
) {
    public record KeyFinding(
        int id,
        String type,
        String description,
        List<String> chains
    ) {}
    
    public record CrossChainImpact(
        String fromChain,
        String toChain,
        String relation,
        String description
    ) {}
}
```

**Step 5: Write DetailLayer record**

```java
// hisi-dev-tool/src/main/java/com/huawei/hisi/ram/phase2v2/model/DetailLayer.java
package com.huawei.hisi.ram.phase2v2.model;

import java.util.List;

/**
 * 第二层：详细链路报告列表。
 */
public record DetailLayer(
    /** 链路报告摘要列表 */
    List<ChainSummary> chains,
    
    /** 链路总数 */
    int chainCount,
    
    /** 分析的总方法数 */
    int totalMethodsAnalyzed,
    
    /** 代码片段总数 */
    int totalCodeSnippets
) {
    public record ChainSummary(
        String chainId,
        String chainName,
        String summary,
        boolean expandable,
        String reportRef
    ) {}
}
```

**Step 6: Write Phase2V2Report record**

```java
// hisi-dev-tool/src/main/java/com/huawei/hisi/ram/phase2v2/model/Phase2V2Report.java
package com.huawei.hisi.ram.phase2v2.model;

import com.huawei.hisi.ram.phase2v2.model.SummaryLayer;
import com.huawei.hisi.ram.phase2v2.model.DetailLayer;

/**
 * Phase2 V2 分层报告完整结构。
 */
public record Phase2V2Report(
    /** 第一层：概览 */
    SummaryLayer summaryLayer,
    
    /** 第二层：详细 */
    DetailLayer detailLayer,
    
    /** 执行状态 */
    String status,
    
    /** 用户原始问题 */
    String question
) {}
```

**Step 7: Verify compilation**

Run: `cd hisi-dev-tool && mvn compile -DskipTests -q`
Expected: No errors

**Step 8: Commit**

```bash
git add hisi-dev-tool/src/main/java/com/huawei/hisi/ram/phase2v2/model/*.java
git commit -m "feat(phase2v2): add data models for multi-agent orchestration

- ChainComplexity enum for dynamic tool allocation
- ChainContext/ChainReport for single chain analysis
- SummaryLayer/DetailLayer for layered report structure
- Phase2V2Report as final output format

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 1.2: 创建动态工具注册器

**Files:**
- Create: `hisi-dev-tool/src/main/java/com/huawei/hisi/ram/phase2v2/DynamicToolRegistry.java`
- Test: `hisi-dev-tool/src/test/java/com/huawei/hisi/ram/phase2v2/DynamicToolRegistryTest.java`

**Step 1: Write failing test**

```java
// hisi-dev-tool/src/test/java/com/huawei/hisi/ram/phase2v2/DynamicToolRegistryTest.java
package com.huawei.hisi.ram.phase2v2;

import com.huawei.hisi.ram.phase2v2.DynamicToolRegistry;
import com.huawei.hisi.ram.phase2v2.model.ChainComplexity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DynamicToolRegistryTest {

    @Test
    void getToolsForSimpleChain_returnsMinimalSet() {
        DynamicToolRegistry registry = new DynamicToolRegistry();
        List<String> tools = registry.getTools(ChainComplexity.SIMPLE);
        
        assertThat(tools).containsExactlyInAnyOrder(
            "KG_MCP", "Read", "Grep", "Glob", "Artifacts"
        );
    }

    @Test
    void getToolsForCrossService_addsWebFetch() {
        DynamicToolRegistry registry = new DynamicToolRegistry();
        List<String> tools = registry.getTools(ChainComplexity.CROSS_SERVICE);
        
        assertThat(tools).contains("WebFetch");
        assertThat(tools).hasSize(6); // SIMPLE + WebFetch
    }

    @Test
    void getToolsForDomainAnalysis_addsBash() {
        DynamicToolRegistry registry = new DynamicToolRegistry();
        List<String> tools = registry.getTools(ChainComplexity.DOMAIN_ANALYSIS);
        
        assertThat(tools).contains("Bash");
        assertThat(tools).hasSize(7); // CROSS_SERVICE + Bash
    }

    @Test
    void getToolsForVerification_addsAgent() {
        DynamicToolRegistry registry = new DynamicToolRegistry();
        List<String> tools = registry.getTools(ChainComplexity.VERIFICATION);
        
        assertThat(tools).contains("Agent");
        assertThat(tools).hasSize(8); // DOMAIN_ANALYSIS + Agent
    }
}
```

**Step 2: Run test to verify it fails**

Run: `cd hisi-dev-tool && mvn test -Dtest=DynamicToolRegistryTest -q`
Expected: FAIL - class not found

**Step 3: Write implementation**

```java
// hisi-dev-tool/src/main/java/com/huawei/hisi/ram/phase2v2/DynamicToolRegistry.java
package com.huawei.hisi.ram.phase2v2;

import com.huawei.hisi.ram.phase2v2.model.ChainComplexity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 根据链路复杂度动态分配 Claude SDK 工具权限。
 */
@Component
public class DynamicToolRegistry {

    private static final List<String> BASE_TOOLS = List.of(
        "KG_MCP", "Read", "Grep", "Glob", "Artifacts"
    );

    /**
     * 根据复杂度返回允许的工具集。
     */
    public List<String> getTools(ChainComplexity complexity) {
        List<String> tools = new ArrayList<>(BASE_TOOLS);
        
        switch (complexity) {
            case SIMPLE:
                // 基础工具集，无需添加
                break;
                
            case CROSS_SERVICE:
                // 跨服务链路，增加 WebFetch 查询外部文档
                tools.add("WebFetch");
                break;
                
            case DOMAIN_ANALYSIS:
                // 领域级分析，增加 Bash 执行构建/依赖分析
                tools.add("WebFetch");
                tools.add("Bash");
                break;
                
            case VERIFICATION:
                // 需要验证，增加 Agent 嵌套 (仅允许一层)
                tools.add("WebFetch");
                tools.add("Bash");
                tools.add("Agent");
                break;
        }
        
        return tools;
    }
}
```

**Step 4: Run test to verify it passes**

Run: `cd hisi-dev-tool && mvn test -Dtest=DynamicToolRegistryTest -q`
Expected: PASS

**Step 5: Commit**

```bash
git add hisi-dev-tool/src/main/java/com/huawei/hisi/ram/phase2v2/DynamicToolRegistry.java
git add hisi-dev-tool/src/test/java/com/huawei/hisi/ram/phase2v2/DynamicToolRegistryTest.java
git commit -m "feat(phase2v2): add DynamicToolRegistry for complexity-based tool allocation

- SIMPLE: KG_MCP, Read, Grep, Glob, Artifacts
- CROSS_SERVICE: + WebFetch
- DOMAIN_ANALYSIS: + Bash
- VERIFICATION: + Agent (nested)

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 1.3: 创建链路拆分器

**Files:**
- Create: `hisi-dev-tool/src/main/java/com/huawei/hisi/ram/phase2v2/ChainSplitter.java`
- Test: `hisi-dev-tool/src/test/java/com/huawei/hisi/ram/phase2v2/ChainSplitterTest.java`

**Step 1: Write failing test**

```java
// hisi-dev-tool/src/test/java/com/huawei/hisi/ram/phase2v2/ChainSplitterTest.java
package com.huawei.hisi.ram.phase2v2;

import com.huawei.hisi.ram.kg.dto.Entry;
import com.huawei.hisi.ram.phase2v2.ChainSplitter;
import com.huawei.hisi.ram.phase2v2.model.ChainContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChainSplitterTest {

    @Test
    void splitByEntryPoints_returnsOneContextPerEntry() {
        List<Entry> entries = List.of(
            new Entry("node1", "OrderController", "createOrder", "Controller", null, null, null),
            new Entry("node2", "PaymentController", "pay", "Controller", null, null, null)
        );
        
        ChainSplitter splitter = new ChainSplitter();
        List<ChainContext> contexts = splitter.split(
            entries, 
            "订单处理流程是怎样的？",
            "/path/to/project",
            "parent-session-123"
        );
        
        assertThat(contexts).hasSize(2);
        assertThat(contexts.get(0).chainName()).contains("OrderController");
        assertThat(contexts.get(1).chainName()).contains("PaymentController");
    }

    @Test
    void filterByKeywords_retainsOnlyRelevantEntries() {
        List<Entry> entries = List.of(
            new Entry("node1", "OrderController", "createOrder", "Controller", null, null, null),
            new Entry("node2", "UserController", "login", "Controller", null, null, null),
            new Entry("node3", "OrderMQConsumer", "handleMessage", "MQ_LISTENER", null, null, null)
        );
        
        ChainSplitter splitter = new ChainSplitter();
        List<ChainContext> contexts = splitter.split(
            entries,
            "订单流程",
            "/path/to/project",
            "parent-session-123"
        );
        
        // 只保留包含 Order 的入口点
        assertThat(contexts).hasSize(2);
        assertThat(contexts.stream().map(c -> c.chainName()).toList())
            .allMatch(name -> name.contains("Order"));
    }
}
```

**Step 2: Run test to verify it fails**

Run: `cd hisi-dev-tool && mvn test -Dtest=ChainSplitterTest -q`
Expected: FAIL - class not found

**Step 3: Write implementation**

```java
// hisi-dev-tool/src/main/java/com/huawei/hisi/ram/phase2v2/ChainSplitter.java
package com.huawei.hisi.ram.phase2v2;

import com.huawei.hisi.ram.kg.dto.Entry;
import com.huawei.hisi.ram.phase2v2.DynamicToolRegistry;
import com.huawei.hisi.ram.phase2v2.model.ChainComplexity;
import com.huawei.hisi.ram.phase2v2.model.ChainContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 链路拆分器：根据 KG entryPoints 和用户问题关键词拆分独立链路。
 */
@Component
@RequiredArgsConstructor
public class ChainSplitter {

    private final DynamicToolRegistry toolRegistry;

    /**
     * 将 entryPoints 拆分为独立 ChainContext。
     * 
     * @param entries KG 入口点列表
     * @param question 用户问题
     * @param projectPath 项目路径
     * @param parentSessionId Phase1 session ID
     * @return ChainContext 列表
     */
    public List<ChainContext> split(
            List<Entry> entries,
            String question,
            String projectPath,
            String parentSessionId) {
        
        List<ChainContext> contexts = new ArrayList<>();
        
        // 1. 从问题提取关键词
        List<String> keywords = extractKeywords(question);
        
        // 2. 过滤相关入口点
        List<Entry> relevantEntries = filterByKeywords(entries, keywords);
        
        // 3. 每个入口点创建一个 ChainContext
        for (Entry entry : relevantEntries) {
            String chainId = UUID.randomUUID().toString().substring(0, 8);
            String chainName = buildChainName(entry);
            ChainComplexity complexity = inferComplexity(entry, question);
            List<String> tools = toolRegistry.getTools(complexity);
            
            ChainContext context = new ChainContext(
                chainId,
                chainName,
                entry,
                question,
                projectPath,
                parentSessionId,
                complexity,
                tools,
                null  // inheritedData 由 Orchestrator 设置
            );
            
            contexts.add(context);
        }
        
        return contexts;
    }

    /**
     * 从问题提取关键词 (简单实现，后续可替换为 LLM)。
     */
    private List<String> extractKeywords(String question) {
        // 中文关键词提取
        String[] words = question.split("[\\s,，。.!！?？、]+");
        return List.of(words).stream()
            .filter(w -> w.length() >= 2)
            .toList();
    }

    /**
     * 过滤与关键词相关的入口点。
     */
    private List<Entry> filterByKeywords(List<Entry> entries, List<String> keywords) {
        return entries.stream()
            .filter(entry -> matchesKeywords(entry, keywords))
            .toList();
    }

    /**
     * 检查入口点是否匹配关键词。
     */
    private boolean matchesKeywords(Entry entry, List<String> keywords) {
        String className = entry.className() != null ? entry.className() : "";
        String methodName = entry.methodName() != null ? entry.methodName() : "";
        String combined = className + "." + methodName;
        
        for (String keyword : keywords) {
            if (combined.toLowerCase().contains(keyword.toLowerCase())) {
                return true;
            }
        }
        
        // 如果关键词为空，保留所有入口点
        return keywords.isEmpty();
    }

    /**
     * 构建链路名称。
     */
    private String buildChainName(Entry entry) {
        String type = entry.type() != null ? entry.type() : "UNKNOWN";
        String className = entry.className() != null ? entry.className() : "Unknown";
        String methodName = entry.methodName() != null ? entry.methodName() : "unknown";
        
        return switch (type) {
            case "Controller" -> className + " 控制器链路";
            case "MQ_LISTENER" -> className + " MQ消费链路";
            case "FEIGN_CLIENT" -> className + " Feign调用链路";
            case "SCHEDULED" -> className + " 定时任务链路";
            default -> className + "#" + methodName + " 链路";
        };
    }

    /**
     * 推断链路复杂度。
     */
    private ChainComplexity inferComplexity(Entry entry, String question) {
        // MQ/Feign 入口点 → CROSS_SERVICE
        if ("MQ_LISTENER".equals(entry.type()) || "FEIGN_CLIENT".equals(entry.type())) {
            return ChainComplexity.CROSS_SERVICE;
        }
        
        // 问题包含"验证"、"测试" → VERIFICATION
        if (question.contains("验证") || question.contains("测试") || question.contains("编译")) {
            return ChainComplexity.VERIFICATION;
        }
        
        // 默认 SIMPLE
        return ChainComplexity.SIMPLE;
    }
}
```

**Step 4: Run test to verify it passes**

Run: `cd hisi-dev-tool && mvn test -Dtest=ChainSplitterTest -q`
Expected: PASS

**Step 5: Commit**

```bash
git add hisi-dev-tool/src/main/java/com/huawei/hisi/ram/phase2v2/ChainSplitter.java
git add hisi-dev-tool/src/test/java/com/huawei/hisi/ram/phase2v2/ChainSplitterTest.java
git commit -m "feat(phase2v2): add ChainSplitter for entryPoints-based chain splitting

- Extract keywords from question
- Filter entryPoints by keyword matching
- Create ChainContext per entry point
- Infer complexity from entry type and question

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Phase 2: Orchestrator 核心 (2 天)

### Task 2.1: 创建 Phase2V2Orchestrator

**Files:**
- Create: `hisi-dev-tool/src/main/java/com/huawei/hisi/ram/phase2v2/Phase2V2Orchestrator.java`
- Test: `hisi-dev-tool/src/test/java/com/huawei/hisi/ram/phase2v2/Phase2V2OrchestratorTest.java`

**Step 1: Write failing test (骨架)**

```java
// hisi-dev-tool/src/test/java/com/huawei/hisi/ram/phase2v2/Phase2V2OrchestratorTest.java
package com.huawei.hisi.ram.phase2v2;

import com.huawei.hisi.ram.phase2v2.Phase2V2Orchestrator;
import com.huawei.hisi.ram.phase2v2.model.Phase2V2Report;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class Phase2V2OrchestratorTest {

    @Test
    void orchestrate_returnsLayeredReport() {
        // 集成测试，后续补充
        Phase2V2Orchestrator orchestrator = new Phase2V2Orchestrator(null, null, null, null);
        
        // 验证基本结构存在
        assertThat(orchestrator).isNotNull();
    }
}
```

**Step 2: Write implementation (骨架)**

```java
// hisi-dev-tool/src/main/java/com/huawei/hisi/ram/phase2v2/Phase2V2Orchestrator.java
package com.huawei.hisi.ram.phase2v2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.ram.kg.KgMcpClient;
import com.huawei.hisi.ram.kg.dto.Entry;
import com.huawei.hisi.ram.phase2v2.model.*;
import com.huawei.hisi.ram.repository.AgentEventRepository;
import com.huawei.hisi.ram.repository.AgentSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Phase2 V2 多 Agent 协作编排器。
 * 
 * <p>核心流程:
 * <ol>
 *     <li>继承 Phase1 checkpoint 数据 (entryPoints, bridgeStats)</li>
 *     <li>KG entryPoints 匹配 + grep 补充 → 拆分链路</li>
 *     <li>动态分配工具权限</li>
 *     <li>并行执行 Chain Agents</li>
 *     <li>分层合并报告</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Phase2V2Orchestrator {

    private final KgMcpClient kgClient;
    private final ChainSplitter chainSplitter;
    private final AgentSessionRepository sessionRepository;
    private final AgentEventRepository eventRepository;
    private final ObjectMapper objectMapper;

    /**
     * 执行 Phase2 V2 分析。
     * 
     * @param parentSessionId Phase1 session ID
     * @param question 用户问题
     * @param projectPath 项目路径
     * @return 分层报告
     */
    public Phase2V2Report orchestrate(
            String parentSessionId,
            String question,
            String projectPath) {
        
        log.info("[Phase2V2] Starting orchestration for parentSession={} question={}", 
                parentSessionId, question);

        // Step 1: 继承 Phase1 数据
        Phase1InheritedData inheritedData = loadPhase1Checkpoint(parentSessionId);
        
        // Step 2: KG entryPoints 匹配
        List<Entry> entryPoints = inheritedData != null && inheritedData.entryPoints() != null
                ? inheritedData.entryPoints()
                : fetchEntryPoints(projectPath);
        
        // Step 3: 拆分链路
        List<ChainContext> chainContexts = chainSplitter.split(
                entryPoints, question, projectPath, parentSessionId);
        
        // 设置 inheritedData
        for (ChainContext ctx : chainContexts) {
            // TODO: 重建 ChainContext with inheritedData (record 不可变)
        }
        
        log.info("[Phase2V2] Split into {} chains", chainContexts.size());
        
        // Step 4: 并行执行 Chain Agents (后续实现)
        // List<ChainReport> reports = executeChains(chainContexts);
        
        // Step 5: 合并报告 (后续实现)
        // return mergeReports(reports, question);
        
        // 骨架返回空报告
        return new Phase2V2Report(
                new SummaryLayer("", "", List.of(), List.of(), List.of()),
                new DetailLayer(List.of(), 0, 0, 0),
                "RUNNING",
                question
        );
    }

    /**
     * 从 Phase1 session 加载 checkpoint 数据。
     */
    private Phase1InheritedData loadPhase1Checkpoint(String sessionId) {
        // TODO: 从 AgentEventRepository 查询 project_overview CHECKPOINT
        return null;
    }

    /**
     * 直接获取 KG entryPoints (兜底)。
     */
    private List<Entry> fetchEntryPoints(String projectPath) {
        try {
            return kgClient.entryPoints(projectPath, "ALL");
        } catch (Exception e) {
            log.warn("[Phase2V2] Failed to fetch entryPoints: {}", e.getMessage());
            return List.of();
        }
    }
}
```

**Step 3: Verify compilation**

Run: `cd hisi-dev-tool && mvn compile -DskipTests -q`
Expected: No errors

**Step 4: Commit (骨架)**

```bash
git add hisi-dev-tool/src/main/java/com/huawei/hisi/ram/phase2v2/Phase2V2Orchestrator.java
git add hisi-dev-tool/src/test/java/com/huawei/hisi/ram/phase2v2/Phase2V2OrchestratorTest.java
git commit -m "feat(phase2v2): add Phase2V2Orchestrator skeleton

- Load Phase1 checkpoint data
- Split chains via ChainSplitter
- Placeholder for parallel execution and merge

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 2.2: 实现 Phase1 checkpoint 加载

**Files:**
- Modify: `hisi-dev-tool/src/main/java/com/huawei/hisi/ram/phase2v2/Phase2V2Orchestrator.java`

**Step 1: Add checkpoint loading implementation**

在 `Phase2V2Orchestrator.java` 中替换 `loadPhase1Checkpoint` 方法:

```java
/**
 * 从 Phase1 session 加载 checkpoint 数据。
 */
@SuppressWarnings("unchecked")
private Phase1InheritedData loadPhase1Checkpoint(String sessionId) {
    try {
        // 解析 backend ID
        Long backendId = sessionRepository.findByUuid(sessionId)
                .map(s -> s.getId())
                .orElse(null);
        
        if (backendId == null) {
            log.warn("[Phase2V2] Parent session not found: {}", sessionId);
            return null;
        }
        
        // 查找 project_overview CHECKPOINT
        List<AgentEvent> events = eventRepository.findBySessionId(backendId);
        for (int i = events.size() - 1; i >= 0; i--) {
            AgentEvent ev = events.get(i);
            if (ev.getType() != EventType.CHECKPOINT) continue;
            
            Map<String, Object> payload = parsePayload(ev.getPayload());
            if (!"project_overview".equals(payload.get("nodeName"))) continue;
            
            Map<String, Object> output = (Map<String, Object>) payload.get("output");
            if (output == null) continue;
            
            // 提取 entryPoints
            List<Entry> entryPoints = extractEntryPoints(output.get("entry_points_summary"));
            
            // 提取 bridgeStats (如果存在)
            long totalBridges = 0;
            long feignCount = 0;
            long mqCount = 0;
            
            // 提取 coreMethods nodeIds
            List<String> coreMethodNodeIds = extractCoreMethodNodeIds(output.get("core_call_chains"));
            
            return new Phase1InheritedData(
                    entryPoints,
                    totalBridges,
                    feignCount,
                    mqCount,
                    coreMethodNodeIds
            );
        }
        
        return null;
    } catch (Exception e) {
        log.warn("[Phase2V2] Failed to load Phase1 checkpoint: {}", e.getMessage());
        return null;
    }
}

private Map<String, Object> parsePayload(String json) {
    if (json == null || json.isBlank()) return Map.of();
    try {
        return objectMapper.readValue(json, Map.class);
    } catch (Exception e) {
        return Map.of();
    }
}

@SuppressWarnings("unchecked")
private List<Entry> extractEntryPoints(Object obj) {
    if (obj instanceof List<?> list) {
        return list.stream()
                .filter(item -> item instanceof Map<?, ?>)
                .map(item -> {
                    Map<String, Object> m = (Map<String, Object>) item;
                    return new Entry(
                            (String) m.getOrDefault("nodeId", ""),
                            (String) m.getOrDefault("className", ""),
                            (String) m.getOrDefault("methodName", ""),
                            (String) m.getOrDefault("type", ""),
                            null, null, null
                    );
                })
                .toList();
    }
    return List.of();
}

@SuppressWarnings("unchecked")
private List<String> extractCoreMethodNodeIds(Object obj) {
    if (obj instanceof List<?> list) {
        return list.stream()
                .filter(item -> item instanceof Map<?, ?>)
                .map(item -> (String) ((Map<?, ?>) item).getOrDefault("nodeId", ""))
                .filter(id -> id != null && !id.isBlank())
                .toList();
    }
    return List.of();
}
```

需要添加 import:

```java
import com.huawei.hisi.ram.model.AgentEvent;
import com.huawei.hisi.ram.model.EventType;
import java.util.Map;
```

**Step 2: Verify compilation**

Run: `cd hisi-dev-tool && mvn compile -DskipTests -q`
Expected: No errors

**Step 3: Commit**

```bash
git add hisi-dev-tool/src/main/java/com/huawei/hisi/ram/phase2v2/Phase2V2Orchestrator.java
git commit -m "feat(phase2v2): implement Phase1 checkpoint loading

- Query AgentEventRepository for project_overview checkpoint
- Extract entryPoints and coreMethodNodeIds
- Handle missing/invalid data gracefully

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Phase 3: Chain Analysis Agent (3 天)

### Task 3.1: 创建 ChainAnalysisAgent 接口

**Files:**
- Create: `hisi-dev-tool/src/main/java/com/huawei/hisi/ram/phase2v2/ChainAnalysisAgent.java`
- Create: `hisi-dev-tool/src/main/java/com/huawei/hisi/ram/phase2v2/impl/ClaudeChainAnalysisAgent.java`

**Step 1: Write ChainAnalysisAgent interface**

```java
// hisi-dev-tool/src/main/java/com/huawei/hisi/ram/phase2v2/ChainAnalysisAgent.java
package com.huawei.hisi.ram.phase2v2;

import com.huawei.hisi.ram.phase2v2.model.ChainContext;
import com.huawei.hisi.ram.phase2v2.model.ChainReport;

/**
 * 链路分析 Agent 接口。
 * 
 * <p>端到端分析一条完整链路，产出包含:
 * <ul>
 *     <li>KG 数据收集 (upstream/downstream/methodBodies/bridges)</li>
 *     <li>Markdown 分析报告</li>
 *     <li>SVG 图表 (调用链流程图、时序图、状态流转图)</li>
 *     <li>代码片段 + 建议 + 置信度</li>
 * </ul>
 */
public interface ChainAnalysisAgent {
    
    /**
     * 执行链路分析。
     * 
     * @param context 链路上下文
     * @return 链路分析报告
     */
    ChainReport analyze(ChainContext context);
    
    /**
     * Agent 类型标识。
     */
    String agentType();
}
```

**Step 2: Write ClaudeChainAnalysisAgent skeleton**

```java
// hisi-dev-tool/src/main/java/com/huawei/hisi/ram/phase2v2/impl/ClaudeChainAnalysisAgent.java
package com.huawei.hisi.ram.phase2v2.impl;

import com.huawei.hisi.ram.kg.KgMcpClient;
import com.huawei.hisi.ram.phase2v2.ChainAnalysisAgent;
import com.huawei.hisi.ram.phase2v2.model.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Claude SDK 驱动的链路分析 Agent。
 * 
 * <p>使用 Claude SDK + KG MCP 工具进行端到端链路分析。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClaudeChainAnalysisAgent implements ChainAnalysisAgent {

    private final KgMcpClient kgClient;

    @Override
    public String agentType() {
        return "claude-chain-analysis-v1";
    }

    @Override
    public ChainReport analyze(ChainContext context) {
        log.info("[ChainAgent] Starting analysis for chainId={} chainName={}", 
                context.chainId(), context.chainName());

        try {
            // Step 1: KG 数据收集
            KgRawData kgData = collectKgData(context);
            
            // Step 2: 调用 Claude SDK 分析 (后续实现)
            // AnalysisResult analysis = callClaudeSdk(context, kgData);
            
            // 骨架返回空结果
            AnalysisResult analysis = new AnalysisResult(
                    "待分析",
                    "",
                    "",
                    "",
                    List.of(),
                    List.of(),
                    new Confidence("insufficient", 
                            new KgCoverage(false, false, 0, List.of("骨架实现")),
                            List.of())
            );
            
            return new ChainReport(
                    context.chainId(),
                    context.chainName(),
                    new ChainReport.EntryPointInfo(
                            context.entryPoint().type(),
                            context.entryPoint().className(),
                            context.entryPoint().methodName(),
                            context.entryPoint().nodeId()
                    ),
                    analysis,
                    kgData,
                    "DONE",
                    null
            );
        } catch (Exception e) {
            log.error("[ChainAgent] Analysis failed for chainId={}: {}", 
                    context.chainId(), e.getMessage(), e);
            
            return new ChainReport(
                    context.chainId(),
                    context.chainName(),
                    null,
                    null,
                    null,
                    "FAILED",
                    e.getMessage()
            );
        }
    }

    /**
     * 收集 KG 数据 (upstream/downstream/methodBodies/bridges)。
     */
    private KgRawData collectKgData(ChainContext context) {
        // TODO: 实现 KG 数据收集
        return new KgRawData(List.of(), List.of(), List.of(), List.of());
    }
}
```

**Step 3: Verify compilation**

Run: `cd hisi-dev-tool && mvn compile -DskipTests -q`
Expected: No errors

**Step 4: Commit**

```bash
git add hisi-dev-tool/src/main/java/com/huawei/hisi/ram/phase2v2/ChainAnalysisAgent.java
git add hisi-dev-tool/src/main/java/com/huawei/hisi/ram/phase2v2/impl/ClaudeChainAnalysisAgent.java
git commit -m "feat(phase2v2): add ChainAnalysisAgent interface and Claude implementation skeleton

- ChainAnalysisAgent interface for end-to-end chain analysis
- ClaudeChainAnalysisAgent using KgMcpClient
- Placeholder for Claude SDK integration

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 3.2: 实现 KG 数据收集

**Files:**
- Modify: `hisi-dev-tool/src/main/java/com/huawei/hisi/ram/phase2v2/impl/ClaudeChainAnalysisAgent.java`

**Step 1: Add KG data collection implementation**

替换 `collectKgData` 方法:

```java
/**
 * 收集 KG 数据 (upstream/downstream/methodBodies/bridges)。
 */
private KgRawData collectKgData(ChainContext context) {
    Entry entry = context.entryPoint();
    String projectPath = context.projectPath();
    
    // 解析 className 和 methodName
    String className = entry.className();
    String methodName = entry.methodName();
    
    if (className == null || methodName == null) {
        log.warn("[ChainAgent] Missing className/methodName for chain {}", context.chainId());
        return new KgRawData(List.of(), List.of(), List.of(), List.of());
    }
    
    // 1. Upstream chains (affecting)
    List<Map<String, Object>> upstreamChains = new ArrayList<>();
    try {
        List<Entry> affecting = kgClient.affecting(className, methodName, projectPath, 5);
        for (Entry e : affecting) {
            upstreamChains.add(entryToMap(e));
        }
        log.debug("[ChainAgent] Found {} upstream entries", upstreamChains.size());
    } catch (Exception e) {
        log.debug("[ChainAgent] affecting failed: {}", e.getMessage());
    }
    
    // 2. Downstream chains (calleesTree)
    List<Map<String, Object>> downstreamChains = new ArrayList<>();
    try {
        CallTreeNode tree = kgClient.calleesTree(className, methodName, projectPath, 5);
        if (tree != null) {
            downstreamChains.add(callTreeNodeToMap(tree));
        }
        log.debug("[ChainAgent] Found downstream tree with depth {}", 
                tree != null ? tree.depth() : 0);
    } catch (Exception e) {
        log.debug("[ChainAgent] calleesTree failed: {}", e.getMessage());
    }
    
    // 3. Root entries (溯源入口点)
    try {
        List<Entry> roots = kgClient.rootEntries(className, methodName, projectPath);
        for (Entry e : roots) {
            upstreamChains.add(entryToMap(e));
        }
    } catch (Exception e) {
        log.debug("[ChainAgent] rootEntries failed: {}", e.getMessage());
    }
    
    // 4. Method bodies (加载核心代码)
    List<Map<String, Object>> methodBodies = new ArrayList<>();
    List<String> nodeIds = collectNodeIds(upstreamChains, downstreamChains);
    if (!nodeIds.isEmpty()) {
        try {
            List<MethodBodyInfo> bodies = kgClient.loadMethodBodies(
                    nodeIds.stream().limit(20).toList(), projectPath);
            for (MethodBodyInfo body : bodies) {
                methodBodies.add(methodBodyToMap(body));
            }
            log.debug("[ChainAgent] Loaded {} method bodies", methodBodies.size());
        } catch (Exception e) {
            log.debug("[ChainAgent] loadMethodBodies failed: {}", e.getMessage());
        }
    }
    
    // 5. Bridge points (Feign/MQ/Mapper)
    List<Map<String, Object>> bridgePoints = new ArrayList<>();
    try {
        List<Bridge> bridges = kgClient.bridges(entry.nodeId(), projectPath);
        for (Bridge b : bridges) {
            bridgePoints.add(bridgeToMap(b));
        }
        log.debug("[ChainAgent] Found {} bridge points", bridgePoints.size());
    } catch (Exception e) {
        log.debug("[ChainAgent] bridges failed: {}", e.getMessage());
    }
    
    return new KgRawData(upstreamChains, downstreamChains, methodBodies, bridgePoints);
}

private Map<String, Object> entryToMap(Entry e) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("nodeId", e.nodeId());
    m.put("className", e.className());
    m.put("methodName", e.methodName());
    m.put("type", e.type());
    return m;
}

private Map<String, Object> callTreeNodeToMap(CallTreeNode node) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("nodeId", node.nodeId());
    m.put("className", node.className());
    m.put("methodName", node.methodName());
    m.put("depth", node.depth());
    if (node.children() != null && !node.children().isEmpty()) {
        m.put("children", node.children().stream()
                .map(this::callTreeNodeToMap)
                .toList());
    }
    return m;
}

private Map<String, Object> methodBodyToMap(MethodBodyInfo body) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("nodeId", body.nodeId());
    m.put("className", body.className());
    m.put("methodName", body.methodName());
    m.put("filePath", body.filePath());
    m.put("methodBody", body.methodBody());
    m.put("description", body.description());
    return m;
}

private Map<String, Object> bridgeToMap(Bridge b) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("nodeId", b.nodeId());
    m.put("bridgeType", b.bridgeType());
    m.put("target", b.target());
    return m;
}

private List<String> collectNodeIds(
        List<Map<String, Object>> upstream,
        List<Map<String, Object>> downstream) {
    List<String> nodeIds = new ArrayList<>();
    
    for (Map<String, Object> m : upstream) {
        String nodeId = (String) m.get("nodeId");
        if (nodeId != null && !nodeId.isBlank()) {
            nodeIds.add(nodeId);
        }
    }
    
    collectNodeIdsFromTree(downstream, nodeIds);
    
    return nodeIds;
}

private void collectNodeIdsFromTree(List<Map<String, Object>> tree, List<String> nodeIds) {
    for (Map<String, Object> node : tree) {
        String nodeId = (String) node.get("nodeId");
        if (nodeId != null && !nodeId.isBlank()) {
            nodeIds.add(nodeId);
        }
        Object children = node.get("children");
        if (children instanceof List<?> list) {
            collectNodeIdsFromTree((List<Map<String, Object>>) list, nodeIds);
        }
    }
}
```

添加必要的 imports:

```java
import com.huawei.hisi.ram.kg.dto.Bridge;
import com.huawei.hisi.ram.kg.dto.CallTreeNode;
import com.huawei.hisi.ram.kg.dto.MethodBodyInfo;
import java.util.ArrayList;
import java.util.LinkedHashMap;
```

**Step 2: Verify compilation**

Run: `cd hisi-dev-tool && mvn compile -DskipTests -q`
Expected: No errors

**Step 3: Commit**

```bash
git add hisi-dev-tool/src/main/java/com/huawei/hisi/ram/phase2v2/impl/ClaudeChainAnalysisAgent.java
git commit -m "feat(phase2v2): implement KG data collection in ChainAnalysisAgent

- upstream (affecting) + root entries
- downstream (calleesTree)
- method bodies (loadMethodBodies)
- bridge points (bridges)

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Phase 4: V2 Controller + API (1 天)

### Task 4.1: 创建 RamPhase2V2Controller

**Files:**
- Create: `hisi-dev-tool/src/main/java/com/huawei/hisi/ram/controller/RamPhase2V2Controller.java`

**Step 1: Write controller**

```java
// hisi-dev-tool/src/main/java/com/huawei/hisi/ram/controller/RamPhase2V2Controller.java
package com.huawei.hisi.ram.controller;

import com.huawei.hisi.model.ApiResponse;
import com.huawei.hisi.ram.phase2v2.Phase2V2Orchestrator;
import com.huawei.hisi.ram.phase2v2.model.Phase2V2Report;
import com.huawei.hisi.ram.repository.AgentSessionRepository;
import com.huawei.hisi.ram.service.SessionMappingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * REST controller for Phase2 V2 multi-agent orchestration analysis.
 * 
 * <p>Endpoints (all under {@code /api/ram/status/phase2/v2}):
 * <ul>
 *   <li>{@code POST /start} – starts a V2 analysis session.</li>
 *   <li>{@code GET /{sid}/status} – returns execution status and progress.</li>
 *   <li>{@code GET /{sid}/report} – returns the layered report.</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/ram/status/phase2/v2")
@RequiredArgsConstructor
public class RamPhase2V2Controller {

    private final Phase2V2Orchestrator orchestrator;
    private final SessionMappingService sessionMappingService;
    private final AgentSessionRepository sessionRepository;
    private final ExecutorService asyncExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "phase2-v2-async");
        t.setDaemon(true);
        return t;
    });

    public record Phase2V2StartRequest(
        String sessionId,   // Phase1 session ID
        String question     // User's follow-up question
    ) {}
    
    public record Phase2V2StartResponse(
        String sessionId,
        String status,
        int estimatedChains
    ) {}
    
    public record Phase2V2StatusResponse(
        String status,
        Progress progress
    ) {}
    
    public record Progress(
        int chainsTotal,
        int chainsCompleted,
        String currentChain,
        int estimatedTimeRemaining
    ) {}

    /**
     * Start a Phase2 V2 analysis session.
     * POST /api/ram/status/phase2/v2/start
     */
    @PostMapping("/start")
    public ApiResponse<Phase2V2StartResponse> startV2Analysis(
            @RequestBody Phase2V2StartRequest request) {
        
        log.info("[Phase2V2] POST /start request={}", request);
        
        if (request == null || request.sessionId() == null || request.sessionId().isBlank()) {
            return ApiResponse.error(400, "sessionId (Phase1) is required");
        }
        if (request.question() == null || request.question().isBlank()) {
            return ApiResponse.error(400, "question is required");
        }
        
        // Resolve parent session to get projectPath
        Long backendId = sessionMappingService.resolveBackendId(request.sessionId());
        if (backendId == null) {
            return ApiResponse.error(404, "parent session not found: " + request.sessionId());
        }
        
        var parentSession = sessionRepository.findById(backendId);
        if (parentSession.isEmpty()) {
            return ApiResponse.error(404, "parent session row missing");
        }
        
        // Extract projectPath from parent
        String projectPath = extractProjectPath(parentSession.get());
        if (projectPath == null || projectPath.isBlank()) {
            return ApiResponse.error(400, "parent session has no projectPath");
        }
        
        // Generate new session UUID
        String v2SessionId = java.util.UUID.randomUUID().toString();
        
        // TODO: Create AgentSession record for V2
        
        // Async execution with 5-minute timeout
        CompletableFuture.runAsync(() -> {
            try {
                log.info("[Phase2V2] Starting orchestration for v2SessionId={}", v2SessionId);
                Phase2V2Report report = orchestrator.orchestrate(
                        request.sessionId(), request.question(), projectPath);
                
                // TODO: Store report in AgentEvent checkpoint
                
                log.info("[Phase2V2] Completed for v2SessionId={}, status={}", 
                        v2SessionId, report.status());
            } catch (Exception e) {
                log.error("[Phase2V2] Failed for v2SessionId={}: {}", 
                        v2SessionId, e.getMessage(), e);
            }
        }, asyncExecutor)
        .orTimeout(5, TimeUnit.MINUTES);
        
        // 预估链路数 (后续实现真实估算)
        int estimated = 3;
        
        return ApiResponse.success(new Phase2V2StartResponse(
                v2SessionId, "RUNNING", estimated));
    }

    /**
     * Get execution status.
     * GET /api/ram/status/phase2/v2/{sid}/status
     */
    @GetMapping("/{sid}/status")
    public ApiResponse<Phase2V2StatusResponse> getStatus(@PathVariable("sid") String sessionId) {
        // TODO: 实现状态查询
        return ApiResponse.success(new Phase2V2StatusResponse(
                "RUNNING",
                new Progress(3, 1, "chain-xxx", 60)
        ));
    }

    /**
     * Get layered report.
     * GET /api/ram/status/phase2/v2/{sid}/report
     */
    @GetMapping("/{sid}/report")
    public ApiResponse<Phase2V2Report> getReport(@PathVariable("sid") String sessionId) {
        // TODO: 实现报告查询
        return ApiResponse.error(404, "Report not ready yet");
    }

    private String extractProjectPath(com.huawei.hisi.ram.model.AgentSession session) {
        // 解析 projectPaths JSON
        String json = session.getProjectPaths();
        if (json == null || json.isBlank()) return null;
        try {
            java.util.List<?> paths = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(json, java.util.List.class);
            return paths.isEmpty() ? null : String.valueOf(paths.get(0));
        } catch (Exception e) {
            return null;
        }
    }
}
```

**Step 2: Verify compilation**

Run: `cd hisi-dev-tool && mvn compile -DskipTests -q`
Expected: No errors

**Step 3: Commit**

```bash
git add hisi-dev-tool/src/main/java/com/huawei/hisi/ram/controller/RamPhase2V2Controller.java
git commit -m "feat(phase2v2): add RamPhase2V2Controller with V2 API endpoints

- POST /start: async orchestration with 5-min timeout
- GET /status: execution progress
- GET /report: layered report retrieval

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Phase 5: 前端适配 (待补充)

> 注: 前端实现计划将在后端核心完成后补充，包括:
> - Phase2V2ReportView.vue 组件
> - 分层报告展示 (概览 + 可展开详细)
> - SVG 图表渲染

---

## Phase 6: 稳定后移除 V1 (待补充)

> 注: V1 移除计划将在 V2 稳定运行后补充

---

## 验收标准

| 阶段 | 验收标准 |
|------|---------|
| Phase 1 | 所有数据模型编译通过，单元测试通过 |
| Phase 2 | Orchestrator 能加载 Phase1 checkpoint，拆分链路 |
| Phase 3 | Chain Agent 能收集 KG 数据，返回 ChainReport |
| Phase 4 | API 端点可用，能异步执行并返回报告 |
| Phase 5 | 前端能展示分层报告和 SVG 图表 |
| Phase 6 | V1 API 移除，V2 成为唯一入口 |

---

> **下一步**: 选择执行方式