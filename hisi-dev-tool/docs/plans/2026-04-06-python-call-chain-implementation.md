# Python Call Chain Analysis Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add Python call chain analysis capability without affecting existing Java functionality, and enable inline expansion of external calls in the frontend.

**Architecture:** Template Method pattern with AbstractChainAnalyzer base class, PythonChainAnalyzer implementation using Tree-sitter for parsing, and frontend enhancement for inline expansion.

**Tech Stack:** Java 17, Spring Boot 3.2, Tree-sitter (via tree-sitter-java binding), Python AST

---

## Phase 1: Core Infrastructure

### Task 1: Create LanguageType Enum

**Files:**
- Create: `src/main/java/com/huawei/hisi/analyzer/LanguageType.java`

**Step 1: Write the enum**

```java
package com.huawei.hisi.analyzer;

/**
 * Supported programming languages for call chain analysis.
 */
public enum LanguageType {
    JAVA("java", ".java"),
    PYTHON("python", ".py");

    private final String name;
    private final String extension;

    LanguageType(String name, String extension) {
        this.name = name;
        this.extension = extension;
    }

    public String getName() {
        return name;
    }

    public String getExtension() {
        return extension;
    }

    public static LanguageType fromExtension(String fileExtension) {
        for (LanguageType type : values()) {
            if (type.extension.equalsIgnoreCase(fileExtension)) {
                return type;
            }
        }
        return null;
    }
}
```

**Step 2: Commit**

```bash
git add src/main/java/com/huawei/hisi/analyzer/LanguageType.java
git commit -m "feat: add LanguageType enum for multi-language support"
```

---

### Task 2: Create AnalyzeContext Class

**Files:**
- Create: `src/main/java/com/huawei/hisi/analyzer/AnalyzeContext.java`

**Step 1: Write the context class**

```java
package com.huawei.hisi.analyzer;

import com.huawei.hisi.cache.GlobalAnalysisCache;
import lombok.Builder;
import lombok.Data;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Context object passed through the analysis pipeline.
 * Replaces direct GlobalAnalysisCache usage for better encapsulation.
 */
@Data
@Builder
public class AnalyzeContext {

    private String projectDir;
    private LanguageType languageType;
    private GlobalAnalysisCache globalCache;
    private Path sourceRoot;

    // Analysis results
    private int totalFiles;
    private int totalMethods;
    private int totalCallEdges;
    private int bridgeCount;

    // Timing
    private long startTime;
    private long endTime;

    // Additional metadata
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();

    public void addMetadata(String key, Object value) {
        metadata.put(key, value);
    }

    public Object getMetadata(String key) {
        return metadata.get(key);
    }

    public long getDurationMs() {
        return endTime - startTime;
    }
}
```

**Step 2: Commit**

```bash
git add src/main/java/com/huawei/hisi/analyzer/AnalyzeContext.java
git commit -m "feat: add AnalyzeContext for analysis pipeline context passing"
```

---

### Task 3: Create AnalysisResult Class

**Files:**
- Create: `src/main/java/com/huawei/hisi/analyzer/AnalysisResult.java`

**Step 1: Write the result class**

```java
package com.huawei.hisi.analyzer;

import lombok.Builder;
import lombok.Data;

/**
 * Result of a call chain analysis operation.
 */
@Data
@Builder
public class AnalysisResult {

    private boolean success;
    private String message;
    private String error;

    private LanguageType languageType;
    private String projectDir;

    // Statistics
    private int uriCount;
    private int methodCount;
    private int callEdgeCount;
    private int mqBridgeCount;
    private int httpBridgeCount;

    // Timing
    private long durationMs;

    public static AnalysisResult success(LanguageType language, String projectDir) {
        return AnalysisResult.builder()
                .success(true)
                .languageType(language)
                .projectDir(projectDir)
                .message("Analysis completed successfully")
                .build();
    }

    public static AnalysisResult failure(String error) {
        return AnalysisResult.builder()
                .success(false)
                .error(error)
                .message("Analysis failed: " + error)
                .build();
    }
}
```

**Step 2: Commit**

```bash
git add src/main/java/com/huawei/hisi/analyzer/AnalysisResult.java
git commit -m "feat: add AnalysisResult for analysis operation results"
```

---

### Task 4: Create AbstractChainAnalyzer Base Class

**Files:**
- Create: `src/main/java/com/huawei/hisi/analyzer/AbstractChainAnalyzer.java`

**Step 1: Write the abstract base class**

