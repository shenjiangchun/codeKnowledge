# Log Root Cause Analysis System Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 实现日志分析根因定位系统，支持应用切换、定时日志采集、指纹去重、向量相似度检测、LLM分析流水线。

**Architecture:** 指纹预筛 + Neo4j向量精排 + LLM高疑确认混合架构。SQLite存指纹索引(O(1))，Neo4j存向量索引(复用现有VECTOR INDEX)，LLM仅处理高疑样本。

**Tech Stack:** Spring Boot 3.2 + SQLite + Neo4j 5.11+ + EmbeddingService(智谱embedding-3) + Vue3 + Element Plus

---

## Phase 1: SQLite Schema Extension (Backend)

### Task 1: Extend log_analysis_report Table

**Files:**
- Modify: `hisi-dev-tool/src/main/java/com/huawei/hisi/config/SQLiteSchemaInitializer.java`
- Modify: `hisi-dev-tool/src/main/java/com/huawei/hisi/repository/LogAnalysisRepository.java`
- Test: `hisi-dev-tool/src/test/java/com/huawei/hisi/repository/LogAnalysisRepositoryTest.java`

**Step 1: Write the failing test for new fields**

```java
// hisi-dev-tool/src/test/java/com/huawei/hisi/repository/LogAnalysisRepositoryTest.java
@Test
void testSaveReportWithFingerprint() {
    LogAnalysisReportEntity report = new LogAnalysisReportEntity();
    report.setReportId(12345L);
    report.setErrorFingerprint("abc123def456");
    report.setAnalysisStatus("pending");
    report.setOccurrenceCount(1);
    report.setSimilarityThreshold(0.85);
    
    repository.save(report);
    
    LogAnalysisReportEntity found = repository.findById(12345L);
    assertThat(found.getErrorFingerprint()).isEqualTo("abc123def456");
    assertThat(found.getOccurrenceCount()).isEqualTo(1);
}
```

**Step 2: Run test to verify it fails**

Run: `cd hisi-dev-tool && mvn test -Dtest=LogAnalysisRepositoryTest#testSaveReportWithFingerprint -v`
Expected: FAIL with "column error_fingerprint not found" or similar

**Step 3: Add columns to SQLite schema**

```java
// hisi-dev-tool/src/main/java/com/huawei/hisi/config/SQLiteSchemaInitializer.java
// Add to createTablesIfNotExist() method, after existing log_analysis_report columns:

private static final String ALTER_LOG_ANALYSIS_REPORT = """
    ALTER TABLE log_analysis_report ADD COLUMN error_fingerprint TEXT NOT NULL DEFAULT '';
    ALTER TABLE log_analysis_report ADD COLUMN embedding_id TEXT;
    ALTER TABLE log_analysis_report ADD COLUMN similarity_threshold REAL DEFAULT 0.85;
    ALTER TABLE log_analysis_report ADD COLUMN analysis_status VARCHAR(20) DEFAULT 'pending';
    ALTER TABLE log_analysis_report ADD COLUMN occurrence_count INTEGER DEFAULT 1;
    ALTER TABLE log_analysis_report ADD COLUMN root_cause TEXT;
    ALTER TABLE log_analysis_report ADD COLUMN fix_suggestion TEXT;
    """;
```

**Step 4: Update entity class**

```java
// hisi-dev-tool/src/main/java/com/huawei/hisi/repository/LogAnalysisRepository.java
// Add fields to LogAnalysisReportEntity inner class:

private String errorFingerprint;
private String embeddingId;
private Double similarityThreshold;
private String analysisStatus;
private Integer occurrenceCount;
private String rootCause;
private String fixSuggestion;

// Add getters and setters
public String getErrorFingerprint() { return errorFingerprint; }
public void setErrorFingerprint(String errorFingerprint) { this.errorFingerprint = errorFingerprint; }
public String getEmbeddingId() { return embeddingId; }
public void setEmbeddingId(String embeddingId) { this.embeddingId = embeddingId; }
// ... etc for other fields
```

**Step 5: Update RowMapper**

```java
// hisi-dev-tool/src/main/java/com/huawei/hisi/repository/LogAnalysisRepository.java
// Add to LogAnalysisReportRowMapper.mapRow():

report.setErrorFingerprint(rs.getString("error_fingerprint"));
report.setEmbeddingId(rs.getString("embedding_id"));
report.setSimilarityThreshold(rs.getDouble("similarity_threshold"));
report.setAnalysisStatus(rs.getString("analysis_status"));
report.setOccurrenceCount(rs.getInt("occurrence_count"));
report.setRootCause(rs.getString("root_cause"));
report.setFixSuggestion(rs.getString("fix_suggestion"));
```

**Step 6: Run test to verify it passes**

Run: `cd hisi-dev-tool && mvn test -Dtest=LogAnalysisRepositoryTest#testSaveReportWithFingerprint -v`
Expected: PASS

**Step 7: Commit**

```bash
git add hisi-dev-tool/src/main/java/com/huawei/hisi/config/SQLiteSchemaInitializer.java
git add hisi-dev-tool/src/main/java/com/huawei/hisi/repository/LogAnalysisRepository.java
git add hisi-dev-tool/src/test/java/com/huawei/hisi/repository/LogAnalysisRepositoryTest.java
git commit -m "feat(db): extend log_analysis_report with fingerprint and analysis fields"
```

---

### Task 2: Create log_error_embedding_map Table

**Files:**
- Modify: `hisi-dev-tool/src/main/java/com/huawei/hisi/config/SQLiteSchemaInitializer.java`
- Create: `hisi-dev-tool/src/main/java/com/huawei/hisi/repository/ErrorEmbeddingMapRepository.java`
- Test: `hisi-dev-tool/src/test/java/com/huawei/hisi/repository/ErrorEmbeddingMapRepositoryTest.java`

**Step 1: Write the failing test**

