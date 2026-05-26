package com.huawei.hisi.ram.nodes.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.ram.kg.KgMcpClient;
import com.huawei.hisi.ram.sdk.ToolDefinition;
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
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Registers {@link KgMcpClient} methods and file-system utilities as Claude
 * {@link ToolDefinition}s for the ClarifyNode tool_use flow.
 *
 * <p>Each tool's {@code projectPath} is bound at creation time via closure,
 * so the LLM never needs to guess or hallucinate file paths.
 *
 * <h3>Exposed tools (8)</h3>
 * <table>
 *     <tr><th>KG tools (5)</th></tr>
 *     <tr><td>hybrid_search</td><td>Semantic search for relevant code</td></tr>
 *     <tr><td>load_method_bodies</td><td>Load method source code</td></tr>
 *     <tr><td>callees_tree</td><td>Downstream call tree</td></tr>
 *     <tr><td>root_entries</td><td>Upstream entry points</td></tr>
 *     <tr><td>entry_points</td><td>List system entry points</td></tr>
 *     <tr><th>File-system tools (3)</th></tr>
 *     <tr><td>grep_project</td><td>Text/regex search in project files</td></tr>
 *     <tr><td>read_file</td><td>Read a file's content</td></tr>
 *     <tr><td>list_files</td><td>List directory structure</td></tr>
 * </table>
 */
@Slf4j
@Component
public class KgToolRegistry {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Maximum characters for a single tool result to prevent prompt overflow. */
    private static final int MAX_RESULT_CHARS = 50_000;

    /** Maximum lines returned by grep. */
    private static final int MAX_GREP_LINES = 100;

    /** Maximum characters for read_file. */
    private static final int MAX_READ_CHARS = 30_000;

    /** Maximum entries for list_files. */
    private static final int MAX_LIST_ENTRIES = 200;

    private final KgMcpClient kgClient; // nullable

    public KgToolRegistry(@Autowired(required = false) KgMcpClient kgClient) {
        this.kgClient = kgClient;
    }

    /** Returns {@code true} when at least KG or FS tools can be registered. */
    public boolean isAvailable() {
        // FS tools are always available (just need projectPath);
        // KG tools need kgClient
        return true;
    }

    /** Whether KG-specific tools are available. */
    public boolean hasKgTools() {
        return kgClient != null;
    }