```java
package com.huawei.hisi.analyzer;

import com.huawei.hisi.bridge.ChainBridge;
import com.huawei.hisi.bridge.HttpChainBridge;
import com.huawei.hisi.bridge.MQChainBridge;
import com.huawei.hisi.bridge.ProxyChainBridge;
import com.huawei.hisi.cache.GlobalAnalysisCache;
import lombok.extern.slf4j.Slf4j;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

/**
 * Abstract base class for language-specific call chain analyzers.
 * Implements Template Method pattern - defines the analysis skeleton,
 * with abstract methods for language-specific parsing logic.
 *
 * Subclasses:
 * - JavaChainAnalyzer: Uses existing JavaParser-based logic
 * - PythonChainAnalyzer: Uses Tree-sitter for Python parsing
 */
@Slf4j
public abstract class AbstractChainAnalyzer {

    protected final GlobalAnalysisCache globalCache;
    protected final DataSource dataSource;

    // Bridges - shared across all languages
    protected final MQChainBridge mqBridge;
    protected final HttpChainBridge httpBridge;
    protected final ProxyChainBridge proxyBridge;

    protected AbstractChainAnalyzer(DataSource dataSource) {
        this.globalCache = new GlobalAnalysisCache();
        this.dataSource = dataSource;
        this.mqBridge = new MQChainBridge();
        this.httpBridge = new HttpChainBridge();
        this.proxyBridge = new ProxyChainBridge();
    }

    /**
     * Template method - defines the analysis workflow skeleton.
     * DO NOT OVERRIDE - override the hook methods instead.
     */
    public final AnalysisResult analyze(String projectDir) {
        // Validate
        if (projectDir == null || projectDir.trim().isEmpty()) {
            return AnalysisResult.failure("Project directory is required");
        }

        long startTime = System.currentTimeMillis();

        try {
            // 1. Create context
            AnalyzeContext context = createContext(projectDir);
            context.setStartTime(startTime);
            beforeAnalyze(context);

            // 2. Discover files (language-specific)
            log.info("[{}] Discovering source files...", getLanguageType().getName());
            List<Path> sourceFiles = discoverFiles(projectDir, context);
            context.setTotalFiles(sourceFiles.size());
            log.info("[{}] Found {} source files", getLanguageType().getName(), sourceFiles.size());

            // 3. Parse and extract (language-specific)
            log.info("[{}] Parsing and extracting endpoints...", getLanguageType().getName());
            parseAndExtract(sourceFiles, context);

            // 4. Build call graph (language-specific)
            log.info("[{}] Building call graph...", getLanguageType().getName());
            buildCallGraph(context);

            // 5. Save to database (common logic)
            log.info("[{}] Saving to database...", getLanguageType().getName());
            saveToDatabase(context);

            // 6. Build bridge tables (common logic)
            log.info("[{}] Building bridge tables...", getLanguageType().getName());
            buildBridgeTables(context);

            // 7. Cleanup
            cleanup(context);

            context.setEndTime(System.currentTimeMillis());

            return AnalysisResult.builder()
                    .success(true)
                    .languageType(getLanguageType())
                    .projectDir(projectDir)
                    .uriCount(context.getTotalMethods())
                    .methodCount(context.getTotalMethods())
                    .callEdgeCount(context.getTotalCallEdges())
                    .httpBridgeCount(countRecords("http_call_bridge"))
                    .durationMs(context.getDurationMs())
                    .message("Analysis completed successfully")
                    .build();

        } catch (Exception e) {
            log.error("[{}] Analysis failed: {}", getLanguageType().getName(), e.getMessage(), e);
            return AnalysisResult.failure(e.getMessage());
        }
    }

    // ========== Abstract methods - subclass must implement ==========

    protected abstract LanguageType getLanguageType();

    protected abstract List<String> getSupportedExtensions();

    protected abstract List<Path> discoverFiles(String projectDir, AnalyzeContext context);

    protected abstract void parseAndExtract(List<Path> files, AnalyzeContext context);

    protected abstract void buildCallGraph(AnalyzeContext context);

    // ========== Hook methods - subclass can override ==========

    protected AnalyzeContext createContext(String projectDir) {
        return AnalyzeContext.builder()
                .projectDir(projectDir)
                .languageType(getLanguageType())
                .globalCache(globalCache)
                .build();
    }

    protected void beforeAnalyze(AnalyzeContext context) {
        // Default: clear cache
        globalCache.clearAll();
    }

    protected void cleanup(AnalyzeContext context) {
        // Default: no-op
    }

    // ========== Common logic - shared by all languages ==========

    protected void saveToDatabase(AnalyzeContext context) {
        // Subclasses will override with specific implementation
        // This is a placeholder for common database operations
    }

    protected void buildBridgeTables(AnalyzeContext context) {
        String projectDir = context.getProjectDir();

        // Clear existing data
        mqBridge.clearBridgeData(dataSource, projectDir);
        httpBridge.clearBridgeData(dataSource, projectDir);
        proxyBridge.clearBridgeData(dataSource, projectDir);

        // Build and save bridges
        var mqData = mqBridge.buildBridgeData(globalCache);
        mqBridge.saveBridgeData(mqData, dataSource, projectDir);
        context.setBridgeCount(mqData.size());

        var httpData = httpBridge.buildBridgeData(globalCache);
        httpBridge.saveBridgeData(httpData, dataSource, projectDir);
        context.setBridgeCount(context.getBridgeCount() + httpData.size());

        var proxyData = proxyBridge.buildBridgeData(globalCache);
        proxyBridge.saveBridgeData(proxyData, dataSource, projectDir);
        context.setBridgeCount(context.getBridgeCount() + proxyData.size());
    }

    protected int countRecords(String tableName) {
        try (Connection conn = dataSource.getConnection();
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery("SELECT COUNT(*) FROM " + tableName)) {
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            log.warn("Failed to count records in {}: {}", tableName, e.getMessage());
        }
        return 0;
    }
}
```

**Step 2: Commit**

```bash
git add src/main/java/com/huawei/hisi/analyzer/AbstractChainAnalyzer.java
git commit -m "feat: add AbstractChainAnalyzer base class with template method pattern"
```

---

### Task 5: Create LanguageAnalyzerRegistry

**Files:**
- Create: `src/main/java/com/huawei/hisi/analyzer/LanguageAnalyzerRegistry.java`

**Step 1: Write the registry class**

```java
package com.huawei.hisi.analyzer;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Registry for language-specific analyzers.
 * Detects project language and delegates to appropriate analyzer.
 */
@Slf4j
@Component
public class LanguageAnalyzerRegistry {

    private final Map<LanguageType, AbstractChainAnalyzer> analyzers = new HashMap<>();
    private final DataSource dataSource;

    public LanguageAnalyzerRegistry(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Register a language analyzer.
     */
    public void register(LanguageType type, AbstractChainAnalyzer analyzer) {
        analyzers.put(type, analyzer);
        log.info("Registered analyzer for language: {}", type.getName());
    }

    /**
     * Get analyzer for a specific language.
     */
    public AbstractChainAnalyzer getAnalyzer(LanguageType type) {
        return analyzers.get(type);
    }

    /**
     * Detect project language and return appropriate analyzer.
     * Detection logic:
     * 1. Check for pom.xml or build.gradle -> Java
     * 2. Check for requirements.txt, pyproject.toml, setup.py -> Python
     * 3. Check file extensions in source directories
     */
    public AbstractChainAnalyzer detectAndGetAnalyzer(String projectDir) {
        LanguageType detected = detectLanguage(projectDir);
        if (detected == null) {
            log.warn("Could not detect language for project: {}, defaulting to Java", projectDir);
            detected = LanguageType.JAVA;
        }
        log.info("Detected language {} for project: {}", detected.getName(), projectDir);
        return getAnalyzer(detected);
    }

    /**
     * Detect the primary language of a project.
     */
    public LanguageType detectLanguage(String projectDir) {
        Path root = Paths.get(projectDir);

        // Check for Java project markers
        if (Files.exists(root.resolve("pom.xml")) ||
            Files.exists(root.resolve("build.gradle")) ||
            Files.exists(root.resolve("build.gradle.kts"))) {
            return LanguageType.JAVA;
        }

        // Check for Python project markers
        if (Files.exists(root.resolve("requirements.txt")) ||
            Files.exists(root.resolve("pyproject.toml")) ||
            Files.exists(root.resolve("setup.py")) ||
            Files.exists(root.resolve("setup.cfg"))) {
            return LanguageType.PYTHON;
        }

        // Check source directory structure
        if (Files.exists(root.resolve("src/main/java"))) {
            return LanguageType.JAVA;
        }

        // Check for Python source patterns
        try {
            List<Path> pyFiles = Files.walk(root, 3)
                    .filter(p -> p.toString().endsWith(".py"))
                    .toList();
            List<Path> javaFiles = Files.walk(root, 3)
                    .filter(p -> p.toString().endsWith(".java"))
                    .toList();

            if (pyFiles.size() > javaFiles.size()) {
                return LanguageType.PYTHON;
            } else if (javaFiles.size() > 0) {
                return LanguageType.JAVA;
            }
        } catch (Exception e) {
            log.warn("Error detecting language by file scan: {}", e.getMessage());
        }

        return null;
    }
}
```