```java
// hisi-dev-tool/src/test/java/com/huawei/hisi/repository/ErrorEmbeddingMapRepositoryTest.java
@Test
void testSaveEmbeddingMap() {
    ErrorEmbeddingMapEntity map = new ErrorEmbeddingMapEntity();
    map.setReportId(12345L);
    map.setEmbeddingId("neo4j-node-abc");
    map.setSimilarityScore(0.92);
    map.setMatchedReportId(67890L);
    
    repository.save(map);
    
    List<ErrorEmbeddingMapEntity> found = repository.findByReportId(12345L);
    assertThat(found).hasSize(1);
    assertThat(found.get(0).getSimilarityScore()).isEqualTo(0.92);
}
```

**Step 2: Run test to verify it fails**

Run: `cd hisi-dev-tool && mvn test -Dtest=ErrorEmbeddingMapRepositoryTest -v`
Expected: FAIL with "table log_error_embedding_map not found"

**Step 3: Create table schema**

```java
// hisi-dev-tool/src/main/java/com/huawei/hisi/config/SQLiteSchemaInitializer.java
// Add to createTablesIfNotExist():

private static final String CREATE_ERROR_EMBEDDING_MAP = """
    CREATE TABLE IF NOT EXISTS log_error_embedding_map (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        report_id INTEGER NOT NULL,
        embedding_id TEXT NOT NULL,
        similarity_score REAL NOT NULL,
        matched_report_id INTEGER,
        created_at INTEGER DEFAULT (strftime('%s','now'))
    )
    """;
```

**Step 4: Create repository**

```java
// hisi-dev-tool/src/main/java/com/huawei/hisi/repository/ErrorEmbeddingMapRepository.java
package com.huawei.hisi.repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

@Slf4j
@Repository
@RequiredArgsConstructor
public class ErrorEmbeddingMapRepository {

    private final JdbcTemplate jdbcTemplate;

    public void save(ErrorEmbeddingMapEntity entity) {
        String sql = """
            INSERT INTO log_error_embedding_map (report_id, embedding_id, similarity_score, matched_report_id)
            VALUES (?, ?, ?, ?)
            """;
        jdbcTemplate.update(sql, entity.getReportId(), entity.getEmbeddingId(),
            entity.getSimilarityScore(), entity.getMatchedReportId());
    }

    public List<ErrorEmbeddingMapEntity> findByReportId(Long reportId) {
        String sql = "SELECT * FROM log_error_embedding_map WHERE report_id = ?";
        return jdbcTemplate.query(sql, new ErrorEmbeddingMapRowMapper(), reportId);
    }

    // Inner classes for entity and row mapper...
}
```

**Step 5: Run test to verify it passes**

Run: `cd hisi-dev-tool && mvn test -Dtest=ErrorEmbeddingMapRepositoryTest -v`
Expected: PASS

**Step 6: Commit**

```bash
git add hisi-dev-tool/src/main/java/com/huawei/hisi/config/SQLiteSchemaInitializer.java
git add hisi-dev-tool/src/main/java/com/huawei/hisi/repository/ErrorEmbeddingMapRepository.java
git add hisi-dev-tool/src/test/java/com/huawei/hisi/repository/ErrorEmbeddingMapRepositoryTest.java
git commit -m "feat(db): add log_error_embedding_map table for vector similarity tracking"
```

---

## Phase 2: Fingerprint Service (Backend)

### Task 3: Create FingerprintService

**Files:**
- Create: `hisi-dev-tool/src/main/java/com/huawei/hisi/service/FingerprintService.java`
- Test: `hisi-dev-tool/src/test/java/com/huawei/hisi/service/FingerprintServiceTest.java`

**Step 1: Write the failing test**

```java
// hisi-dev-tool/src/test/java/com/huawei/hisi/service/FingerprintServiceTest.java
@Test
void testGenerateFingerprintFromStackTrace() {
    String stackTrace = """
        java.lang.NullPointerException: Cannot invoke method on null object
            at com.example.service.UserService.getUserProfile(UserService.java:45)
            at com.example.controller.UserController.handleRequest(UserController.java:120)
            at org.springframework.web.servlet.FrameworkServlet.service(FrameworkServlet.java:897)
        """;
    
    String fingerprint = fingerprintService.generateFingerprint(stackTrace);
    
    // Same stack trace should produce same fingerprint
    String fingerprint2 = fingerprintService.generateFingerprint(stackTrace);
    assertThat(fingerprint).isEqualTo(fingerprint2);
    
    // Fingerprint should be 32-char MD5
    assertThat(fingerprint).hasSize(32);
}

@Test
void testFingerprintExcludesLineNumbers() {
    String stackTrace1 = "at com.example.service.UserService.getUserProfile(UserService.java:45)";
    String stackTrace2 = "at com.example.service.UserService.getUserProfile(UserService.java:50)";
    
    // Same method signature, different line numbers → same fingerprint
    assertThat(fingerprintService.generateFingerprint(stackTrace1))
        .isEqualTo(fingerprintService.generateFingerprint(stackTrace2));
}
```

**Step 2: Run test to verify it fails**

Run: `cd hisi-dev-tool && mvn test -Dtest=FingerprintServiceTest -v`
Expected: FAIL with "FingerprintService not found"

**Step 3: Implement FingerprintService**