    /**
     * Build tool definitions for the given project path.
     * Always includes FS tools; includes KG tools when {@link KgMcpClient} is available.
     */
    public List<ToolDefinition> buildToolDefinitions(String projectPath) {
        List<ToolDefinition> tools = new ArrayList<>();

        // ── KG tools ──
        if (kgClient != null) {
            tools.add(new ToolDefinition(
                    "hybrid_search",
                    "语义搜索项目代码。输入自然语言查询，返回最相关的方法列表（nodeId、摘要、相似度分数）。" +
                    "用 nodeId 调用 load_method_bodies 获取源码详情。",
                    """
                    {
                      "type": "object",
                      "properties": {
                        "query": {
                          "type": "string",
                          "description": "自然语言查询，描述要查找的功能或行为，例如'处理支付回调的方法'"
                        },
                        "limit": {
                          "type": "integer",
                          "description": "最多返回结果数量，默认10",
                          "default": 10
                        }
                      },
                      "required": ["query"]
                    }
                    """
            ));

            tools.add(new ToolDefinition(
                    "load_method_bodies",
                    "加载指定方法的源码。输入 nodeId 列表（从 hybrid_search 结果获得），" +
                    "返回每个方法的完整源码、类名、方法名、文件路径。用于深入理解具体实现细节。",
                    """
                    {
                      "type": "object",
                      "properties": {
                        "node_ids": {
                          "type": "array",
                          "items": { "type": "string" },
                          "description": "方法节点ID列表（从 hybrid_search 结果的 nodeId 字段获取）"
                        }
                      },
                      "required": ["node_ids"]
                    }
                    """
            ));

            tools.add(new ToolDefinition(
                    "callees_tree",
                    "查看方法的下游调用链。输入类名和方法名，返回该方法调用了哪些其他方法（树形结构）。" +
                    "用于理解方法的依赖关系和影响范围。",
                    """
                    {
                      "type": "object",
                      "properties": {
                        "class_name": {
                          "type": "string",
                          "description": "全限定类名，例如 'com.example.service.OrderService'"
                        },
                        "method_name": {
                          "type": "string",
                          "description": "方法名，例如 'placeOrder'"
                        },
                        "max_depth": {
                          "type": "integer",
                          "description": "最大追踪深度，默认5",
                          "default": 5
                        }
                      },
                      "required": ["class_name", "method_name"]
                    }
                    """
            ));

            tools.add(new ToolDefinition(
                    "root_entries",
                    "查看方法的上游入口。输入类名和方法名，返回哪些Controller/定时任务/消息监听器" +
                    "最终会调用到这个方法。用于理解方法的调用来源和影响面。",
                    """
                    {
                      "type": "object",
                      "properties": {
                        "class_name": {
                          "type": "string",
                          "description": "全限定类名"
                        },
                        "method_name": {
                          "type": "string",
                          "description": "方法名"
                        }
                      },
                      "required": ["class_name", "method_name"]
                    }
                    """
            ));

            tools.add(new ToolDefinition(
                    "entry_points",
                    "列出项目的系统入口点（Controller接口、定时任务、消息监听器、Feign客户端）。" +
                    "用于了解项目有哪些对外暴露的接口和后台任务。",
                    """
                    {
                      "type": "object",
                      "properties": {
                        "entry_type": {
                          "type": "string",
                          "enum": ["CONTROLLER", "SCHEDULED", "MQ_LISTENER", "FEIGN_CLIENT", "ALL"],
                          "description": "入口点类型，默认ALL",
                          "default": "ALL"
                        }
                      },
                      "required": []
                    }
                    """
            ));
        }

        // ── File-system tools (always available when projectPath is valid) ──
        tools.add(new ToolDefinition(
                "grep_project",
                "在项目目录下搜索包含指定文本或正则表达式的文件。返回匹配的文件名和行内容。" +
                "适用于搜索配置文件、注解、字符串常量等 KG 未索引的内容。",
                """
                {
                  "type": "object",
                  "properties": {
                    "pattern": {
                      "type": "string",
                      "description": "搜索的文本或正则表达式"
                    },
                    "file_glob": {
                      "type": "string",
                      "description": "文件过滤模式，例如 '*.java', '*.xml', '*.yml'，默认搜索所有文件"
                    },
                    "case_sensitive": {
                      "type": "boolean",
                      "description": "是否区分大小写，默认false",
                      "default": false
                    }
                  },
                  "required": ["pattern"]
                }
                """
        ));

        tools.add(new ToolDefinition(
                "read_file",
                "读取项目中指定文件的内容。路径必须是相对于项目根目录的相对路径。" +
                "适用于查看配置文件(pom.xml, application.yml)、README、特定源码文件等。",
                """
                {
                  "type": "object",
                  "properties": {
                    "path": {
                      "type": "string",
                      "description": "相对于项目根目录的文件路径，例如 'pom.xml' 或 'src/main/resources/application.yml'"
                    }
                  },
                  "required": ["path"]
                }
                """
        ));

        tools.add(new ToolDefinition(
                "list_files",
                "列出项目中指定目录的文件和子目录。路径必须是相对于项目根目录的相对路径。" +
                "用于了解项目结构和模块布局。",
                """
                {
                  "type": "object",
                  "properties": {
                    "path": {
                      "type": "string",
                      "description": "相对于项目根目录的目录路径，默认为项目根目录",
                      "default": ""
                    },
                    "recursive": {
                      "type": "boolean",
                      "description": "是否递归列出子目录，默认false",
                      "default": false
                    }
                  },
                  "required": []
                }
                """
        ));

        return List.copyOf(tools);
    }