**Step 2: Commit**

```bash
git add src/main/java/com/huawei/hisi/analyzer/LanguageAnalyzerRegistry.java
git commit -m "feat: add LanguageAnalyzerRegistry for language detection and analyzer selection"
```

---

## Phase 2: Java Analyzer Migration (Zero Modification Strategy)

### Task 6: Create JavaChainAnalyzer (Delegate Pattern)

**Files:**
- Create: `src/main/java/com/huawei/hisi/analyzer/java/JavaChainAnalyzer.java`

**Step 1: Write the Java analyzer (delegates to existing code)**

```java
package com.huawei.hisi.analyzer.java;

import com.huawei.hisi.analyzer.AbstractChainAnalyzer;
import com.huawei.hisi.analyzer.AnalyzeContext;
import com.huawei.hisi.analyzer.LanguageType;
import com.huawei.hisi.bridge.HttpChainBridge;
import com.huawei.hisi.bridge.MQChainBridge;
import com.huawei.hisi.bridge.ProxyChainBridge;
import com.huawei.hisi.cache.GlobalAnalysisCache;
import com.huawei.hisi.scanner.FeignClientScanner;
import com.huawei.hisi.scanner.HttpCallScanner;
import com.huawei.hisi.scanner.MQEndpointScanner;
import com.huawei.hisi.scanner.ProxyClassScanner;
import lombok.extern.slf4j.Slf4j;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Java call chain analyzer.
 * Wraps existing Scanner and Bridge logic without modification.
 *
 * IMPORTANT: This class does NOT modify any existing Scanner/Bridge code.
 * It simply orchestrates the existing components.
 */
@Slf4j
public class JavaChainAnalyzer extends AbstractChainAnalyzer {

    // Existing scanners - used as-is
    private final MQEndpointScanner mqScanner;
    private final FeignClientScanner feignScanner;
    private final HttpCallScanner httpScanner;
    private final ProxyClassScanner proxyScanner;

    public JavaChainAnalyzer(DataSource dataSource) {
        super(dataSource);
        this.mqScanner = new MQEndpointScanner();
        this.feignScanner = new FeignClientScanner();
        this.httpScanner = new HttpCallScanner();
        this.proxyScanner = new ProxyClassScanner();
    }

    @Override
    protected LanguageType getLanguageType() {
        return LanguageType.JAVA;
    }

    @Override
    protected List<String> getSupportedExtensions() {
        return List.of(".java");
    }

    @Override
    protected List<Path> discoverFiles(String projectDir, AnalyzeContext context) {
        List<Path> javaFiles = new ArrayList<>();
        try {
            java.nio.file.Files.walkFileTree(java.nio.file.Paths.get(projectDir),
                new java.nio.file.SimpleFileVisitor<Path>() {
                    @Override
                    public java.nio.file.FileVisitResult visitFile(Path file,
                            java.nio.file.attribute.BasicFileAttributes attrs) {
                        if (file.toString().endsWith(".java")) {
                            javaFiles.add(file);
                        }
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }
                });
        } catch (Exception e) {
            log.error("Error discovering Java files: {}", e.getMessage());
        }
        return javaFiles;
    }

    @Override
    protected void parseAndExtract(List<Path> files, AnalyzeContext context) {
        // Use existing scanners - zero modification
        log.info("Running MQ endpoint scanner...");
        mqScanner.scanFiles(files, globalCache);

        log.info("Running Feign client scanner...");
        feignScanner.scanFiles(files, globalCache);

        log.info("Running HTTP call scanner...");
        httpScanner.scanFiles(files, globalCache);

        log.info("Running proxy class scanner...");
        proxyScanner.scanFiles(files, globalCache);
    }

    @Override
    protected void buildCallGraph(AnalyzeContext context) {
        // The actual call graph building is handled by HisiURIMethodChainToDBServiceImpl
        // This method is a placeholder for when we migrate that logic
        log.info("Java call graph building is handled by existing HisiURIMethodChainToDBServiceImpl");
    }

    @Override
    protected void cleanup(AnalyzeContext context) {
        super.cleanup(context);
        mqScanner.cleanup();
        feignScanner.cleanup();
        httpScanner.cleanup();
        proxyScanner.cleanup();
    }
}
```

**Step 2: Commit**

```bash
git add src/main/java/com/huawei/hisi/analyzer/java/JavaChainAnalyzer.java
git commit -m "feat: add JavaChainAnalyzer wrapping existing scanners (zero modification)"
```

---

## Phase 3: Python Analyzer

### Task 7: Add Tree-sitter Dependency

**Files:**
- Modify: `pom.xml`

**Step 1: Add Tree-sitter dependency**

Add to `<dependencies>` section:

```xml
<!-- Tree-sitter for multi-language parsing -->
<dependency>
    <groupId>io.github.tree-sitter</groupId>
    <artifactId>tree-sitter</artifactId>
    <version>0.22.6</version>
</dependency>
<dependency>
    <groupId>io.github.tree-sitter</groupId>
    <artifactId>tree-sitter-python</artifactId>
    <version>0.22.6</version>
</dependency>
```