```java
// hisi-dev-tool/src/main/java/com/huawei/hisi/service/FingerprintService.java
package com.huawei.hisi.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 日志指纹服务
 * 生成确定性指纹用于快速去重
 */
@Slf4j
@Service
public class FingerprintService {

    // Stack frame pattern: at package.Class.method(File.java:line)
    private static final Pattern STACK_FRAME_PATTERN = 
        Pattern.compile("at\\s+([\\w.]+)\\.([\\w]+)\\([\\w.]+:\\d+\\)");
    
    // Error type pattern: java.lang.ExceptionType: message
    private static final Pattern ERROR_TYPE_PATTERN = 
        Pattern.compile("^([\\w.]+Exception|[\\w.]+Error):");

    /**
     * Generate deterministic fingerprint from log content
     * Components: errorType + top3 stack frames (without line numbers)
     */
    public String generateFingerprint(String logContent) {
        if (logContent == null || logContent.isEmpty()) {
            return "00000000000000000000000000000000";
        }
        
        StringBuilder normalized = new StringBuilder();
        
        // Extract error type
        Matcher errorMatcher = ERROR_TYPE_PATTERN.matcher(logContent);
        if (errorMatcher.find()) {
            normalized.append(errorMatcher.group(1)).append("|");
        }
        
        // Extract top 3 stack frames (class + method only, no line numbers)
        Matcher frameMatcher = STACK_FRAME_PATTERN.matcher(logContent);
        int frameCount = 0;
        while (frameMatcher.find() && frameCount < 3) {
            String className = frameMatcher.group(1);
            String methodName = frameMatcher.group(2);
            // Exclude framework classes
            if (!isFrameworkClass(className)) {
                normalized.append(className).append(".").append(methodName).append("|");
                frameCount++;
            }
        }
        
        // Generate MD5 hash
        return md5Hash(normalized.toString());
    }
    
    private boolean isFrameworkClass(String className) {
        return className.startsWith("java.") 
            || className.startsWith("javax.")
            || className.startsWith("org.springframework.")
            || className.startsWith("org.apache.")
            || className.startsWith("sun.");
    }
    
    private String md5Hash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("MD5 hash failed", e);
            return "00000000000000000000000000000000";
        }
    }
}
```

**Step 4: Run test to verify it passes**

Run: `cd hisi-dev-tool && mvn test -Dtest=FingerprintServiceTest -v`
Expected: PASS

**Step 5: Commit**

```bash
git add hisi-dev-tool/src/main/java/com/huawei/hisi/service/FingerprintService.java
git add hisi-dev-tool/src/test/java/com/huawei/hisi/service/FingerprintServiceTest.java
git commit -m "feat(core): add FingerprintService for log deduplication"
```

---

### Task 4: Implement Fingerprint Deduplication in Log Analysis

**Files:**
- Modify: `hisi-dev-tool/src/main/java/com/huawei/hisi/service/LogAnalysisExecutor.java`
- Modify: `hisi-dev-tool/src/main/java/com/huawei/hisi/repository/LogAnalysisRepository.java`
- Test: `hisi-dev-tool/src/test/java/com/huawei/hisi/service/LogAnalysisExecutorTest.java`

**Step 1: Write the failing test**

```java
// hisi-dev-tool/src/test/java/com/huawei/hisi/service/LogAnalysisExecutorTest.java
@Test
void testDuplicateLogUpdatesOccurrenceCount() {
    // First log
    LogAnalyzeRequest request1 = new LogAnalyzeRequest();
    request1.setMessage("NullPointerException: null object");
    request1.setStackTrace("at com.example.Service.method(Service.java:50)");
    
    Long reportId1 = logAnalysisExecutor.submitForAnalysis(request1);
    
    // Duplicate log (same fingerprint)
    LogAnalyzeRequest request2 = new LogAnalyzeRequest();
    request2.setMessage("NullPointerException: null object");
    request2.setStackTrace("at com.example.Service.method(Service.java:55)");
    
    Long reportId2 = logAnalysisExecutor.submitForAnalysis(request2);
    
    // Should return same reportId (duplicate)
    assertThat(reportId2).isEqualTo(reportId1);
    
    // Occurrence count should be 2
    LogAnalysisReportEntity report = repository.findById(reportId1);
    assertThat(report.getOccurrenceCount()).isEqualTo(2);
}
```

**Step 2: Run test to verify it fails**

Run: `cd hisi-dev-tool && mvn test -Dtest=LogAnalysisExecutorTest#testDuplicateLogUpdatesOccurrenceCount -v`
Expected: FAIL

**Step 3: Add findByFingerprint method**

```java
// hisi-dev-tool/src/main/java/com/huawei/hisi/repository/LogAnalysisRepository.java
public LogAnalysisReportEntity findByFingerprint(String fingerprint) {
    String sql = "SELECT * FROM log_analysis_report WHERE error_fingerprint = ? ORDER BY created_at DESC LIMIT 1";
    try {
        return jdbcTemplate.queryForObject(sql, new LogAnalysisReportRowMapper(), fingerprint);
    } catch (Exception e) {
        return null;
    }
}

public void incrementOccurrenceCount(Long reportId) {
    String sql = "UPDATE log_analysis_report SET occurrence_count = occurrence_count + 1, updated_at = strftime('%s','now') WHERE report_id = ?";
    jdbcTemplate.update(sql, reportId);
}
```

**Step 4: Modify LogAnalysisExecutor**

```java
// hisi-dev-tool/src/main/java/com/huawei/hisi/service/LogAnalysisExecutor.java
// Inject FingerprintService

@Autowired
private FingerprintService fingerprintService;

public Long submitForAnalysis(LogAnalyzeRequest request) {
    // Generate fingerprint
    String fingerprint = fingerprintService.generateFingerprint(
        request.getMessage() + "\n" + request.getStackTrace()
    );
    
    // Check for duplicate
    LogAnalysisReportEntity existing = repository.findByFingerprint(fingerprint);
    if (existing != null) {
        // Duplicate found - increment count and return existing reportId
        repository.incrementOccurrenceCount(existing.getReportId());
        log.info("Duplicate log detected (fingerprint={}), incrementing count", fingerprint);
        return existing.getReportId();
    }
    
    // New log - create report
    Long reportId = snowflakeIdGenerator.nextId();
    LogAnalysisReportEntity report = new LogAnalysisReportEntity();
    report.setReportId(reportId);
    report.setErrorFingerprint(fingerprint);
    report.setLogMessage(request.getMessage());
    report.setLogStackTrace(request.getStackTrace());
    report.setAnalysisStatus("pending");
    report.setOccurrenceCount(1);
    report.setSimilarityThreshold(0.85);
    // ... other fields
    
    repository.save(report);
    return reportId;
}
```

**Step 5: Run test to verify it passes**

Run: `cd hisi-dev-tool && mvn test -Dtest=LogAnalysisExecutorTest#testDuplicateLogUpdatesOccurrenceCount -v`
Expected: PASS

**Step 6: Commit**