    /**
     * Build tool handlers bound to the given project path.
     * Each handler receives the LLM's input map and returns a result
     * that will be serialized as the tool_result content.
     */
    public Map<String, Function<Map<String, Object>, Object>> buildToolHandlers(String projectPath) {
        Map<String, Function<Map<String, Object>, Object>> handlers = new LinkedHashMap<>();

        // ── KG handlers ──
        if (kgClient != null) {
            handlers.put("hybrid_search", input -> {
                String query = getString(input, "query", "");
                int limit = getInt(input, "limit", 10);
                var seeds = kgClient.hybridSearch(query, projectPath, limit);
                return seeds.stream().map(s -> Map.of(
                        "nodeId", s.nodeId(),
                        "summary", s.summary() != null ? s.summary() : "",
                        "score", s.score()
                )).toList();
            });

            handlers.put("load_method_bodies", input -> {
                List<String> nodeIds = getStringList(input, "node_ids");
                var bodies = kgClient.loadMethodBodies(nodeIds, projectPath);
                return bodies.stream().map(b -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("className", b.className());
                    m.put("methodName", b.methodName());
                    m.put("filePath", b.filePath() != null ? b.filePath() : "");
                    m.put("description", b.description() != null ? b.description() : "");
                    String body = b.methodBody() != null ? b.methodBody() : "";
                    // Truncate large method bodies
                    if (body.length() > 3000) {
                        body = body.substring(0, 3000) + "\n// ... truncated";
                    }
                    m.put("methodBody", body);
                    return m;
                }).toList();
            });

            handlers.put("callees_tree", input -> {
                String className = getString(input, "class_name", "");
                String methodName = getString(input, "method_name", "");
                int maxDepth = getInt(input, "max_depth", 5);
                return kgClient.calleesTree(className, methodName, projectPath, maxDepth);
            });

            handlers.put("root_entries", input -> {
                String className = getString(input, "class_name", "");
                String methodName = getString(input, "method_name", "");
                return kgClient.rootEntries(className, methodName, projectPath);
            });

            handlers.put("entry_points", input -> {
                String entryType = getString(input, "entry_type", "ALL");
                return kgClient.entryPoints(projectPath, entryType);
            });
        }

        // ── File-system handlers ──
        handlers.put("grep_project", input -> grepProject(
                projectPath,
                getString(input, "pattern", ""),
                getString(input, "file_glob", null),
                getBoolean(input, "case_sensitive", false)
        ));

        handlers.put("read_file", input -> readFile(
                projectPath,
                getString(input, "path", "")
        ));

        handlers.put("list_files", input -> listFiles(
                projectPath,
                getString(input, "path", ""),
                getBoolean(input, "recursive", false)
        ));

