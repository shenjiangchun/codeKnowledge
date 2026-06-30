// hisi-dev-tool/src/test/java/com/huawei/hisi/ram/phase2v2/ChainSplitterTest.java
package com.huawei.hisi.ram.phase2v2;

import com.huawei.hisi.ram.kg.dto.Entry;
import com.huawei.hisi.ram.phase2v2.model.ChainComplexity;
import com.huawei.hisi.ram.phase2v2.model.ChainContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChainSplitterTest {

    private ChainSplitter chainSplitter;

    @BeforeEach
    void setUp() {
        DynamicToolRegistry toolRegistry = new DynamicToolRegistry();
        chainSplitter = new ChainSplitter(toolRegistry);
    }

    @Test
    @DisplayName("split returns one context per entry when no keyword filtering")
    void split_noKeywordFiltering_returnsOneContextPerEntry() {
        List<Entry> entries = List.of(
            new Entry("node1", "OrderController", "createOrder", "Controller"),
            new Entry("node2", "PaymentController", "pay", "Controller")
        );

        List<ChainContext> contexts = chainSplitter.split(
            entries,
            "系统架构是怎样的？",
            "/path/to/project",
            "parent-session-123"
        );

        assertThat(contexts).hasSize(2);
        assertThat(contexts.get(0).chainName()).contains("OrderController");
        assertThat(contexts.get(1).chainName()).contains("PaymentController");
    }

    @Test
    @DisplayName("split filters entries by question keywords")
    void split_filterByKeywords_retainsOnlyRelevantEntries() {
        List<Entry> entries = List.of(
            new Entry("node1", "OrderController", "createOrder", "Controller"),
            new Entry("node2", "UserController", "login", "Controller"),
            new Entry("node3", "OrderMQConsumer", "handleMessage", "MQ_LISTENER")
        );

        // Note: Current implementation uses simple string matching.
        // Chinese-to-English semantic mapping (e.g., "订单" -> "Order") is a future enhancement.
        List<ChainContext> contexts = chainSplitter.split(
            entries,
            "Order",
            "/path/to/project",
            "parent-session-123"
        );

        // Only keep entries containing "Order"
        assertThat(contexts).hasSize(2);
        assertThat(contexts.stream().map(ChainContext::chainName).toList())
            .allMatch(name -> name.contains("Order"));
    }

    @Test
    @DisplayName("split generates correct chain name for different entry types")
    void split_generatesCorrectChainName_forDifferentTypes() {
        Entry controller = new Entry("n1", "OrderController", "create", "Controller");
        Entry mqListener = new Entry("n2", "PaymentConsumer", "handle", "MQ_LISTENER");
        Entry feignClient = new Entry("n3", "UserClient", "getUser", "FEIGN_CLIENT");
        Entry scheduled = new Entry("n4", "ReportScheduler", "generate", "SCHEDULED");
        Entry unknown = new Entry("n5", "UnknownType", "doSomething", "UNKNOWN");

        List<ChainContext> contexts = chainSplitter.split(
            List.of(controller, mqListener, feignClient, scheduled, unknown),
            "",
            "/path",
            "session"
        );

        assertThat(contexts.get(0).chainName()).isEqualTo("OrderController 控制器链路");
        assertThat(contexts.get(1).chainName()).isEqualTo("PaymentConsumer MQ消费链路");
        assertThat(contexts.get(2).chainName()).isEqualTo("UserClient Feign调用链路");
        assertThat(contexts.get(3).chainName()).isEqualTo("ReportScheduler 定时任务链路");
        assertThat(contexts.get(4).chainName()).isEqualTo("UnknownType#doSomething 链路");
    }

    @Test
    @DisplayName("split infers CROSS_SERVICE complexity for MQ and Feign entries")
    void split_infersCrossServiceComplexity_forMqAndFeign() {
        Entry mqEntry = new Entry("n1", "OrderConsumer", "handle", "MQ_LISTENER");
        Entry feignEntry = new Entry("n2", "PaymentClient", "pay", "FEIGN_CLIENT");
        Entry controllerEntry = new Entry("n3", "UserController", "list", "Controller");

        List<ChainContext> contexts = chainSplitter.split(
            List.of(mqEntry, feignEntry, controllerEntry),
            "分析一下",
            "/path",
            "session"
        );

        assertThat(contexts.get(0).complexity()).isEqualTo(ChainComplexity.CROSS_SERVICE);
        assertThat(contexts.get(1).complexity()).isEqualTo(ChainComplexity.CROSS_SERVICE);
        assertThat(contexts.get(2).complexity()).isEqualTo(ChainComplexity.SIMPLE);
    }

    @Test
    @DisplayName("split infers VERIFICATION complexity when question contains verification keywords")
    void split_infersVerificationComplexity_forVerificationKeywords() {
        Entry entry = new Entry("n1", "OrderController", "create", "Controller");

        List<ChainContext> contexts = chainSplitter.split(
            List.of(entry),
            "请验证订单创建逻辑",
            "/path",
            "session"
        );

        assertThat(contexts.get(0).complexity()).isEqualTo(ChainComplexity.VERIFICATION);
    }

    @Test
    @DisplayName("split assigns correct tools based on complexity")
    void split_assignsCorrectTools_basedOnComplexity() {
        Entry simpleEntry = new Entry("n1", "UserController", "list", "Controller");
        Entry crossServiceEntry = new Entry("n2", "OrderConsumer", "handle", "MQ_LISTENER");

        List<ChainContext> contexts = chainSplitter.split(
            List.of(simpleEntry, crossServiceEntry),
            "分析",
            "/path",
            "session"
        );

        // SIMPLE complexity: base tools only
        assertThat(contexts.get(0).allowedTools()).contains("KG_MCP", "Read", "Grep");
        assertThat(contexts.get(0).allowedTools()).doesNotContain("WebFetch", "Bash", "Agent");

        // CROSS_SERVICE complexity: includes WebFetch
        assertThat(contexts.get(1).allowedTools()).contains("KG_MCP", "Read", "Grep", "WebFetch");
        assertThat(contexts.get(1).allowedTools()).doesNotContain("Bash", "Agent");
    }

    @Test
    @DisplayName("split returns all entries when no keywords match (fallback behavior)")
    void split_noKeywordsMatch_returnsAllEntriesAsFallback() {
        // When keywords exist but don't match any entry, return all entries as fallback
        List<Entry> entries = List.of(
            new Entry("node1", "UserController", "login", "Controller"),
            new Entry("node2", "AuthController", "authenticate", "Controller")
        );

        List<ChainContext> contexts = chainSplitter.split(
            entries,
            "订单",  // Chinese keyword, no match in English class names
            "/path/to/project",
            "parent-session-123"
        );

        // Fallback: return all entries when keywords don't match
        assertThat(contexts).hasSize(2);
    }

    @Test
    @DisplayName("split handles empty entries list")
    void split_emptyEntries_returnsEmptyList() {
        List<ChainContext> contexts = chainSplitter.split(
            List.of(),
            "订单",
            "/path/to/project",
            "parent-session-123"
        );

        assertThat(contexts).isEmpty();
    }

    @Test
    @DisplayName("split generates unique chain IDs")
    void split_generatesUniqueChainIds() {
        List<Entry> entries = List.of(
            new Entry("node1", "OrderController", "create", "Controller"),
            new Entry("node2", "OrderController", "cancel", "Controller")
        );

        List<ChainContext> contexts = chainSplitter.split(
            entries,
            "",
            "/path",
            "session"
        );

        assertThat(contexts).hasSize(2);
        assertThat(contexts.get(0).chainId()).isNotEqualTo(contexts.get(1).chainId());
    }

    @Test
    @DisplayName("split correctly sets all ChainContext fields")
    void split_setsAllChainContextFields() {
        Entry entry = new Entry("node1", "OrderController", "createOrder", "Controller");

        // Use English keyword to ensure match
        List<ChainContext> contexts = chainSplitter.split(
            List.of(entry),
            "Order create flow",
            "/projects/myapp",
            "session-abc"
        );

        assertThat(contexts).hasSize(1);
        ChainContext ctx = contexts.get(0);

        assertThat(ctx.chainId()).isNotBlank();
        assertThat(ctx.chainName()).isEqualTo("OrderController 控制器链路");
        assertThat(ctx.entryPoint()).isEqualTo(entry);
        assertThat(ctx.question()).isEqualTo("Order create flow");
        assertThat(ctx.projectPath()).isEqualTo("/projects/myapp");
        assertThat(ctx.parentSessionId()).isEqualTo("session-abc");
        assertThat(ctx.complexity()).isNotNull();
        assertThat(ctx.allowedTools()).isNotEmpty();
        assertThat(ctx.inheritedData()).isNull();
    }

    @Test
    @DisplayName("split handles entries with null fields")
    void split_handlesNullFields_gracefully() {
        Entry entryWithNulls = new Entry(null, "SomeClass", null, null);

        List<ChainContext> contexts = chainSplitter.split(
            List.of(entryWithNulls),
            "",
            "/path",
            "session"
        );

        assertThat(contexts).hasSize(1);
        assertThat(contexts.get(0).chainName()).contains("SomeClass");
    }

    @Test
    @DisplayName("extract keywords filters short words")
    void extractKeywords_filtersShortWords() {
        // Single-character words like "的" are filtered out
        // Multi-character words like "Order" are kept
        Entry entry = new Entry("n1", "OrderController", "create", "Controller");

        List<ChainContext> contexts = chainSplitter.split(
            List.of(entry),
            "x Order",  // "x" is single char (filtered), "Order" matches
            "/path",
            "session"
        );

        // "Order" matches OrderController
        assertThat(contexts).hasSize(1);
    }
}