```bash
git add hisi-dev-tool/src/main/java/com/huawei/hisi/service/LogAnalysisExecutor.java
git add hisi-dev-tool/src/main/java/com/huawei/hisi/repository/LogAnalysisRepository.java
git add hisi-dev-tool/src/test/java/com/huawei/hisi/service/LogAnalysisExecutorTest.java
git commit -m "feat(core): implement fingerprint-based log deduplication"
```

---

## Phase 3: Neo4j LogChunk Node & Vector Index

### Task 5: Create LogChunk Neo4j Model

**Files:**
- Create: `hisi-dev-tool/src/main/java/com/huawei/hisi/neo4j/model/LogChunkNode.java`
- Create: `hisi-dev-tool/src/main/java/com/huawei/hisi/neo4j/repository/Neo4jLogChunkRepository.java`
- Test: `hisi-dev-tool/src/test/java/com/huawei/hisi/neo4j/repository/Neo4jLogChunkRepositoryTest.java`

**Step 1: Write the failing test**

```java
// hisi-dev-tool/src/test/java/com/huawei/hisi/neo4j/repository/Neo4jLogChunkRepositoryTest.java
@Test
void testSaveLogChunkWithEmbedding() {
    LogChunkNode chunk = new LogChunkNode();
    chunk.setNodeId("log-chunk-123");
    chunk.setErrorType("NullPointerException");
    chunk.setMessage("Cannot invoke method on null");
    chunk.setFingerprint("abc123def456");
    chunk.setEmbedding(List.of(0.1, 0.2, 0.3)); // Simplified for test
    
    repository.save(chunk);
    
    LogChunkNode found = repository.findByNodeId("log-chunk-123");
    assertThat(found.getErrorType()).isEqualTo("NullPointerException");
}

@Test
void testFindBySimilarity() {
    // Create test chunks with embeddings
    // Query for similar chunks
    List<Double> queryVector = List.of(0.1, 0.2, 0.3);
    List<LogChunkNode> similar = repository.findSimilarByVector(queryVector, 0.85, 5);
    assertThat(similar).isNotEmpty();
}
```

**Step 2: Run test to verify it fails**

Run: `cd hisi-dev-tool && mvn test -Dtest=Neo4jLogChunkRepositoryTest -v`
Expected: FAIL with "LogChunkNode not found"

**Step 3: Create LogChunkNode model**

```java
// hisi-dev-tool/src/main/java/com/huawei/hisi/neo4j/model/LogChunkNode.java
package com.huawei.hisi.neo4j.model;

import lombok.Data;
import java.util.List;

@Data
public class LogChunkNode {
    private String nodeId;
    private String errorType;
    private String message;
    private String fingerprint;
    private String stackTrace;
    private List<Double> embedding;  // 2048 dimensions
    private String createdAt;
}
```

**Step 4: Create repository**

```java
// hisi-dev-tool/src/main/java/com/huawei/hisi/neo4j/repository/Neo4jLogChunkRepository.java
package com.huawei.hisi.neo4j.repository;

import com.huawei.hisi.neo4j.model.LogChunkNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.neo4j.core.Neo4jTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Slf4j
@Repository
@RequiredArgsConstructor
public class Neo4jLogChunkRepository {

    private final Neo4jTemplate neo4jTemplate;

    public void save(LogChunkNode chunk) {
        String cypher = """
            MERGE (l:LogChunk {nodeId: $nodeId})
            SET l.errorType = $errorType,
                l.message = $message,
                l.fingerprint = $fingerprint,
                l.stackTrace = $stackTrace,
                l.embedding = $embedding,
                l.createdAt = datetime()
            """;
        neo4jTemplate.query(cypher, Map.of(
            "nodeId", chunk.getNodeId(),
            "errorType", chunk.getErrorType(),
            "message", chunk.getMessage(),
            "fingerprint", chunk.getFingerprint(),
            "stackTrace", chunk.getStackTrace(),
            "embedding", chunk.getEmbedding()
        ));
    }

    public List<LogChunkNode> findSimilarByVector(List<Double> queryVector, double threshold, int limit) {
        String cypher = """
            CALL db.index.vector.queryNodes('logEmbedding', $limit, $queryVector)
            YIELD node AS l, score
            WHERE score >= $threshold
            RETURN l.nodeId AS nodeId, l.errorType AS errorType, l.message AS message, score
            """;
        // ... implementation
    }
}
```

**Step 5: Create vector index initializer**

```java
// hisi-dev-tool/src/main/java/com/huawei/hisi/neo4j/config/LogChunkVectorIndexInitializer.java
package com.huawei.hisi.neo4j.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.neo4j.core.Neo4jTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class LogChunkVectorIndexInitializer {

    private final Neo4jTemplate neo4jTemplate;

    @PostConstruct
    public void createVectorIndex() {
        String cypher = """
            CREATE VECTOR INDEX logEmbedding IF NOT EXISTS
            FOR (l:LogChunk) ON l.embedding
            OPTIONS {
                indexConfig: {
                    `vector.dimensions`: 2048,
                    `vector.similarity_function`: 'cosine'
                }
            }
            """;
        try {
            neo4jTemplate.query(cypher);
            log.info("LogChunk vector index created successfully");
        } catch (Exception e) {
            log.warn("Vector index creation failed (may already exist): {}", e.getMessage());
        }
    }
}
```

**Step 6: Run test to verify it passes**

Run: `cd hisi-dev-tool && mvn test -Dtest=Neo4jLogChunkRepositoryTest -v`
Expected: PASS

**Step 7: Commit**

```bash
git add hisi-dev-tool/src/main/java/com/huawei/hisi/neo4j/model/LogChunkNode.java
git add hisi-dev-tool/src/main/java/com/huawei/hisi/neo4j/repository/Neo4jLogChunkRepository.java
git add hisi-dev-tool/src/main/java/com/huawei/hisi/neo4j/config/LogChunkVectorIndexInitializer.java
git add hisi-dev-tool/src/test/java/com/huawei/hisi/neo4j/repository/Neo4jLogChunkRepositoryTest.java
git commit -m "feat(neo4j): add LogChunk node with vector index for log similarity search"
```

---

## Phase 4: Scheduled Log Pull Task

### Task 6: Create Scheduled Task for Log Pulling