        return Map.copyOf(handlers);
    }

    // ────────────────────── File-system tool implementations ──────────────────────

    private Object grepProject(String projectPath, String pattern,
                               String fileGlob, boolean caseSensitive) {
        if (pattern == null || pattern.isBlank()) {
            return Map.of("error", "pattern is required");
        }

        Path root = Path.of(projectPath);
        if (!Files.isDirectory(root)) {
            return Map.of("error", "Project path not found: " + projectPath);
        }

        List<Map<String, Object>> matches = new ArrayList<>();
        int lineCount = 0;

        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> files = walk
                    .filter(Files::isRegularFile)
                    .filter(p -> !isExcluded(root, p))
                    .filter(p -> fileGlob == null || matchGlob(p.getFileName().toString(), fileGlob))
                    .limit(5000) // safety limit on file count
                    .toList();

            for (Path file : files) {
                if (lineCount >= MAX_GREP_LINES) break;
                try {
                    List<String> lines = Files.readAllLines(file);
                    for (int i = 0; i < lines.size() && lineCount < MAX_GREP_LINES; i++) {
                        String line = lines.get(i);
                        boolean match = caseSensitive
                                ? line.contains(pattern)
                                : line.toLowerCase().contains(pattern.toLowerCase());
                        if (match) {
                            matches.add(Map.of(
                                    "file", root.relativize(file).toString().replace('\\', '/'),
                                    "line", i + 1,
                                    "content", truncate(line.trim(), 200)
                            ));
                            lineCount++;
                        }
                    }
                } catch (IOException ignored) {
                    // skip binary/unreadable files
                }
            }
        } catch (IOException e) {
            return Map.of("error", "Failed to search: " + e.getMessage());
        }

        return Map.of(
                "matches", matches,
                "total", lineCount,
                "truncated", lineCount >= MAX_GREP_LINES
        );
    }

    private Object readFile(String projectPath, String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return Map.of("error", "path is required");
        }

        Path root = Path.of(projectPath);
        Path resolved = root.resolve(relativePath).normalize();

        // Security: ensure path stays within project
        if (!resolved.startsWith(root)) {
            return Map.of("error", "Path traversal not allowed: " + relativePath);
        }

        if (!Files.isRegularFile(resolved)) {
            return Map.of("error", "File not found: " + relativePath);
        }

        try {
            String content = Files.readString(resolved);
            if (content.length() > MAX_READ_CHARS) {
                content = content.substring(0, MAX_READ_CHARS) + "\n\n// ... [truncated, file too large]";
            }
            return Map.of(
                    "path", relativePath,
                    "content", content,
                    "size", Files.size(resolved)
            );
        } catch (IOException e) {
            return Map.of("error", "Failed to read file: " + e.getMessage());
        }
    }

    private Object listFiles(String projectPath, String relativePath, boolean recursive) {
        Path root = Path.of(projectPath);
        Path dir = relativePath == null || relativePath.isBlank()
                ? root : root.resolve(relativePath).normalize();

        // Security: ensure path stays within project
        if (!dir.startsWith(root)) {
            return Map.of("error", "Path traversal not allowed: " + relativePath);
        }

        if (!Files.isDirectory(dir)) {
            return Map.of("error", "Directory not found: " + relativePath);
        }

        List<Map<String, Object>> entries = new ArrayList<>();
        try {
            if (recursive) {
                try (Stream<Path> walk = Files.walk(dir)) {
                    walk.filter(p -> !p.equals(dir))
                            .filter(p -> !isExcluded(root, p))
                            .limit(MAX_LIST_ENTRIES)
                            .forEach(p -> entries.add(Map.of(
                                    "path", root.relativize(p).toString().replace('\\', '/'),
                                    "type", Files.isDirectory(p) ? "directory" : "file"
                            )));
                }
            } else {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                    int count = 0;
                    for (Path p : stream) {
                        if (count >= MAX_LIST_ENTRIES) break;
                        if (isExcluded(root, p)) continue;
                        entries.add(Map.of(
                                "path", root.relativize(p).toString().replace('\\', '/'),
                                "type", Files.isDirectory(p) ? "directory" : "file"
                        ));
                        count++;
                    }
                }
            }
        } catch (IOException e) {
            return Map.of("error", "Failed to list directory: " + e.getMessage());
        }

        return Map.of(
                "entries", entries,
                "total", entries.size(),
                "truncated", entries.size() >= MAX_LIST_ENTRIES
        );
    }

    // ────────────────────── Utility methods ──────────────────────

    /** Check if a path should be excluded (build artifacts, VCS, etc). */
    private boolean isExcluded(Path root, Path p) {
        String rel = root.relativize(p).toString().replace('\\', '/');
        return rel.startsWith(".git/") || rel.startsWith(".git")
                || rel.startsWith("target/") || rel.startsWith("build/")
                || rel.startsWith("node_modules/") || rel.startsWith(".idea/")
                || rel.startsWith(".claude/") || rel.contains("/.git/");
    }

    private boolean matchGlob(String fileName, String glob) {
        // Simple glob matching: *.java, *.xml, etc.
        if (glob.startsWith("*.")) {
            String ext = glob.substring(1);
            return fileName.endsWith(ext);
        }
        return fileName.contains(glob);
    }

    private static String getString(Map<String, Object> input, String key, String defaultValue) {
        Object v = input == null ? null : input.get(key);
        return v instanceof String s ? s : defaultValue;
    }

    private static int getInt(Map<String, Object> input, String key, int defaultValue) {
        Object v = input == null ? null : input.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        }
        return defaultValue;
    }

    private static boolean getBoolean(Map<String, Object> input, String key, boolean defaultValue) {
        Object v = input == null ? null : input.get(key);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return Boolean.parseBoolean(s);
        return defaultValue;
    }

    @SuppressWarnings("unchecked")
    private static List<String> getStringList(Map<String, Object> input, String key) {
        Object v = input == null ? null : input.get(key);
        if (v instanceof List<?> list) {
            return list.stream()
                    .filter(o -> o instanceof String)
                    .map(o -> (String) o)
                    .toList();
        }
        return List.of();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /**
     * Serialize a tool result to JSON string, with size truncation.
     * Used by the tool_use loop client.
     */
    public static String serializeResult(Object result) {
        try {
            String json = MAPPER.writeValueAsString(result);
            if (json.length() > MAX_RESULT_CHARS) {
                json = json.substring(0, MAX_RESULT_CHARS) + " [truncated]";
            }
            return json;
        } catch (Exception e) {
            return "{\"error\": \"Failed to serialize result: " + e.getMessage() + "\"}";
        }
    }
}
