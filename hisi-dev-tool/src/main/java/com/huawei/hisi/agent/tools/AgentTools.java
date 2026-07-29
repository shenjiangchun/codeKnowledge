package com.huawei.hisi.agent.tools;

import com.huawei.hisi.ram.kg.KgMcpClient;
import com.huawei.hisi.ram.sdk.ToolDefinition;
import com.huawei.hisi.repository.LogAnalysisRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Facade bean that wraps KG + FS + log-analysis tools for use by LLM agents.
 *
 * <p>Each method accepts an explicit {@code projectPath} parameter (the Spring AI 1.0.8
 * equivalent of {@code ToolContext.get("projectPath")}). When upgrading to Spring AI 1.1+,
 * each method becomes {@code @Tool(description="...")} with a {@code ToolContext} parameter.
 *
 * <h3>Tools (10)</h3>
 * <ol>
 *   <li>{@code hybridSearch} — semantic code search (KG)
 *   <li>{@code loadMethodBodies} — load method source code (KG)
 *   <li>{@code calleesTree} — downstream call tree (KG)
 *   <li>{@code rootEntries} — upstream entry points (KG)
 *   <li>{@code entryPoints} — list system entry points (KG)
 *   <li>{@code grepProject} — text/regex search in project files (FS)
 *   <li>{@code readFile} — read a file's content (FS)
 *   <li>{@code listFiles} — list directory structure (FS)
 *   <li>{@code lookupLogReport} — look up a log analysis report (DB)
 *   <li>{@code generateProjectOverview} — generate a project overview (summary)
 * </ol>
 */
@Slf4j
@Component
public class AgentTools {

    static final int MAX_RESULT_CHARS = 50_000;
    private static final int MAX_GREP_LINES = 100;
    private static final int MAX_READ_CHARS = 30_000;
    private static final int MAX_LIST_ENTRIES = 200;

    private final KgMcpClient kgClient;
    private final LogAnalysisRepository logRepository;

    public AgentTools(@Autowired(required = false) KgMcpClient kgClient,
                      @Autowired LogAnalysisRepository logRepository) {
        this.kgClient = kgClient;
        this.logRepository = logRepository;
    }

    // ──────────────────── KG Tools ────────────────────

    /**
     * Semantic search for relevant code methods.
     * Returns a list of matching methods with nodeId, summary, and similarity score.
     */
    public List<Map<String, Object>> hybridSearch(String projectPath, String query, Integer limit) {
        if (kgClient == null) return errorList("KG not available");
        int n = limit != null ? limit : 10;
        var seeds = kgClient.hybridSearch(query, projectPath, n);
        return seeds.stream()
                .map(s -> Map.<String, Object>of(
                        "nodeId", s.nodeId(),
                        "summary", s.summary() != null ? s.summary() : "",
                        "score", s.score()))
                .toList();
    }