**Files:**
- Create: `hisi-dev-tool/src/main/java/com/huawei/hisi/scheduler/LogPullScheduler.java`
- Create: `hisi-dev-tool/src/main/java/com/huawei/hisi/model/AppLogConfig.java`
- Test: `hisi-dev-tool/src/test/java/com/huawei/hisi/scheduler/LogPullSchedulerTest.java`

**Step 1: Write the failing test**

```java
// hisi-dev-tool/src/test/java/com/huawei/hisi/scheduler/LogPullSchedulerTest.java
@Test
void testPullLogsAndStore() {
    AppLogConfig config = new AppLogConfig();
    config.setAppId("hiapm");
    config.setProjectPath("/path/to/project");
    config.setDslQuery("{\"query\": {\"match\": {\"level\": \"ERROR\"}}}");
    config.setPullIntervalMinutes(10);
    
    logPullScheduler.pullLogsForApp(config);
    
    // Verify logs were stored
    List<LogAnalysisReportEntity> reports = repository.findByStatus("pending");
    assertThat(reports).isNotEmpty();
}
```

**Step 2: Run test to verify it fails**

Run: `cd hisi-dev-tool && mvn test -Dtest=LogPullSchedulerTest -v`
Expected: FAIL

**Step 3: Implement scheduler**

```java
// hisi-dev-tool/src/main/java/com/huawei/hisi/scheduler/LogPullScheduler.java
package com.huawei.hisi.scheduler;

import com.huawei.hisi.model.AppLogConfig;
import com.huawei.hisi.service.LogCloudService;
import com.huawei.hisi.service.LogAnalysisExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class LogPullScheduler {

    private final LogCloudService logCloudService;
    private final LogAnalysisExecutor logAnalysisExecutor;
    private final AppLogConfigRepository configRepository;

    @Scheduled(fixedRate = 600000) // 10 minutes
    public void pullLogsForAllApps() {
        List<AppLogConfig> configs = configRepository.findAllActive();
        for (AppLogConfig config : configs) {
            try {
                pullLogsForApp(config);
            } catch (Exception e) {
                log.error("Failed to pull logs for app {}", config.getAppId(), e);
            }
        }
    }

    public void pullLogsForApp(AppLogConfig config) {
        // Query logs from ES using DSL
        List<LogEntry> logs = logCloudService.queryByDsl(config.getDslQuery());
        
        for (LogEntry log : logs) {
            // Submit for analysis (fingerprint deduplication handled internally)
            logAnalysisExecutor.submitForAnalysis(
                log.getMessage(),
                log.getStackTrace(),
                config.getAppId(),
                config.getProjectPath()
            );
        }
        
        log.info("Pulled {} logs for app {}", logs.size(), config.getAppId());
    }
}
```

**Step 4: Enable scheduling**

```java
// hisi-dev-tool/src/main/java/com/huawei/hisi/HisiDevToolApplication.java
// Add annotation:
@EnableScheduling
```

**Step 5: Run test to verify it passes**

Run: `cd hisi-dev-tool && mvn test -Dtest=LogPullSchedulerTest -v`
Expected: PASS

**Step 6: Commit**

```bash
git add hisi-dev-tool/src/main/java/com/huawei/hisi/scheduler/LogPullScheduler.java
git add hisi-dev-tool/src/main/java/com/huawei/hisi/model/AppLogConfig.java
git add hisi-dev-tool/src/test/java/com/huawei/hisi/scheduler/LogPullSchedulerTest.java
git commit -m "feat(scheduler): add scheduled task for periodic log pulling"
```

---

## Phase 5: Extended Status API

### Task 7: Extend Status API with Pipeline Progress

**Files:**
- Modify: `hisi-dev-tool/src/main/java/com/huawei/hisi/controller/LogAnalysisController.java`
- Modify: `hisi-dev-tool/src/main/java/com/huawei/hisi/model/DetailedAnalysisReport.java`
- Test: `hisi-dev-tool/src/test/java/com/huawei/hisi/controller/LogAnalysisControllerTest.java`

**Step 1: Write the failing test**

```java
// hisi-dev-tool/src/test/java/com/huawei/hisi/controller/LogAnalysisControllerTest.java
@Test
void testGetReportStatusWithProgress() {
    // Create a pending report
    Long reportId = submitTestReport();
    
    // Get extended status
    ApiResponse<Map<String, Object>> response = controller.getReportStatus(reportId);
    
    Map<String, Object> status = response.getData();
    assertThat(status.get("status")).isEqualTo("pending");
    assertThat(status.get("progress")).isNotNull();
    assertThat(status.get("stage")).isNotNull();
    assertThat(status.get("queuePosition")).isNotNull();
}
```

**Step 2: Run test to verify it fails**

Run: `cd hisi-dev-tool && mvn test -Dtest=LogAnalysisControllerTest#testGetReportStatusWithProgress -v`
Expected: FAIL with "progress field not found"

**Step 3: Extend controller**

```java
// hisi-dev-tool/src/main/java/com/huawei/hisi/controller/LogAnalysisController.java
@GetMapping("/report/{id}/status")
public ApiResponse<Map<String, Object>> getReportStatus(@PathVariable("id") Long reportId) {
    LogAnalysisReportEntity report = repository.findById(reportId);
    if (report == null) {
        return ApiResponse.error(404, "报告不存在");
    }
    
    Map<String, Object> status = new HashMap<>();
    status.put("reportId", report.getReportId());
    status.put("status", report.getAnalysisStatus());
    status.put("progress", calculateProgress(report));
    status.put("stage", determineStage(report));
    status.put("queuePosition", getQueuePosition(report));
    status.put("etaSeconds", estimateRemainingTime(report));
    status.put("createdAt", report.getCreatedAt());
    status.put("updatedAt", report.getUpdatedAt());
    
    return ApiResponse.success(status);
}

private int calculateProgress(LogAnalysisReportEntity report) {
    // Based on analysis_status
    switch (report.getAnalysisStatus()) {
        case "pending": return 0;
        case "parsing": return 25;
        case "deduplicating": return 50;
        case "analyzing": return 75;
        case "completed": return 100;
        case "failed": return 100;
        default: return 0;
    }
}

private String determineStage(LogAnalysisReportEntity report) {
    return report.getAnalysisStatus(); // pending, parsing, analyzing, completed, failed
}

private int getQueuePosition(LogAnalysisReportEntity report) {
    if (!"pending".equals(report.getAnalysisStatus())) return 0;
    // Count pending reports created before this one
    return repository.countPendingBefore(report.getCreatedAt());
}
```