**Step 2: Commit**

```bash
git add pom.xml
git commit -m "feat: add tree-sitter dependencies for Python parsing"
```

---

### Task 8: Create PythonASTParser

**Files:**
- Create: `src/main/java/com/huawei/hisi/analyzer/python/PythonASTParser.java`

**Step 1: Write the Python AST parser**

```java
package com.huawei.hisi.analyzer.python;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Python AST parser using regex-based extraction (lightweight approach).
 * For production, consider using Tree-sitter or a Python subprocess parser.
 */
@Slf4j
@Component
public class PythonASTParser {

    // Decorator patterns
    private static final Pattern FASTAPI_ROUTE_PATTERN = Pattern.compile(
        "@(app|router)\\.(get|post|put|delete|patch)\\s*\\(\\s*[\"']([^\"']+)[\"']\\s*\\)"
    );
    private static final Pattern FLASK_ROUTE_PATTERN = Pattern.compile(
        "@app\\.route\\s*\\(\\s*[\"']([^\"']+)[\"']\\s*(?:,\\s*methods\\s*=\\s*\\[([^\\]]+)\\])?\\s*\\)"
    );

    // Function definition pattern
    private static final Pattern FUNCTION_DEF_PATTERN = Pattern.compile(
        "def\\s+(\\w+)\\s*\\(([^)]*)\\)\\s*(?:->\\s*([^:]+))?\\s*:"
    );

    // Class definition pattern
    private static final Pattern CLASS_DEF_PATTERN = Pattern.compile(
        "class\\s+(\\w+)\\s*(?:\\([^)]*\\))?\\s*:"
    );

    // HTTP client call patterns
    private static final Pattern REQUESTS_PATTERN = Pattern.compile(
        "requests\\.(get|post|put|delete|patch)\\s*\\([^)]*\\)"
    );
    private static final Pattern HTTPX_PATTERN = Pattern.compile(
        "(?:httpx\\.)?(?:async\\.)?(client\\.)?(get|post|put|delete|patch)\\s*\\([^)]*\\)"
    );

    /**
     * Parse a Python file and extract module info.
     */
    public PythonModule parseFile(Path filePath) {
        PythonModule module = new PythonModule();
        module.setFilePath(filePath.toString());

        try {
            String content = Files.readString(filePath);

            // Extract module name from file path
            String fileName = filePath.getFileName().toString();
            module.setModuleName(fileName.replace(".py", ""));

            // Extract classes
            extractClasses(content, module);

            // Extract functions
            extractFunctions(content, module);

            // Extract FastAPI routes
            extractFastAPIRoutes(content, module);

            // Extract Flask routes
            extractFlaskRoutes(content, module);

            // Extract HTTP client calls
            extractHttpCalls(content, module);

        } catch (IOException e) {
            log.error("Error reading Python file {}: {}", filePath, e.getMessage());
        }

        return module;
    }

    private void extractClasses(String content, PythonModule module) {
        Matcher matcher = CLASS_DEF_PATTERN.matcher(content);
        while (matcher.find()) {
            PythonClass cls = new PythonClass();
            cls.setName(matcher.group(1));
            module.getClasses().add(cls);
        }
    }

    private void extractFunctions(String content, PythonModule module) {
        Matcher matcher = FUNCTION_DEF_PATTERN.matcher(content);
        int lastEnd = 0;
        while (matcher.find()) {
            PythonFunction func = new PythonFunction();
            func.setName(matcher.group(1));
            func.setParameters(matcher.group(2).trim());
            func.setReturnType(matcher.group(3) != null ? matcher.group(3).trim() : null);

            // Calculate line number
            String beforeFunction = content.substring(0, matcher.start());
            func.setLineNumber(beforeFunction.split("\n").length);

            module.getFunctions().add(func);
            lastEnd = matcher.end();
        }
    }

    private void extractFastAPIRoutes(String content, PythonModule module) {
        Matcher matcher = FASTAPI_ROUTE_PATTERN.matcher(content);
        while (matcher.find()) {
            PythonEndpoint endpoint = new PythonEndpoint();
            endpoint.setFramework("FastAPI");
            endpoint.setHttpMethod(matcher.group(2).toUpperCase());
            endpoint.setPath(matcher.group(3));
            module.getEndpoints().add(endpoint);
        }
    }

    private void extractFlaskRoutes(String content, PythonModule module) {
        Matcher matcher = FLASK_ROUTE_PATTERN.matcher(content);
        while (matcher.find()) {
            PythonEndpoint endpoint = new PythonEndpoint();
            endpoint.setFramework("Flask");
            endpoint.setPath(matcher.group(1));

            // Extract HTTP methods
            String methods = matcher.group(2);
            if (methods != null) {
                endpoint.setHttpMethod(methods.replaceAll("[\"'\\s]", "").toUpperCase());
            } else {
                endpoint.setHttpMethod("GET"); // Flask default
            }

            module.getEndpoints().add(endpoint);
        }
    }

    private void extractHttpCalls(String content, PythonModule module) {
        // Extract requests calls
        Matcher requestsMatcher = REQUESTS_PATTERN.matcher(content);
        while (requestsMatcher.find()) {
            PythonHttpCall call = new PythonHttpCall();
            call.setClientType("requests");
            call.setHttpMethod(requestsMatcher.group(1).toUpperCase());
            module.getHttpCalls().add(call);
        }

        // Extract httpx calls
        Matcher httpxMatcher = HTTPX_PATTERN.matcher(content);
        while (httpxMatcher.find()) {
            PythonHttpCall call = new PythonHttpCall();
            call.setClientType("httpx");
            call.setHttpMethod(httpxMatcher.group(2).toUpperCase());
            module.getHttpCalls().add(call);
        }
    }

    /**
     * Check if file is a Python file.
     */
    public boolean isPythonFile(Path path) {
        return path.toString().endsWith(".py");
    }

    // Inner classes for parsed elements
    @Data
    public static class PythonModule {
        private String filePath;
        private String moduleName;
        private List<PythonClass> classes = new ArrayList<>();
        private List<PythonFunction> functions = new ArrayList<>();
        private List<PythonEndpoint> endpoints = new ArrayList<>();
        private List<PythonHttpCall> httpCalls = new ArrayList<>();
    }

    @Data
    public static class PythonClass {
        private String name;
        private List<PythonFunction> methods = new ArrayList<>();
    }

    @Data
    public static class PythonFunction {
        private String name;
        private String parameters;
        private String returnType;
        private int lineNumber;
        private List<String> calls = new ArrayList<>();
    }

    @Data
    public static class PythonEndpoint {
        private String framework;
        private String httpMethod;
        private String path;
        private String handlerFunction;
    }

    @Data
    public static class PythonHttpCall {
        private String clientType;
        private String httpMethod;
        private String url;
        private String sourceFunction;
    }
}
```