    /**
     * Load source code for methods identified by nodeId (from hybridSearch results).
     */
    public List<Map<String, Object>> loadMethodBodies(String projectPath, List<String> nodeIds) {
        if (kgClient == null) return errorList("KG not available");
        var bodies = kgClient.loadMethodBodies(nodeIds, projectPath);
        return bodies.stream().map(b -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("className", b.className());
            m.put("methodName", b.methodName());
            m.put("filePath", b.filePath() != null ? b.filePath() : "");
            m.put("description", b.description() != null ? b.description() : "");
            String body = b.methodBody() != null ? b.methodBody() : "";
            if (body.length() > 3000) body = body.substring(0, 3000) + "\n// ... truncated";
            m.put("methodBody", body);
            return m;
        }).toList();
    }

    /**
     * Query the downstream call tree for a method.
     */
    public Object calleesTree(String projectPath, String className, String methodName, Integer maxDepth) {
        if (kgClient == null) return errorMap("KG not available");
        int depth = maxDepth != null ? maxDepth : 5;
        return kgClient.calleesTree(className, methodName, projectPath, depth);
    }

    /**
     * Find upstream entry points (Controller/scheduler/MQ) that reach a method.
     */
    public Object rootEntries(String projectPath, String className, String methodName) {
        if (kgClient == null) return errorMap("KG not available");
        return kgClient.rootEntries(className, methodName, projectPath);
    }

    /**
     * List system entry points filtered by type (CONTROLLER/SCHEDULED/MQ_LISTENER/FEIGN_CLIENT/ALL).
     */
    public Object entryPoints(String projectPath, String entryType) {
        if (kgClient == null) return errorMap("KG not available");
        return kgClient.entryPoints(projectPath, entryType);
    }

    // ──────────────────── File-system Tools ────────────────────

    /**
     * Search project files for text/regex matches.
     */
    public Map<String, Object> grepProject(String projectPath, String pattern,
                                           String fileGlob, Boolean caseSensitive) {
        if (pattern == null || pattern.isBlank()) return errorMap("pattern is required");
        Path root = Path.of(projectPath);
        if (!Files.isDirectory(root)) return errorMap("Project path not found: " + projectPath);

        boolean matchCase = caseSensitive != null && caseSensitive;
        List<Map<String, Object>> matches = new ArrayList<>();
        int lineCount = 0;

        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> files = walk
                    .filter(Files::isRegularFile)
                    .filter(p -> !isExcluded(root, p))
                    .filter(p -> fileGlob == null || matchGlob(p.getFileName().toString(), fileGlob))
                    .limit(5000)
                    .toList();
            for (Path file : files) {
                if (lineCount >= MAX_GREP_LINES) break;
                for (String line : Files.readAllLines(file)) {
                    if (lineCount >= MAX_GREP_LINES) break;
                    boolean matched = matchCase
                            ? line.contains(pattern)
                            : line.toLowerCase().contains(pattern.toLowerCase());
                    if (matched) {
                        matches.add(Map.of(
                                "file", root.relativize(file).toString().replace('\\', '/'),
                                "line", lineCount + 1,
                                "content", truncate(line.trim(), 200)));
                        lineCount++;
                    }
                }
            }
        } catch (IOException e) {
            return errorMap("Failed to search: " + e.getMessage());
        }
        return Map.of("matches", matches, "total", lineCount,
                "truncated", lineCount >= MAX_GREP_LINES);
    }

    /**
     * Read the content of a project file (relative path from project root).
     */
    public Map<String, Object> readFile(String projectPath, String relativePath) {
        if (relativePath == null || relativePath.isBlank()) return errorMap("path is required");
        Path root = Path.of(projectPath);
        Path resolved = root.resolve(relativePath).normalize();
        if (!resolved.startsWith(root)) return errorMap("Path traversal not allowed: " + relativePath);
        if (!Files.isRegularFile(resolved)) return errorMap("File not found: " + relativePath);

        try {
            String content = Files.readString(resolved);
            if (content.length() > MAX_READ_CHARS)
                content = content.substring(0, MAX_READ_CHARS) + "\n\n// ... [truncated]";
            return Map.of("path", relativePath, "content", content,
                    "size", Files.size(resolved));
        } catch (IOException e) {
            return errorMap("Failed to read file: " + e.getMessage());
        }
    }

    /**
     * List files and directories under a project path.
     */
    public Map<String, Object> listFiles(String projectPath, String relativePath, Boolean recursive) {
        Path root = Path.of(projectPath);
        Path dir = (relativePath == null || relativePath.isBlank())
                ? root : root.resolve(relativePath).normalize();
        if (!dir.startsWith(root)) return errorMap("Path traversal not allowed: " + relativePath);
        if (!Files.isDirectory(dir)) return errorMap("Directory not found: " + relativePath);

        boolean recurse = recursive != null && recursive;
        List<Map<String, Object>> entries = new ArrayList<>();
        try {
            if (recurse) {
                try (Stream<Path> walk = Files.walk(dir)) {
                    walk.filter(p -> !p.equals(dir))
                            .filter(p -> !isExcluded(root, p))
                            .limit(MAX_LIST_ENTRIES)
                            .forEach(p -> entries.add(Map.of(
                                    "path", root.relativize(p).toString().replace('\\', '/'),
                                    "type", Files.isDirectory(p) ? "directory" : "file")));
                }
            } else {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                    int count = 0;
                    for (Path p : stream) {
                        if (count >= MAX_LIST_ENTRIES) break;
                        if (isExcluded(root, p)) continue;
                        entries.add(Map.of(
                                "path", root.relativize(p).toString().replace('\\', '/'),
                                "type", Files.isDirectory(p) ? "directory" : "file"));
                        count++;
                    }
                }
            }
        } catch (IOException e) {
            return errorMap("Failed to list directory: " + e.getMessage());
        }
        return Map.of("entries", entries, "total", entries.size(),
                "truncated", entries.size() >= MAX_LIST_ENTRIES);
    }

    // ──────────────────── DB / Analysis Tools ────────────────────

    /**
     * Look up a log analysis report by ID.
     */
    public Map<String, Object> lookupLogReport(Long reportId) {
        if (logRepository == null) return errorMap("Log repository not available");
        var report = logRepository.findById(reportId);
        if (report == null) return errorMap("Report not found: " + reportId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reportId", report.getReportId());
        result.put("status", report.getStatus());
        result.put("errorType", report.getErrorType());
        result.put("logMessage", report.getLogMessage());
        result.put("logStackTrace", truncate(report.getLogStackTrace(), 5000));
        result.put("errorSummary", report.getErrorSummary());
        result.put("rootCause", report.getRootCause());
        result.put("fixSuggestions", report.getFixSuggestions());
        result.put("codeSnippets", report.getCodeSnippets());
        result.put("occurrenceCount", report.getOccurrenceCount());
        return result;
    }

    /**
     * Generate a project overview — available tools, entry points, and project path info.
     * This is a light scaffolding tool so agents can discover available capabilities.
     */
    public Map<String, Object> generateProjectOverview(String projectPath) {
        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("projectPath", projectPath);
        overview.put("kgAvailable", kgClient != null);

        // List available tools
        List<String> tools = new ArrayList<>();
        if (kgClient != null) {
            tools.add("hybridSearch");
            tools.add("loadMethodBodies");
            tools.add("calleesTree");
            tools.add("rootEntries");
            tools.add("entryPoints");
        }
        tools.add("grepProject");
        tools.add("readFile");
        tools.add("listFiles");
        tools.add("lookupLogReport");
        overview.put("availableTools", tools);

        // Brief project structure summary
        Path root = Path.of(projectPath);
        if (Files.isDirectory(root)) {
            try {
                List<String> topLevel = new ArrayList<>();
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
                    for (Path p : stream) {
                        if (isExcluded(root, p)) continue;
                        topLevel.add(p.getFileName().toString()
                                + (Files.isDirectory(p) ? "/" : ""));
                    }
                }
                overview.put("topLevel", topLevel.stream().sorted().limit(30).toList());
            } catch (IOException ignored) {
                overview.put("topLevel", "unreadable");
            }
        }
        return overview;
    }

    // ──────────────────── ToolDefinition / Handler backward compat ────────────────────

    /** Expose tool definitions in the same format as KgToolRegistry for existing consumers. */
    public List<ToolDefinition> buildToolDefinitions() {
        List<ToolDefinition> tools = new ArrayList<>();
        if (kgClient != null) {
            kgToolDefs(tools);
        }
        fsToolDefs(tools);
        tools.add(new ToolDefinition("lookup_log_report",
                "查看日志分析报告的详细信息，包括原始错误消息、堆栈跟踪、分析结果。传入 reportId 获取完整报告数据。",
                "{\"type\":\"object\",\"properties\":{\"reportId\":{\"type\":\"number\",\"description\":\"日志分析报告 ID\"}},\"required\":[\"reportId\"]}"));
        tools.add(new ToolDefinition("generate_project_overview",
                "查看项目概览：可用工具有哪些、项目路径、顶层文件结构。用于了解当前可以调用哪些工具。",
                "{\"type\":\"object\",\"properties\":{},\"required\":[]}"));
        return List.copyOf(tools);
    }

    /** Expose handlers in KgToolRegistry format for existing consumers. */
    public Map<String, Function<Map<String, Object>, Object>> buildToolHandlers(String projectPath) {
        Map<String, Function<Map<String, Object>, Object>> handlers = new LinkedHashMap<>();
        if (kgClient != null) {
            kgHandlers(handlers, projectPath);
        }
        fsHandlers(handlers, projectPath);

        handlers.put("lookup_log_report", input -> {
            Object rid = input.get("reportId");
            long reportId = rid instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(rid));
            return lookupLogReport(reportId);
        });
        handlers.put("generate_project_overview", input -> generateProjectOverview(projectPath));
        return Map.copyOf(handlers);
    }

    // ──────────────────── private helpers ────────────────────

    private void kgToolDefs(List<ToolDefinition> tools) {
        tools.add(new ToolDefinition("hybrid_search",
                "语义搜索项目代码。输入自然语言查询，返回最相关的方法列表（nodeId、摘要、相似度分数）。",
                "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\",\"description\":\"自然语言查询\"},\"limit\":{\"type\":\"integer\",\"description\":\"最多结果数，默认10\"}},\"required\":[\"query\"]}"));
        tools.add(new ToolDefinition("load_method_bodies",
                "加载指定方法的源码。输入 nodeId 列表（从 hybrid_search 结果获得）。",
                "{\"type\":\"object\",\"properties\":{\"node_ids\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}},\"required\":[\"node_ids\"]}"));
        tools.add(new ToolDefinition("callees_tree",
                "查看方法的下游调用链（树形结构）。",
                "{\"type\":\"object\",\"properties\":{\"class_name\":{\"type\":\"string\"},\"method_name\":{\"type\":\"string\"},\"max_depth\":{\"type\":\"integer\",\"default\":5}},\"required\":[\"class_name\",\"method_name\"]}"));
        tools.add(new ToolDefinition("root_entries",
                "查看方法的上游入口——哪些Controller/定时任务会调用到该方法。",
                "{\"type\":\"object\",\"properties\":{\"class_name\":{\"type\":\"string\"},\"method_name\":{\"type\":\"string\"}},\"required\":[\"class_name\",\"method_name\"]}"));
        tools.add(new ToolDefinition("entry_points",
                "列出项目的系统入口点（Controller、定时任务、MQ监听器、Feign客户端）。",
                "{\"type\":\"object\",\"properties\":{\"entry_type\":{\"type\":\"string\",\"enum\":[\"CONTROLLER\",\"SCHEDULED\",\"MQ_LISTENER\",\"FEIGN_CLIENT\",\"ALL\"]}},\"required\":[]}"));
    }

    private void fsToolDefs(List<ToolDefinition> tools) {
        tools.add(new ToolDefinition("grep_project",
                "在项目目录下搜索包含指定文本或正则表达式的文件。",
                "{\"type\":\"object\",\"properties\":{\"pattern\":{\"type\":\"string\"},\"file_glob\":{\"type\":\"string\"},\"case_sensitive\":{\"type\":\"boolean\",\"default\":false}},\"required\":[\"pattern\"]}"));
        tools.add(new ToolDefinition("read_file",
                "读取项目中指定文件的内容（相对路径）。",
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},\"required\":[\"path\"]}"));
        tools.add(new ToolDefinition("list_files",
                "列出项目中指定目录的文件和子目录（相对路径）。",
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\",\"default\":\"\"},\"recursive\":{\"type\":\"boolean\",\"default\":false}},\"required\":[]}"));
    }

    private void kgHandlers(Map<String, Function<Map<String, Object>, Object>> handlers, String projectPath) {
        handlers.put("hybrid_search", input ->
                hybridSearch(projectPath, str(input, "query"), intVal(input, "limit", 10)));
        handlers.put("load_method_bodies", input ->
                loadMethodBodies(projectPath, strList(input, "node_ids")));
        handlers.put("callees_tree", input ->
                calleesTree(projectPath, str(input, "class_name"), str(input, "method_name"), intVal(input, "max_depth", 5)));
        handlers.put("root_entries", input ->
                rootEntries(projectPath, str(input, "class_name"), str(input, "method_name")));
        handlers.put("entry_points", input ->
                entryPoints(projectPath, str(input, "entry_type", "ALL")));
    }

    private void fsHandlers(Map<String, Function<Map<String, Object>, Object>> handlers, String projectPath) {
        handlers.put("grep_project", input ->
                grepProject(projectPath, str(input, "pattern"),
                        str(input, "file_glob", null),
                        boolVal(input, "case_sensitive", false)));
        handlers.put("read_file", input ->
                readFile(projectPath, str(input, "path")));
        handlers.put("list_files", input ->
                listFiles(projectPath, str(input, "path", ""),
                        boolVal(input, "recursive", false)));
    }

    private boolean isExcluded(Path root, Path p) {
        String rel = root.relativize(p).toString().replace('\\', '/');
        return rel.startsWith(".git/") || rel.startsWith(".git")
                || rel.startsWith("target/") || rel.startsWith("build/")
                || rel.startsWith("node_modules/") || rel.startsWith(".idea/")
                || rel.startsWith(".claude/") || rel.contains("/.git/");
    }

    private static boolean matchGlob(String fileName, String glob) {
        if (glob.startsWith("*.")) return fileName.endsWith(glob.substring(1));
        return fileName.contains(glob);
    }

    // ──────────────────── static utils ────────────────────

    private static String str(Map<String, Object> input, String key) {
        return str(input, key, "");
    }
    private static String str(Map<String, Object> input, String key, String def) {
        Object v = input == null ? null : input.get(key);
        return v instanceof String s ? s : def;
    }
    private static int intVal(Map<String, Object> input, String key, int def) {
        Object v = input == null ? null : input.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) { try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {} }
        return def;
    }
    private static boolean boolVal(Map<String, Object> input, String key, boolean def) {
        Object v = input == null ? null : input.get(key);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return Boolean.parseBoolean(s);
        return def;
    }
    @SuppressWarnings("unchecked")
    private static List<String> strList(Map<String, Object> input, String key) {
        Object v = input == null ? null : input.get(key);
        if (v instanceof List<?> l) return l.stream().filter(o -> o instanceof String).map(o -> (String) o).toList();
        return List.of();
    }
    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
    private static List<Map<String, Object>> errorList(String msg) {
        return List.of(Map.of("error", msg));
    }
    private static Map<String, Object> errorMap(String msg) {
        return Map.of("error", msg);
    }
}