**Step 4: Add repository method**

```java
// hisi-dev-tool/src/main/java/com/huawei/hisi/repository/LogAnalysisRepository.java
public int countPendingBefore(LocalDateTime createdAt) {
    String sql = "SELECT COUNT(*) FROM log_analysis_report WHERE analysis_status = 'pending' AND created_at < ?";
    return jdbcTemplate.queryForObject(sql, Integer.class, createdAt);
}
```

**Step 5: Run test to verify it passes**

Run: `cd hisi-dev-tool && mvn test -Dtest=LogAnalysisControllerTest#testGetReportStatusWithProgress -v`
Expected: PASS

**Step 6: Commit**

```bash
git add hisi-dev-tool/src/main/java/com/huawei/hisi/controller/LogAnalysisController.java
git add hisi-dev-tool/src/main/java/com/huawei/hisi/repository/LogAnalysisRepository.java
git add hisi-dev-tool/src/test/java/com/huawei/hisi/controller/LogAnalysisControllerTest.java
git commit -m "feat(api): extend report status API with progress and stage info"
```

---

## Phase 6: Frontend - App Selector & Tabs

### Task 8: Add App Selector to LogQuery.vue

**Files:**
- Modify: `hisi-dev-tool-frontend/src/views/log-analysis/LogQuery.vue`
- Modify: `hisi-dev-tool-frontend/src/api/logAnalysis.ts`

**Step 1: Add app selector UI**

```vue
<!-- hisi-dev-tool-frontend/src/views/log-analysis/LogQuery.vue -->
<!-- Add at top of DSL config card, before el-row -->
<el-row :gutter="16" style="margin-bottom: 16px">
  <el-col :span="6">
    <el-form-item label="应用" label-width="60px">
      <el-select v-model="selectedAppId" placeholder="选择应用" @change="handleAppChange">
        <el-option label="hiapm" value="hiapm" />
        <el-option label="其他应用" value="other" />
      </el-select>
    </el-form-item>
  </el-col>
  <el-col :span="6" v-if="selectedAppId !== 'hiapm'">
    <el-form-item label="项目路径" label-width="80px">
      <el-input v-model="projectPath" placeholder="本地项目路径" />
    </el-form-item>
  </el-col>
</el-row>
```

**Step 2: Add reactive state**

```typescript
// hisi-dev-tool-frontend/src/views/log-analysis/LogQuery.vue <script setup>
const selectedAppId = ref('hiapm')
const projectPath = ref('')

const handleAppChange = (appId: string) => {
  // Update DSL config to filter by app
  if (appId === 'hiapm') {
    projectPath.value = ''
  }
}
```

**Step 3: Verify UI renders**

Run: `cd hisi-dev-tool-frontend && npm run dev`
Manual: Open http://localhost:5173/log-analysis
Expected: App selector dropdown visible at top

**Step 4: Commit**

```bash
git add hisi-dev-tool-frontend/src/views/log-analysis/LogQuery.vue
git commit -m "feat(ui): add app selector to log query page"
```

---

### Task 9: Add Root Cause Analysis Tab

**Files:**
- Modify: `hisi-dev-tool-frontend/src/views/log-analysis/LogQuery.vue`
- Create: `hisi-dev-tool-frontend/src/views/log-analysis/components/AnalysisPipeline.vue`

**Step 1: Wrap existing content in tabs**

```vue
<!-- hisi-dev-tool-frontend/src/views/log-analysis/LogQuery.vue -->
<!-- Replace el-card header structure with tabs -->
<el-tabs v-model="activeTab" class="log-tabs">
  <el-tab-pane label="日志查询" name="query">
    <!-- Existing DSL config and results -->
  </el-tab-pane>
  
  <el-tab-pane label="根因定位" name="rootCause">
    <!-- New root cause analysis UI -->
    <RootCausePanel :appId="selectedAppId" :projectPath="projectPath" />
  </el-tab-pane>
</el-tabs>
```

**Step 2: Create AnalysisPipeline component**

```vue
<!-- hisi-dev-tool-frontend/src/views/log-analysis/components/AnalysisPipeline.vue -->
<template>
  <div class="analysis-pipeline">
    <el-steps :active="currentStep" finish-status="success" align-center>
      <el-step title="排队" :description="queueInfo" />
      <el-step title="解析" description="错误信息提取" />
      <el-step title="去重" description="指纹+向量检测" />
      <el-step title="分析" description="LLM根因定位" />
      <el-step title="完成" :description="resultSummary" />
    </el-steps>
    
    <div class="pipeline-detail" v-if="currentStage">
      <el-card shadow="never">
        <template #header>
          <span>当前阶段: {{ stageLabels[currentStage] }}</span>
        </template>
        <el-progress :percentage="progress" :status="progressStatus" />
        <p class="eta" v-if="etaSeconds">预计剩余: {{ formatEta(etaSeconds) }}</p>
      </el-card>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { logAnalysisApi } from '@/api/logAnalysis'

const props = defineProps<{
  reportId: number
}>()

const currentStage = ref('pending')
const progress = ref(0)
const etaSeconds = ref(0)
const queuePosition = ref(0)

const currentStep = computed(() => {
  const stages = ['pending', 'parsing', 'deduplicating', 'analyzing', 'completed']
  return stages.indexOf(currentStage.value)
})

// Poll status every 2 seconds
let pollTimer: number
onMounted(() => {
  pollTimer = setInterval(async () => {
    const res = await logAnalysisApi.getStatus(props.reportId)
    currentStage.value = res.stage
    progress.value = res.progress
    etaSeconds.value = res.etaSeconds
    queuePosition.value = res.queuePosition
  }, 2000)
})

onUnmounted(() => clearInterval(pollTimer))
</script>
```