**Step 2: Commit**

```bash
git add src/main/java/com/huawei/hisi/analyzer/python/PythonASTParser.java
git commit -m "feat: add PythonASTParser for Python code analysis"
```

---

### Task 9: Create FastAPIParser

**Files:**
- Create: `src/main/java/com/huawei/hisi/analyzer/python/FastAPIParser.java`

**Step 1: Write the FastAPI parser**

```java
package com.huawei.hisi.analyzer.python;

import com.huawei.hisi.analyzer.python.PythonASTParser.PythonModule;
import com.huawei.hisi.analyzer.python.PythonASTParser.PythonEndpoint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for FastAPI applications.
 * Extracts routes from @app.get(), @router.post() decorators.
 */
@Slf4j
@Component
public class FastAPIParser {

    // FastAPI route decorator pattern
    // Matches: @app.get("/path"), @router.post("/path"), @app.put("/path/{id}")
    private static final Pattern ROUTE_DECORATOR = Pattern.compile(
        "@(\\w+)\\.(get|post|put|delete|patch)\\s*\\(\\s*[\"']([^\"']+)[\"']\\s*\\)"
    );

    // Function definition after decorator
    private static final Pattern FUNCTION_AFTER_DECORATOR = Pattern.compile(
        "def\\s+(\\w+)\\s*\\("
    );

    // Router definition
    private static final Pattern ROUTER_DEF = Pattern.compile(
        "(\\w+)\\s*=\\s*APIRouter\\s*\\("
    );

    // Include router pattern
    private static final Pattern INCLUDE_ROUTER = Pattern.compile(
        "app\\.include_router\\s*\\(\\s*(\\w+)"
    );

    /**
     * Parse a FastAPI application file.
     */
    public List<FastAPIRoute> parseRoutes(Path filePath, String content) {
        List<FastAPIRoute> routes = new ArrayList<>();

        String[] lines = content.split("\n");
        String currentRouter = "app";

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];

            // Check for router definition
            Matcher routerMatcher = ROUTER_DEF.matcher(line);
            if (routerMatcher.find()) {
                currentRouter = routerMatcher.group(1);
                continue;
            }

            // Check for route decorator
            Matcher routeMatcher = ROUTE_DECORATOR.matcher(line);
            if (routeMatcher.find()) {
                FastAPIRoute route = new FastAPIRoute();
                route.setRouterName(routeMatcher.group(1));
                route.setHttpMethod(routeMatcher.group(2).toUpperCase());
                route.setPath(routeMatcher.group(3));
                route.setLineNumber(i + 1);

                // Look for function name on next line
                if (i + 1 < lines.length) {
                    Matcher funcMatcher = FUNCTION_AFTER_DECORATOR.matcher(lines[i + 1]);
                    if (funcMatcher.find()) {
                        route.setHandlerFunction(funcMatcher.group(1));
                    }
                }

                routes.add(route);
                log.debug("Found FastAPI route: {} {} -> {}", route.getHttpMethod(),
                    route.getPath(), route.getHandlerFunction());
            }
        }

        return routes;
    }

    /**
     * FastAPI route model.
     */
    @lombok.Data
    public static class FastAPIRoute {
        private String routerName;
        private String httpMethod;
        private String path;
        private String handlerFunction;
        private int lineNumber;
        private String moduleName;
        private String fullPath; // Including router prefix if any
    }
}
```

**Step 2: Commit**

```bash
git add src/main/java/com/huawei/hisi/analyzer/python/FastAPIParser.java
git commit -m "feat: add FastAPIParser for FastAPI route extraction"
```

---

### Task 10: Create PythonChainAnalyzer

**Files:**
- Create: `src/main/java/com/huawei/hisi/analyzer/python/PythonChainAnalyzer.java`

**Step 1: Write the Python analyzer**

