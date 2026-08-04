package com.huawei.hisi.agent.tools;

import com.huawei.hisi.ram.kg.KgMcpClient;
import com.huawei.hisi.ram.kg.dto.CallTreeNode;
import com.huawei.hisi.ram.kg.dto.Entry;
import com.huawei.hisi.ram.kg.dto.MethodBodyInfo;
import com.huawei.hisi.ram.kg.dto.Seed;
import com.huawei.hisi.ram.sdk.ToolDefinition;
import com.huawei.hisi.repository.LogAnalysisRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AgentTools")
class AgentToolsTest {

    @Mock private KgMcpClient kgClient;
    @Mock private LogAnalysisRepository logRepository;

    private AgentTools tools;

    @BeforeEach
    void setUp() {
        tools = new AgentTools(kgClient, logRepository);
    }

    // ── hybridSearch ──

    @Test
    @DisplayName("hybridSearch delegates to kgClient and returns seed list")
    void hybridSearch_delegatesToKgClient() {
        // Seed(String nodeId, double score, String summary)
        when(kgClient.hybridSearch(anyString(), anyString(), anyInt()))
                .thenReturn(List.of(
                        new Seed("n1", 0.95, "creates orders"),
                        new Seed("n2", 0.82, "updates stock")));

        var result = tools.hybridSearch("/proj", "order creation", 10);

        assertThat(result).hasSize(2);
        assertThat(result.get(0)).containsEntry("nodeId", "n1")
                .containsEntry("summary", "creates orders");
    }

    @Test
    @DisplayName("hybridSearch returns error when kgClient is null")
    void hybridSearch_noKgClient_returnsError() {
        var noKg = new AgentTools(null, logRepository);
        var result = noKg.hybridSearch("/proj", "query", 5);
        assertThat(result).hasSize(1);
        assertThat(result.get(0)).containsKey("error");
    }

    // ── loadMethodBodies ──