**Step 3: Verify tabs render**

Run: `cd hisi-dev-tool-frontend && npm run dev`
Manual: Check both tabs render correctly

**Step 4: Commit**

```bash
git add hisi-dev-tool-frontend/src/views/log-analysis/LogQuery.vue
git add hisi-dev-tool-frontend/src/views/log-analysis/components/AnalysisPipeline.vue
git commit -m "feat(ui): add root cause analysis tab with pipeline visualization"
```

---

### Task 10: Create ReportCard Component

**Files:**
- Create: `hisi-dev-tool-frontend/src/views/log-analysis/components/ReportCard.vue`

**Step 1: Create component**

```vue
<!-- hisi-dev-tool-frontend/src/views/log-analysis/components/ReportCard.vue -->
<template>
  <el-card class="report-card" :class="{ 'is-duplicate': isDuplicate }">
    <template #header>
      <div class="card-header">
        <el-tag :type="statusType">{{ status }}</el-tag>
        <span class="error-type">{{ errorType }}</span>
        <el-tag v-if="isDuplicate" type="warning" size="small">
          重复 {{ occurrenceCount }} 次
        </el-tag>
      </div>
    </template>
    
    <el-collapse>
      <el-collapse-item title="错误摘要" name="summary">
        <pre class="error-message">{{ errorMessage }}</pre>
      </el-collapse-item>
      
      <el-collapse-item title="根因分析" name="rootCause" v-if="rootCause">
        <div class="root-cause-content">
          <p><strong>根因类型:</strong> {{ rootCauseType }}</p>
          <p><strong>描述:</strong> {{ rootCauseDescription }}</p>
          <p><strong>置信度:</strong> {{ confidence }}</p>
        </div>
      </el-collapse-item>
      
      <el-collapse-item title="修复建议" name="fixSuggestion" v-if="fixSuggestion">
        <ol class="fix-suggestions">
          <li v-for="(step, index) in fixSteps" :key="index">{{ step }}</li>
        </ol>
      </el-collapse-item>
    </el-collapse>
  </el-card>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  report: {
    status: string
    errorType: string
    errorMessage: string
    rootCause: string
    fixSuggestion: string
    occurrenceCount: number
    similarityScore: number
  }
}>()

const statusType = computed(() => {
  switch (props.report.status) {
    case 'completed': return 'success'
    case 'failed': return 'danger'
    case 'analyzing': return 'warning'
    default: return 'info'
  }
})

const isDuplicate = computed(() => props.report.occurrenceCount > 1)
</script>
```

**Step 2: Commit**

```bash
git add hisi-dev-tool-frontend/src/views/log-analysis/components/ReportCard.vue
git commit -m "feat(ui): add ReportCard component for analysis results display"
```

---

## Phase 7: Cost Limiter (Risk Control)

### Task 11: Implement Cost Limiter

**Files:**
- Create: `hisi-dev-tool/src/main/java/com/huawei/hisi/service/CostLimiter.java`
- Test: `hisi-dev-tool/src/test/java/com/huawei/hisi/service/CostLimiterTest.java`

**Step 1: Write the failing test**

```java
// hisi-dev-tool/src/test/java/com/huawei/hisi/service/CostLimiterTest.java
@Test
void testAllowCallsUnderLimit() {
    CostLimiter limiter = new CostLimiter(1000); // 1000 calls per day
    
    for (int i = 0; i < 500; i++) {
        assertTrue(limiter.allowCall());
    }
}

@Test
void testBlockCallsOverLimit() {
    CostLimiter limiter = new CostLimiter(1000);
    
    // Exhaust limit
    for (int i = 0; i < 1000; i++) {
        limiter.allowCall();
    }
    
    // Should block
    assertFalse(limiter.allowCall());
    assertTrue(limiter.isCircuitBroken());
}

@Test
void testResetAtMidnight() {
    CostLimiter limiter = new CostLimiter(1000);
    limiter.incrementCount(1000);
    
    limiter.resetDaily();
    
    assertTrue(limiter.allowCall());
}
```

**Step 2: Run test to verify it fails**

Run: `cd hisi-dev-tool && mvn test -Dtest=CostLimiterTest -v`
Expected: FAIL

**Step 3: Implement CostLimiter**

```java
// hisi-dev-tool/src/main/java/com/huawei/hisi/service/CostLimiter.java
package com.huawei.hisi.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
public class CostLimiter {

    private final int dailyLimit;
    private final AtomicInteger callCount = new AtomicInteger(0);
    private final AtomicInteger failCount = new AtomicInteger(0);
    private volatile LocalDate lastResetDate = LocalDate.now();
    
    private static final double ERROR_RATE_THRESHOLD = 0.15; // 15%

    public CostLimiter(int dailyLimit) {
        this.dailyLimit = dailyLimit;
    }

    public boolean allowCall() {
        checkAndResetDaily();
        
        if (callCount.get() >= dailyLimit) {
            log.warn("[CostLimiter] Daily limit reached: {} calls", callCount.get());
            return false;
        }
        
        if (getErrorRate() > ERROR_RATE_THRESHOLD) {
            log.warn("[CostLimiter] Error rate too high: {}", getErrorRate());
            return false;
        }
        
        return true;
    }
    
    public void recordCall() {
        checkAndResetDaily();
        callCount.incrementAndGet();
    }
    
    public void recordFailure() {
        failCount.incrementAndGet();
    }
    
    public boolean isCircuitBroken() {
        return callCount.get() >= dailyLimit || getErrorRate() > ERROR_RATE_THRESHOLD;
    }
    
    public void resetDaily() {
        callCount.set(0);
        failCount.set(0);
        lastResetDate = LocalDate.now();
        log.info("[CostLimiter] Daily reset completed");
    }
    
    private void checkAndResetDaily() {
        LocalDate today = LocalDate.now();
        if (!today.equals(lastResetDate)) {
            resetDaily();
        }
    }
    
    private double getErrorRate() {
        int total = callCount.get();
        if (total == 0) return 0;
        return (double) failCount.get() / total;
    }
}
```