```java
package com.huawei.hisi.analyzer.python;

import com.huawei.hisi.analyzer.AbstractChainAnalyzer;
import com.huawei.hisi.analyzer.AnalyzeContext;
import com.huawei.hisi.analyzer.LanguageType;
import com.huawei.hisi.analyzer.python.PythonASTParser.PythonModule;
import com.huawei.hisi.analyzer.python.PythonASTParser.PythonEndpoint;
import com.huawei.hisi.analyzer.python.PythonASTParser.PythonHttpCall;
import com.huawei.hisi.cache.GlobalAnalysisCache;
import lombok.extern.slf4j.Slf4j;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Python call chain analyzer.
 * Supports FastAPI, Flask, and Django frameworks.
 */
@Slf4j
public class PythonChainAnalyzer extends AbstractChainAnalyzer {

    private final PythonASTParser astParser;
    private final FastAPIParser fastAPIParser;

    public PythonChainAnalyzer(DataSource dataSource) {
        super(dataSource);
        this.astParser = new PythonASTParser();
        this.fastAPIParser = new FastAPIParser();
    }

    @Override
    protected LanguageType getLanguageType() {
        return LanguageType.PYTHON;
    }

    @Override
    protected List<String> getSupportedExtensions() {
        return List.of(".py");
    }

    @Override
    protected List<Path> discoverFiles(String projectDir, AnalyzeContext context) {
        List<Path> pythonFiles = new ArrayList<>();
        try {
            Files.walkFileTree(Paths.get(projectDir), new java.nio.file.SimpleFileVisitor<Path>() {
                @Override
                public java.nio.file.FileVisitResult visitFile(Path file,
                        java.nio.file.attribute.BasicFileAttributes attrs) {
                    if (file.toString().endsWith(".py") &&
                        !file.toString().contains("__pycache__") &&
                        !file.toString().contains(".venv") &&
                        !file.toString().contains("site-packages")) {
                        pythonFiles.add(file);
                    }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (Exception e) {
            log.error("Error discovering Python files: {}", e.getMessage());
        }
        return pythonFiles;
    }

    @Override
    protected void parseAndExtract(List<Path> files, AnalyzeContext context) {
        int endpointCount = 0;
        int httpCallCount = 0;

        for (Path file : files) {
            PythonModule module = astParser.parseFile(file);

            // Extract endpoints to global cache
            for (PythonEndpoint endpoint : module.getEndpoints()) {
                String uri = endpoint.getPath();
                String method = module.getModuleName() + "." + endpoint.getHandlerFunction();

                globalCache.getUriMap().put(uri, null); // Placeholder for method declaration
                endpointCount++;

                log.debug("Found Python endpoint: {} {} in {}", endpoint.getHttpMethod(),
                    uri, method);
            }

            // Extract HTTP calls for bridge
            for (PythonHttpCall call : module.getHttpCalls()) {
                String key = module.getModuleName() + "|" + call.getHttpMethod();
                globalCache.getRestEndpointMap().put(key, call.getSourceFunction());
                httpCallCount++;
            }
        }

        context.setTotalMethods(endpointCount);
        log.info("Found {} Python endpoints and {} HTTP calls", endpointCount, httpCallCount);
    }

    @Override
    protected void buildCallGraph(AnalyzeContext context) {
        // Build Python call graph
        // For now, store endpoint information
        log.info("Building Python call graph for {} endpoints", context.getTotalMethods());
    }

    @Override
    protected void saveToDatabase(AnalyzeContext context) {
        // Save to method_call_graph5 table
        String sql = "INSERT INTO method_call_graph5 " +
                "(root_uri, parent_method, child_method, depth, method_body, package) " +
                "VALUES (?, ?, ?, ?, ?, ?)";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            // Save Python endpoints
            for (var entry : globalCache.getUriMap().entrySet()) {
                String uri = entry.getKey();
                pstmt.setString(1, uri);
                pstmt.setString(2, "PYTHON_ENDPOINT");
                pstmt.setString(3, "HANDLER");
                pstmt.setInt(4, 1);
                pstmt.setString(5, "");
                pstmt.setString(6, context.getProjectDir());
                pstmt.addBatch();
            }

            pstmt.executeBatch();
            log.info("Saved {} Python endpoints to database", globalCache.getUriMap().size());

        } catch (SQLException e) {
            log.error("Error saving Python call graph to database: {}", e.getMessage());
        }
    }
}
```

**Step 2: Commit**

```bash
git add src/main/java/com/huawei/hisi/analyzer/python/PythonChainAnalyzer.java
git commit -m "feat: add PythonChainAnalyzer for Python call chain analysis"
```

---

## Phase 4: Frontend Enhancement - Inline Expansion

### Task 11: Add Expand External Call API Endpoint

**Files:**
- Modify: `src/main/java/com/huawei/hisi/controller/CallChainController.java`
- Modify: `src/main/java/com/huawei/hisi/service/CallChainService.java`
- Modify: `src/main/java/com/huawei/hisi/service/CallChainServiceImpl.java`

**Step 1: Add interface method to CallChainService**

Add to `CallChainService.java`:

```java
/**
 * Expand external call to get target service call chain.
 * @param sourceMethod The method making the external call
 * @param bridgeType The bridge type (HTTP/MQ)
 * @return The external call chain with bridge info
 */
Map<String, Object> expandExternalCall(String sourceMethod, String bridgeType);
```

**Step 2: Implement in CallChainServiceImpl**

Add to `CallChainServiceImpl.java`:

```java
@Override
public Map<String, Object> expandExternalCall(String sourceMethod, String bridgeType) {
    Map<String, Object> result = new HashMap<>();

    if ("HTTP".equalsIgnoreCase(bridgeType)) {
        // Find HTTP bridge
        List<Map<String, Object>> bridges = findHttpBridge(sourceMethod);
        if (!bridges.isEmpty()) {
            Map<String, Object> bridge = bridges.get(0);
            result.put("bridgeInfo", bridge);

            // Get target service call chain
            String targetUri = (String) bridge.get("uriPattern");
            if (targetUri != null) {
                List<Map<String, Object>> targetChain = getCallChain(targetUri, null);
                result.put("externalCallChain", targetChain);
            }
        }
    } else if ("MQ".equalsIgnoreCase(bridgeType)) {
        List<Map<String, Object>> bridges = findMQBridge(sourceMethod);
        if (!bridges.isEmpty()) {
            Map<String, Object> bridge = bridges.get(0);
            result.put("bridgeInfo", bridge);
            // MQ target chain would need topic-based lookup
        }
    }

    result.put("sourceMethod", sourceMethod);
    result.put("bridgeType", bridgeType);
    return result;
}
```

**Step 3: Add controller endpoint**

Add to `CallChainController.java`:

```java
/**
 * Expand external call inline
 * GET /api/callchain/expand-external?method={method}&type={bridgeType}
 */
@GetMapping("/expand-external")
public ApiResponse<Map<String, Object>> expandExternalCall(
        @RequestParam String method,
        @RequestParam(defaultValue = "HTTP") String type) {
    Map<String, Object> result = callChainService.expandExternalCall(method, type);
    return ApiResponse.success(result);
}
```

**Step 4: Commit**

```bash
git add src/main/java/com/huawei/hisi/controller/CallChainController.java
git add src/main/java/com/huawei/hisi/service/CallChainService.java
git add src/main/java/com/huawei/hisi/service/CallChainServiceImpl.java
git commit -m "feat: add expand-external API for inline expansion of external calls"
```

---

### Task 12: Update getCrossServiceCallChain to Include Expandable Flag

**Files:**
- Modify: `src/main/java/com/huawei/hisi/service/CallChainServiceImpl.java`

**Step 1: Add expandable flag to cross-service call chain**

Modify `getCrossServiceCallChain` method to add `expandable: true` flag:

```java
@Override
public List<Map<String, Object>> getCrossServiceCallChain(String uri, boolean includeCrossService) {
    List<Map<String, Object>> callChain = getCallChain(uri, null);

    if (!includeCrossService) {
        return callChain;
    }

    for (Map<String, Object> node : callChain) {
        String childMethod = (String) node.get("childMethod");
        if (childMethod == null) continue;

        // Check for HTTP bridge
        List<Map<String, Object>> httpBridge = findHttpBridge(childMethod);
        if (!httpBridge.isEmpty()) {
            node.put("httpBridge", httpBridge);
            node.put("crossServiceType", "HTTP");
            node.put("expandable", true); // NEW: flag for frontend
        }

        // Check for MQ bridge
        List<Map<String, Object>> mqBridge = findMQBridge(childMethod);
        if (!mqBridge.isEmpty()) {
            node.put("mqBridge", mqBridge);
            node.put("crossServiceType", "MQ");
            node.put("expandable", true); // NEW: flag for frontend
        }
    }

    return callChain;
}
```

**Step 2: Commit**

```bash
git add src/main/java/com/huawei/hisi/service/CallChainServiceImpl.java
git commit -m "feat: add expandable flag to cross-service call chain nodes"
```

---

## Phase 5: Configuration and Registration

### Task 13: Create Analyzer Configuration

**Files:**
- Create: `src/main/java/com/huawei/hisi/config/AnalyzerConfig.java`

**Step 1: Write configuration class**

```java
package com.huawei.hisi.config;

import com.huawei.hisi.analyzer.AbstractChainAnalyzer;
import com.huawei.hisi.analyzer.LanguageAnalyzerRegistry;
import com.huawei.hisi.analyzer.LanguageType;
import com.huawei.hisi.analyzer.java.JavaChainAnalyzer;
import com.huawei.hisi.analyzer.python.PythonChainAnalyzer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Configuration for language analyzers.
 */
@Configuration
public class AnalyzerConfig {

    @Bean
    public JavaChainAnalyzer javaChainAnalyzer(DataSource dataSource) {
        return new JavaChainAnalyzer(dataSource);
    }

    @Bean
    public PythonChainAnalyzer pythonChainAnalyzer(DataSource dataSource) {
        return new PythonChainAnalyzer(dataSource);
    }

    @Bean
    public LanguageAnalyzerRegistry languageAnalyzerRegistry(
            DataSource dataSource,
            JavaChainAnalyzer javaAnalyzer,
            PythonChainAnalyzer pythonAnalyzer) {

        LanguageAnalyzerRegistry registry = new LanguageAnalyzerRegistry(dataSource);
        registry.register(LanguageType.JAVA, javaAnalyzer);
        registry.register(LanguageType.PYTHON, pythonAnalyzer);
        return registry;
    }
}
```

**Step 2: Commit**

```bash
git add src/main/java/com/huawei/hisi/config/AnalyzerConfig.java
git commit -m "feat: add AnalyzerConfig for analyzer bean registration"
```

---

### Task 14: Add Python Chain Generator Endpoint

**Files:**
- Modify: `src/main/java/com/huawei/hisi/controller/HisiURIMethodChainToDBController.java`

**Step 1: Add Python endpoint**

Add to controller:

```java
@Autowired
private LanguageAnalyzerRegistry analyzerRegistry;

/**
 * Generate Python call chain
 * GET /api/method_chain/generate-python?projectDir={projectDir}
 */
@GetMapping("/generate-python")
public ResponseEntity<String> generatePythonChain(
        @RequestParam(required = false) String projectDir) throws Exception {

    if (projectDir == null || projectDir.isEmpty()) {
        projectDir = appConfigService.getProjectDir();
    }

    AbstractChainAnalyzer analyzer = analyzerRegistry.getAnalyzer(LanguageType.PYTHON);
    if (analyzer == null) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("Python analyzer not registered");
    }

    AnalysisResult result = analyzer.analyze(projectDir);

    if (result.isSuccess()) {
        return ResponseEntity.ok("Python analysis completed: " + result.getMessage());
    } else {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("Analysis failed: " + result.getError());
    }
}
```

**Step 2: Add imports**

```java
import com.huawei.hisi.analyzer.AbstractChainAnalyzer;
import com.huawei.hisi.analyzer.LanguageAnalyzerRegistry;
import com.huawei.hisi.analyzer.LanguageType;
import com.huawei.hisi.analyzer.AnalysisResult;
```

**Step 3: Commit**

```bash
git add src/main/java/com/huawei/hisi/controller/HisiURIMethodChainToDBController.java
git commit -m "feat: add generate-python endpoint for Python call chain analysis"
```

---

## Phase 6: Testing

### Task 15: Write Unit Tests for LanguageType

**Files:**
- Create: `src/test/java/com/huawei/hisi/analyzer/LanguageTypeTest.java`

**Step 1: Write test**

```java
package com.huawei.hisi.analyzer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("LanguageType Tests")
class LanguageTypeTest {

    @Test
    @DisplayName("Test fromExtension with .java")
    void testFromExtensionJava() {
        assertEquals(LanguageType.JAVA, LanguageType.fromExtension(".java"));
    }

    @Test
    @DisplayName("Test fromExtension with .py")
    void testFromExtensionPython() {
        assertEquals(LanguageType.PYTHON, LanguageType.fromExtension(".py"));
    }

    @Test
    @DisplayName("Test fromExtension with unknown extension")
    void testFromExtensionUnknown() {
        assertNull(LanguageType.fromExtension(".go"));
    }

    @Test
    @DisplayName("Test fromExtension case insensitive")
    void testFromExtensionCaseInsensitive() {
        assertEquals(LanguageType.JAVA, LanguageType.fromExtension(".JAVA"));
        assertEquals(LanguageType.PYTHON, LanguageType.fromExtension(".PY"));
    }

    @Test
    @DisplayName("Test getName and getExtension")
    void testGetters() {
        assertEquals("java", LanguageType.JAVA.getName());
        assertEquals(".java", LanguageType.JAVA.getExtension());
        assertEquals("python", LanguageType.PYTHON.getName());
        assertEquals(".py", LanguageType.PYTHON.getExtension());
    }
}
```

**Step 2: Run test**

```bash
mvn test -Dtest=LanguageTypeTest -q
```

**Step 3: Commit**

```bash
git add src/test/java/com/huawei/hisi/analyzer/LanguageTypeTest.java
git commit -m "test: add unit tests for LanguageType enum"
```

---