    @Test
    @DisplayName("loadMethodBodies delegates to kgClient")
    void loadMethodBodies_delegatesToKgClient() {
        // MethodBodyInfo(nodeId, className, methodName, description, methodBody, filePath)
        when(kgClient.loadMethodBodies(anyList(), anyString()))
                .thenReturn(List.of(new MethodBodyInfo(
                        "n1", "MyService", "process", "Business logic",
                        "public void process() {}", "/src/MyService.java")));

        var result = tools.loadMethodBodies("/proj", List.of("n1"));

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).containsEntry("className", "MyService")
                .containsEntry("methodName", "process");
    }

    // ── calleesTree ──

    @Test
    @DisplayName("calleesTree delegates to kgClient")
    void calleesTree_delegatesToKgClient() {
        var node = new CallTreeNode("n1", "com.foo.Service", "process", 0, null);
        when(kgClient.calleesTree(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(node);

        var result = tools.calleesTree("/proj", "com.foo.Service", "process", 3);

        assertThat(result).isEqualTo(node);
    }

    // ── rootEntries ──

    @Test
    @DisplayName("rootEntries delegates to kgClient")
    void rootEntries_delegatesToKgClient() {
        // Entry(String nodeId, String className, String methodName, String type)
        var entries = List.of(new Entry("e1", "com.foo.Controller", "handle", "CONTROLLER"));
        when(kgClient.rootEntries(anyString(), anyString(), anyString()))
                .thenReturn(entries);

        var result = tools.rootEntries("/proj", "com.foo.Service", "process");

        assertThat(result).isEqualTo(entries);
    }

    // ── entryPoints ──

    @Test
    @DisplayName("entryPoints delegates to kgClient with entry type")
    void entryPoints_delegatesToKgClient() {
        var entries = List.of(new Entry("e1", "com.foo.Controller", "handle", "CONTROLLER"));
        when(kgClient.entryPoints(anyString(), anyString())).thenReturn(entries);

        var result = tools.entryPoints("/proj", "ALL");

        assertThat(result).isEqualTo(entries);
    }

    // ── grepProject ──

    @Test
    @DisplayName("grepProject finds matches in real files")
    void grepProject_findsMatches(@TempDir Path tmpDir) throws Exception {
        Files.writeString(tmpDir.resolve("hello.java"), "public class Hello {\n    // TODO fix this\n}");

        var result = tools.grepProject(tmpDir.toString(), "TODO", null, false);

        assertThat(result).containsEntry("total", 1);
        @SuppressWarnings("unchecked")
        var matches = (List<Map<String, Object>>) result.get("matches");
        assertThat(matches).hasSize(1);
    }

    @Test
    @DisplayName("grepProject returns error for missing dir")
    void grepProject_missingDir_returnsError() {
        var result = tools.grepProject("/nonexistent/path", "TODO", null, false);
        assertThat(result).containsKey("error");
    }

    // ── readFile ──

    @Test
    @DisplayName("readFile reads file content")
    void readFile_readsContent(@TempDir Path tmpDir) throws Exception {
        Files.writeString(tmpDir.resolve("readme.md"), "# Project\nHello world!");

        var result = tools.readFile(tmpDir.toString(), "readme.md");

        assertThat(result).containsEntry("path", "readme.md");
        assertThat((String) result.get("content")).contains("# Project");
    }

    @Test
    @DisplayName("readFile blocks path traversal")
    void readFile_blocksPathTraversal(@TempDir Path tmpDir) {
        var result = tools.readFile(tmpDir.toString(), "../../etc/passwd");
        assertThat(result).containsKey("error");
    }

    // ── listFiles ──

    @Test
    @DisplayName("listFiles lists directory entries")
    void listFiles_listsEntries(@TempDir Path tmpDir) throws Exception {
        Files.createFile(tmpDir.resolve("a.java"));
        Files.createDirectories(tmpDir.resolve("sub"));

        var result = tools.listFiles(tmpDir.toString(), "", false);

        @SuppressWarnings("unchecked")
        var entries = (List<Map<String, Object>>) result.get("entries");
        assertThat(entries).hasSize(2);
    }

    // ── lookupLogReport ──

    @Test
    @DisplayName("lookupLogReport returns error when not found")
    void lookupLogReport_notFound_returnsError() {
        when(logRepository.findById(anyLong())).thenReturn(null);

        var result = tools.lookupLogReport(99L);

        assertThat(result).containsEntry("error", "Report not found: 99");
    }

    // ── generateProjectOverview ──

    @Test
    @DisplayName("generateProjectOverview returns available tools and project info")
    void generateProjectOverview_returnsOverview(@TempDir Path tmpDir) throws Exception {
        Files.createFile(tmpDir.resolve("pom.xml"));
        Files.createDirectories(tmpDir.resolve("src/main/java"));

        var result = tools.generateProjectOverview(tmpDir.toString());

        assertThat(result).containsKey("projectPath");
        assertThat(result).containsKey("kgAvailable");
        assertThat(result).containsKey("availableTools");
        assertThat(result).containsKey("topLevel");
    }

    // ── buildToolDefinitions ──

    @Test
    @DisplayName("buildToolDefinitions returns 10 tools")
    void buildToolDefinitions_returnsAllTools() {
        var defs = tools.buildToolDefinitions();

        // 5 KG + 3 FS + lookup_log_report + generate_project_overview = 10
        assertThat(defs).hasSize(10);
        var names = defs.stream().map(ToolDefinition::name).toList();
        assertThat(names).contains(
                "hybrid_search", "load_method_bodies", "callees_tree",
                "root_entries", "entry_points",
                "grep_project", "read_file", "list_files",
                "lookup_log_report", "generate_project_overview");
    }

    @Test
    @DisplayName("buildToolDefinitions without kgClient returns 5 tools")
    void buildToolDefinitions_noKgClient_returnsFsAndDbTools() {
        var noKg = new AgentTools(null, logRepository);
        var defs = noKg.buildToolDefinitions();
        // FS 3 + log 1 + overview 1 = 5
        assertThat(defs).hasSize(5);
    }

    // ── buildToolHandlers ──

    @Test
    @DisplayName("buildToolHandlers returns handlers for all tools")
    void buildToolHandlers_returnsAllHandlers() {
        when(kgClient.hybridSearch(anyString(), anyString(), anyInt()))
                .thenReturn(List.of(new Seed("n1", 0.9, "test")));

        Map<String, Function<Map<String, Object>, Object>> handlers = tools.buildToolHandlers("/proj");

        assertThat(handlers).hasSize(10);
        assertThat(handlers).containsKeys("hybrid_search", "lookup_log_report", "generate_project_overview");

        var result = handlers.get("hybrid_search").apply(Map.of("query", "test", "limit", 5));
        assertThat(result).isInstanceOf(List.class);
    }

    @Test
    @DisplayName("buildToolHandlers lookup_log_report works")
    void buildToolHandlers_lookupLogReport_works() {
        var handlers = tools.buildToolHandlers("/proj");
        var result = handlers.get("lookup_log_report").apply(Map.of("reportId", 1));
        assertThat(result).isInstanceOf(Map.class);
    }
}