**Step 4: Run test to verify it passes**

Run: `cd hisi-dev-tool && mvn test -Dtest=CostLimiterTest -v`
Expected: PASS

**Step 5: Commit**

```bash
git add hisi-dev-tool/src/main/java/com/huawei/hisi/service/CostLimiter.java
git add hisi-dev-tool/src/test/java/com/huawei/hisi/service/CostLimiterTest.java
git commit -m "feat(risk): add CostLimiter for LLM call budget control"
```

---

## Phase 8: Analysis Pipeline Service

### Task 12: Implement Analysis Pipeline Orchestrator

**Files:**
- Create: `hisi-dev-tool/src/main/java/com/huawei/hisi/service/AnalysisPipelineService.java`
- Test: `hisi-dev-tool/src/test/java/com/huawei/hisi/service/AnalysisPipelineServiceTest.java`

**Step 1: Write the failing test**

```java
// hisi-dev-tool/src/test/java/com/huawei/hisi/service/AnalysisPipelineServiceTest.java
@Test
void testPipelineExecution() {
    Long reportId = createTestReport();
    
    analysisPipelineService.executePipeline(reportId);
    
    LogAnalysisReportEntity report = repository.findById(reportId);
    assertThat(report.getAnalysisStatus()).isEqualTo("completed");
    assertThat(report.getRootCause()).isNotNull();
}

@Test
void testPipelineRecordsIntermediateSteps() {
    Long reportId = createTestReport();
    
    analysisPipelineService.executePipeline(reportId);
    
    List<AnalysisStepEntity> steps = stepRepository.findByReportId(reportId);
    assertThat(steps).hasSize(4); // parsing, deduplicating, analyzing, completed
}
```

**Step 2: Run test to verify it fails**

Run: `cd hisi-dev-tool && mvn test -Dtest=AnalysisPipelineServiceTest -v`
Expected: FAIL

**Step 3: Implement pipeline**

```java
// hisi-dev-tool/src/main/java/com/huawei/hisi/service/AnalysisPipelineService.java
package com.huawei.hisi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisPipelineService {

    private final LogAnalysisRepository reportRepository;
    private final FingerprintService fingerprintService;
    private final EmbeddingService embeddingService;
    private final Neo4jLogChunkRepository logChunkRepository;
    private final CostLimiter costLimiter;
    private final AnalysisStepRepository stepRepository;

    public void executePipeline(Long reportId) {
        LogAnalysisReportEntity report = reportRepository.findById(reportId);
        if (report == null) return;
        
        try {
            // Step 1: Parse error
            updateStatus(reportId, "parsing", 25);
            recordStep(reportId, "parsing", "Extracting error info");
            // ... parsing logic
            
            // Step 2: Deduplicate
            updateStatus(reportId, "deduplicating", 50);
            recordStep(reportId, "deduplicating", "Fingerprint: " + report.getErrorFingerprint());
            // Already handled by fingerprint in submission
            
            // Step 3: Vector similarity
            if (!costLimiter.allowCall()) {
                log.warn("Cost limit reached, skipping vector analysis");
                updateStatus(reportId, "completed", 100);
                return;
            }
            updateStatus(reportId, "analyzing", 75);
            recordStep(reportId, "analyzing", "Vector search started");
            
            float[] embedding = embeddingService.generateEmbedding(report.getLogMessage());
            List<LogChunkNode> similar = logChunkRepository.findSimilarByVector(
                toDoubleList(embedding), 0.85, 5);
            
            // Step 4: LLM analysis (via MCP tools)
            // ... LLM integration
            
            updateStatus(reportId, "completed", 100);
            recordStep(reportId, "completed", "Analysis finished");
            
        } catch (Exception e) {
            log.error("Pipeline failed for report {}", reportId, e);
            updateStatus(reportId, "failed", 100);
            recordStep(reportId, "failed", e.getMessage());
            costLimiter.recordFailure();
        }
    }
    
    private void updateStatus(Long reportId, String status, int progress) {
        reportRepository.updateAnalysisStatus(reportId, status);
    }
    
    private void recordStep(Long reportId, String stage, String detail) {
        AnalysisStepEntity step = new AnalysisStepEntity();
        step.setReportId(reportId);
        step.setStage(stage);
        step.setDetail(detail);
        stepRepository.save(step);
    }
}
```

**Step 4: Run test to verify it passes**

Run: `cd hisi-dev-tool && mvn test -Dtest=AnalysisPipelineServiceTest -v`
Expected: PASS

**Step 5: Commit**

```bash
git add hisi-dev-tool/src/main/java/com/huawei/hisi/service/AnalysisPipelineService.java
git add hisi-dev-tool/src/test/java/com/huawei/hisi/service/AnalysisPipelineServiceTest.java
git commit -m "feat(core): add AnalysisPipelineService for node-based root cause analysis"
```

---

## Summary

**Total Tasks:** 12 tasks across 8 phases

**Estimated Time:** 
- Phase 1 (DB): 2 hours
- Phase 2 (Fingerprint): 1.5 hours
- Phase 3 (Neo4j): 2 hours
- Phase 4 (Scheduler): 1 hour
- Phase 5 (API): 1 hour
- Phase 6 (Frontend): 3 hours
- Phase 7 (Risk Control): 1 hour
- Phase 8 (Pipeline): 2 hours

**Total:** ~12 hours of development

**Dependencies:**
- Neo4j 5.11+ (existing)
- EmbeddingService (existing)
- Spring @Scheduled (new)
- SQLite ALTER TABLE support

**Risk Controls Implemented:**
1. Fingerprint deduplication (90% reduction)
2. Cost limiter (1000 calls/day)
3. Error rate threshold (15%)
4. Intermediate step logging
5. Status progress tracking

---

## Execution Options

Plan complete and saved to `docs/plans/2026-06-12-log-root-cause-analysis.md`.

**Two execution options:**

**1. Subagent-Driven (this session)** - I dispatch fresh subagent per task, review between tasks, fast iteration

**2. Parallel Session (separate)** - Open new session with executing-plans, batch execution with checkpoints

**Which approach would you prefer?**