### Task 16: Write Unit Tests for PythonASTParser

**Files:**
- Create: `src/test/java/com/huawei/hisi/analyzer/python/PythonASTParserTest.java`

**Step 1: Write test**

```java
package com.huawei.hisi.analyzer.python;

import com.huawei.hisi.analyzer.python.PythonASTParser.PythonModule;
import com.huawei.hisi.analyzer.python.PythonASTParser.PythonEndpoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PythonASTParser Tests")
class PythonASTParserTest {

    private PythonASTParser parser;

    @BeforeEach
    void setUp() {
        parser = new PythonASTParser();
    }

    @Test
    @DisplayName("Test isPythonFile with .py extension")
    void testIsPythonFile() {
        assertTrue(parser.isPythonFile(Path.of("test.py")));
        assertTrue(parser.isPythonFile(Path.of("/path/to/module.py")));
        assertFalse(parser.isPythonFile(Path.of("test.java")));
        assertFalse(parser.isPythonFile(Path.of("test.txt")));
    }

    @Test
    @DisplayName("Test parse FastAPI route")
    void testParseFastAPIRoute() {
        String content = """
            from fastapi import FastAPI
            app = FastAPI()

            @app.get("/users")
            def get_users():
                return []

            @app.post("/users")
            def create_user():
                return {}
            """;

        List<FastAPIParser.FastAPIRoute> routes =
            new FastAPIParser().parseRoutes(Path.of("test.py"), content);

        assertEquals(2, routes.size());

        FastAPIParser.FastAPIRoute getRoute = routes.get(0);
        assertEquals("GET", getRoute.getHttpMethod());
        assertEquals("/users", getRoute.getPath());
        assertEquals("get_users", getRoute.getHandlerFunction());

        FastAPIParser.FastAPIRoute postRoute = routes.get(1);
        assertEquals("POST", postRoute.getHttpMethod());
        assertEquals("create_user", postRoute.getHandlerFunction());
    }

    @Test
    @DisplayName("Test parse class definition")
    void testParseClass() {
        String content = """
            class UserService:
                def get_user(self, id):
                    pass

            class OrderService:
                pass
            """;

        PythonModule module = parser.parseModuleContent(content, "test.py");
        assertEquals(2, module.getClasses().size());
        assertEquals("UserService", module.getClasses().get(0).getName());
        assertEquals("OrderService", module.getClasses().get(1).getName());
    }

    @Test
    @DisplayName("Test parse function definition")
    void testParseFunction() {
        String content = """
            def hello(name: str) -> str:
                return f"Hello {name}"

            def add(a, b):
                return a + b
            """;

        PythonModule module = parser.parseModuleContent(content, "test.py");
        assertEquals(2, module.getFunctions().size());

        assertEquals("hello", module.getFunctions().get(0).getName());
        assertEquals("name: str", module.getFunctions().get(0).getParameters());
        assertEquals("str", module.getFunctions().get(0).getReturnType());
    }
}
```

**Note:** Add helper method `parseModuleContent` to PythonASTParser if needed.

**Step 2: Commit**

```bash
git add src/test/java/com/huawei/hisi/analyzer/python/PythonASTParserTest.java
git commit -m "test: add unit tests for PythonASTParser"
```

---

### Task 17: Write Integration Test for Expand External Call

**Files:**
- Create: `src/test/java/com/huawei/hisi/service/ExpandExternalCallTest.java`

**Step 1: Write integration test**

```java
package com.huawei.hisi.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Expand External Call Integration Tests")
class ExpandExternalCallTest {

    @Autowired
    private CallChainService callChainService;

    @Test
    @DisplayName("Test expand HTTP external call")
    void testExpandHttpCall() {
        // This test requires test data in http_call_bridge table
        Map<String, Object> result = callChainService.expandExternalCall(
            "com.example.Service.callExternalApi", "HTTP");

        assertNotNull(result);
        assertTrue(result.containsKey("bridgeInfo") || result.containsKey("sourceMethod"));
    }

    @Test
    @DisplayName("Test expand with unknown method")
    void testExpandUnknownMethod() {
        Map<String, Object> result = callChainService.expandExternalCall(
            "unknown.method", "HTTP");

        assertNotNull(result);
        assertEquals("unknown.method", result.get("sourceMethod"));
        // Should not have externalCallChain for unknown method
        assertFalse(result.containsKey("externalCallChain") ||
            result.get("externalCallChain") == null);
    }
}
```

**Step 2: Commit**

```bash
git add src/test/java/com/huawei/hisi/service/ExpandExternalCallTest.java
git commit -m "test: add integration tests for expand external call feature"
```

---

## Phase 7: Run All Tests and Verify

### Task 18: Run Full Test Suite

**Step 1: Run all tests**

```bash
cd hisi-dev-tool
mvn clean test
```

**Expected:** All 928+ existing tests pass + new tests pass

**Step 2: Verify Java functionality unchanged**

```bash
mvn test -Dtest=CallChainServiceImplTest -q
```

**Step 3: Run new tests**

```bash
mvn test -Dtest="LanguageTypeTest,PythonASTParserTest,ExpandExternalCallTest" -q
```

---

## Summary

| Phase | Tasks | Key Deliverables |
|-------|-------|------------------|
| 1. Core Infrastructure | 1-5 | LanguageType, AnalyzeContext, AbstractChainAnalyzer, Registry |
| 2. Java Migration | 6 | JavaChainAnalyzer (zero modification) |
| 3. Python Analyzer | 7-10 | Tree-sitter, PythonASTParser, FastAPIParser, PythonChainAnalyzer |
| 4. Frontend Enhancement | 11-12 | Expand external call API, expandable flag |
| 5. Configuration | 13-14 | AnalyzerConfig, Python generation endpoint |
| 6. Testing | 15-17 | Unit tests and integration tests |
| 7. Verification | 18 | Full test suite passes |

**Total Tasks:** 18

**Estimated Effort:** 3 weeks

---

Plan complete and saved to `docs/plans/2026-04-06-python-call-chain-implementation.md`. Two execution options:

**1. Subagent-Driven (this session)** - I dispatch fresh subagent per task, review between tasks, fast iteration

**2. Parallel Session (separate)** - Open new session with executing-plans, batch execution with checkpoints

**Which approach?**