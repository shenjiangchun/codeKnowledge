# 知识图谱功能实施计划

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 在不影响现有调用链生成功能的前提下，新建知识图谱生成功能和数据结构，支持所有入口类型（HTTP、MQ、定时任务、事件监听、WebSocket、RPC）。

**Architecture:** 采用安全重构模式 - 新建独立的数据表、服务类和 Controller，原有调用链代码保持不变。

**Tech Stack:** Spring Boot 3.2.0, Java 17, OpenGauss, JavaParser

---

## 设计原则

### 1. 零侵入原则

- 原有 `HisiURIMethodChainToDBService` 和相关类**不做任何修改**
- 新建独立的类和方法承载知识图谱逻辑
- 公共工具类采用"复制+抽取"模式，原有代码保持不变

### 2. 数据隔离原则

- 知识图谱使用**新建的三张表**，与 `method_call_graph5` 完全独立
- 不修改现有表结构

### 3. 入口类型全覆盖

| 入口类型 | 注解 | 示例 |
|---------|------|------|
| HTTP | @RequestMapping, @GetMapping, @PostMapping | REST API |
| SCHEDULED | @Scheduled | 定时任务 |
| MQ | @RabbitListener, @KafkaListener | 消息队列消费者 |
| EVENT | @EventListener, @TransactionalEventListener | 事件监听器 |
| WEBSOCKET | @OnMessage, @ServerEndpoint | WebSocket处理 |
| RPC | @DubboService, @FeignClient, @GrpcService | 远程服务 |

---

## 数据库设计

### 表1: code_method_node (方法节点表)

```sql
CREATE TABLE code_method_node (
    node_id VARCHAR(128) PRIMARY KEY,           -- 节点ID (类名.方法名.签名hash)
    class_name VARCHAR(256) NOT NULL,           -- 全限定类名
    method_name VARCHAR(128) NOT NULL,          -- 方法名
    signature VARCHAR(512),                     -- 方法签名
    file_path VARCHAR(512),                     -- 源文件路径
    start_line INTEGER,                         -- 起始行号
    end_line INTEGER,                           -- 结束行号
    complexity INTEGER DEFAULT 1,               -- 圈复杂度
    thrown_exceptions TEXT,                     -- 抛出的异常 (JSON数组)
    caught_exceptions TEXT,                     -- 捕获的异常 (JSON数组)
    method_body TEXT,                           -- 方法体 (压缩存储)
    project_path VARCHAR(512) NOT NULL,         -- 项目路径
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_method_node_class ON code_method_node(class_name);
CREATE INDEX idx_method_node_project ON code_method_node(project_path);
```

### 表2: code_call_relation (调用关系表)

```sql
CREATE TABLE code_call_relation (
    id BIGSERIAL PRIMARY KEY,
    caller_id VARCHAR(128) NOT NULL,            -- 调用方节点ID
    callee_id VARCHAR(128) NOT NULL,            -- 被调用方节点ID
    call_type VARCHAR(20) DEFAULT 'DIRECT',     -- DIRECT/VIRTUAL/STATIC
    call_line INTEGER,                          -- 调用行号
    project_path VARCHAR(512) NOT NULL,         -- 项目路径
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(caller_id, callee_id, call_line)
);

CREATE INDEX idx_call_relation_caller ON code_call_relation(caller_id);
CREATE INDEX idx_call_relation_callee ON code_call_relation(callee_id);
CREATE INDEX idx_call_relation_project ON code_call_relation(project_path);
```

### 表3: code_entry_point (入口点表)

```sql
CREATE TABLE code_entry_point (
    id BIGSERIAL PRIMARY KEY,
    node_id VARCHAR(128) NOT NULL,              -- 方法节点ID
    entry_type VARCHAR(32) NOT NULL,            -- HTTP/SCHEDULED/MQ/EVENT/WEBSOCKET/RPC
    entry_key VARCHAR(256),                     -- 入口标识 (URI/cron/queue名等)
    entry_info TEXT,                            -- 入口详细信息 (JSON)
    project_path VARCHAR(512) NOT NULL,         -- 项目路径
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_entry_point_type ON code_entry_point(entry_type);
CREATE INDEX idx_entry_point_project ON code_entry_point(project_path);
CREATE INDEX idx_entry_point_node ON code_entry_point(node_id);
```

---

## Task 1: 创建入口类型枚举

**Files:**
- Create: `src/main/java/com/huawei/hisi/knowledgegraph/model/EntryPointType.java`

**Step 1: 创建枚举类**

```java
package com.huawei.hisi.knowledgegraph.model;

/**
 * 代码入口点类型枚举
 */
public enum EntryPointType {
    HTTP("HTTP", "REST API入口"),
    SCHEDULED("SCHEDULED", "定时任务入口"),
    MQ("MQ", "消息队列消费者入口"),
    EVENT("EVENT", "事件监听器入口"),
    WEBSOCKET("WEBSOCKET", "WebSocket入口"),
    RPC("RPC", "远程服务入口"),
    LIFECYCLE("LIFECYCLE", "生命周期入口");

    private final String code;
    private final String description;

    EntryPointType(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }
}
```

**Step 2: 验证文件创建**

Run: `ls src/main/java/com/huawei/hisi/knowledgegraph/model/`
Expected: 显示 EntryPointType.java

---

## Task 2: 创建知识图谱数据模型

**Files:**
- Create: `src/main/java/com/huawei/hisi/knowledgegraph/model/MethodNode.java`
- Create: `src/main/java/com/huawei/hisi/knowledgegraph/model/CallRelation.java`
- Create: `src/main/java/com/huawei/hisi/knowledgegraph/model/EntryPoint.java`

**Step 1: 创建 MethodNode**

```java
package com.huawei.hisi.knowledgegraph.model;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MethodNode {
    private String nodeId;
    private String className;
    private String methodName;
    private String signature;
    private String filePath;
    private Integer startLine;
    private Integer endLine;
    private Integer complexity;
    private List<String> thrownExceptions;
    private List<String> caughtExceptions;
    private String methodBody;
    private String projectPath;
}
```

**Step 2: 创建 CallRelation**

```java
package com.huawei.hisi.knowledgegraph.model;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CallRelation {
    private String callerId;
    private String calleeId;
    private String callType;
    private Integer callLine;
    private String projectPath;
}
```

**Step 3: 创建 EntryPoint**

```java
package com.huawei.hisi.knowledgegraph.model;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EntryPoint {
    private String nodeId;
    private EntryPointType entryType;
    private String entryKey;
    private String entryInfo;
    private String projectPath;
}
```

---

## Task 3: 创建知识图谱 Repository

**Files:**
- Create: `src/main/java/com/huawei/hisi/knowledgegraph/repository/MethodNodeRepository.java`
- Create: `src/main/java/com/huawei/hisi/knowledgegraph/repository/CallRelationRepository.java`
- Create: `src/main/java/com/huawei/hisi/knowledgegraph/repository/EntryPointRepository.java`

**Step 1: 创建 MethodNodeRepository**

```java
package com.huawei.hisi.knowledgegraph.repository;

import com.huawei.hisi.knowledgegraph.model.MethodNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public class MethodNodeRepository {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final RowMapper<MethodNode> rowMapper = (rs, rowNum) -> MethodNode.builder()
        .nodeId(rs.getString("node_id"))
        .className(rs.getString("class_name"))
        .methodName(rs.getString("method_name"))
        .signature(rs.getString("signature"))
        .filePath(rs.getString("file_path"))
        .startLine(rs.getInt("start_line"))
        .endLine(rs.getInt("end_line"))
        .complexity(rs.getInt("complexity"))
        .methodBody(rs.getString("method_body"))
        .projectPath(rs.getString("project_path"))
        .build();

    public void save(MethodNode node) {
        String sql = "INSERT INTO code_method_node (node_id, class_name, method_name, signature, " +
            "file_path, start_line, end_line, complexity, thrown_exceptions, caught_exceptions, " +
            "method_body, project_path) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?) " +
            "ON CONFLICT (node_id) DO UPDATE SET ...";
        // 实现保存逻辑
    }

    public void batchSave(List<MethodNode> nodes) {
        // 批量保存实现
    }

    public Optional<MethodNode> findByNodeId(String nodeId) {
        String sql = "SELECT * FROM code_method_node WHERE node_id = ?";
        List<MethodNode> results = jdbcTemplate.query(sql, rowMapper, nodeId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public List<MethodNode> findByClassName(String className, String projectPath) {
        String sql = "SELECT * FROM code_method_node WHERE class_name = ? AND project_path = ?";
        return jdbcTemplate.query(sql, rowMapper, className, projectPath);
    }

    public List<MethodNode> findByProjectPath(String projectPath) {
        String sql = "SELECT * FROM code_method_node WHERE project_path = ?";
        return jdbcTemplate.query(sql, rowMapper, projectPath);
    }

    public void deleteByProjectPath(String projectPath) {
        String sql = "DELETE FROM code_method_node WHERE project_path = ?";
        jdbcTemplate.update(sql, projectPath);
    }
}
```

**Step 2: 创建 CallRelationRepository**

```java
package com.huawei.hisi.knowledgegraph.repository;

import com.huawei.hisi.knowledgegraph.model.CallRelation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public class CallRelationRepository {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    public void batchSave(List<CallRelation> relations) {
        // 批量保存实现
    }

    public List<CallRelation> findByCallerId(String callerId) {
        String sql = "SELECT * FROM code_call_relation WHERE caller_id = ?";
        return jdbcTemplate.query(sql, rowMapper, callerId);
    }

    public List<CallRelation> findByCalleeId(String calleeId) {
        String sql = "SELECT * FROM code_call_relation WHERE callee_id = ?";
        return jdbcTemplate.query(sql, rowMapper, calleeId);
    }

    public void deleteByProjectPath(String projectPath) {
        String sql = "DELETE FROM code_call_relation WHERE project_path = ?";
        jdbcTemplate.update(sql, projectPath);
    }
}
```

**Step 3: 创建 EntryPointRepository**

```java
package com.huawei.hisi.knowledgegraph.repository;

import com.huawei.hisi.knowledgegraph.model.EntryPoint;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public class EntryPointRepository {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    public void batchSave(List<EntryPoint> entryPoints) {
        // 批量保存实现
    }

    public List<EntryPoint> findByProjectPath(String projectPath) {
        String sql = "SELECT * FROM code_entry_point WHERE project_path = ?";
        return jdbcTemplate.query(sql, rowMapper, projectPath);
    }

    public List<EntryPoint> findByEntryType(String entryType, String projectPath) {
        String sql = "SELECT * FROM code_entry_point WHERE entry_type = ? AND project_path = ?";
        return jdbcTemplate.query(sql, rowMapper, entryType, projectPath);
    }

    public void deleteByProjectPath(String projectPath) {
        String sql = "DELETE FROM code_entry_point WHERE project_path = ?";
        jdbcTemplate.update(sql, projectPath);
    }
}
```

---

## Task 4: 创建入口点扫描器

**Files:**
- Create: `src/main/java/com/huawei/hisi/knowledgegraph/scanner/EntryPointScanner.java`

**Step 1: 创建通用入口点扫描器**

```java
package com.huawei.hisi.knowledgegraph.scanner;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.huawei.hisi.knowledgegraph.model.EntryPoint;
import com.huawei.hisi.knowledgegraph.model.EntryPointType;
import org.springframework.stereotype.Component;
import java.util.*;

/**
 * 入口点扫描器 - 扫描各种类型的代码入口
 */
@Component
public class EntryPointScanner {

    // HTTP入口注解
    private static final Set<String> HTTP_ANNOTATIONS = Set.of(
        "RequestMapping", "GetMapping", "PostMapping", "PutMapping",
        "DeleteMapping", "PatchMapping", "Path"
    );

    // 定时任务注解
    private static final Set<String> SCHEDULED_ANNOTATIONS = Set.of("Scheduled");

    // MQ消费者注解
    private static final Set<String> MQ_ANNOTATIONS = Set.of(
        "RabbitListener", "KafkaListener", "RocketMQMessageListener"
    );

    // 事件监听注解
    private static final Set<String> EVENT_ANNOTATIONS = Set.of(
        "EventListener", "TransactionalEventListener"
    );

    // WebSocket注解
    private static final Set<String> WEBSOCKET_ANNOTATIONS = Set.of(
        "OnMessage", "ServerEndpoint", "OnOpen", "OnClose"
    );

    // RPC注解
    private static final Set<String> RPC_ANNOTATIONS = Set.of(
        "DubboService", "FeignClient", "GrpcService", "RpcService"
    );

    // 生命周期注解
    private static final Set<String> LIFECYCLE_ANNOTATIONS = Set.of(
        "PostConstruct", "PreDestroy", "AfterConstruct"
    );

    /**
     * 扫描编译单元中的所有入口点
     */
    public List<EntryPoint> scanEntryPoints(CompilationUnit cu, String projectPath) {
        List<EntryPoint> entryPoints = new ArrayList<>();

        cu.findAll(MethodDeclaration.class).forEach(method -> {
            for (AnnotationExpr annotation : method.getAnnotations()) {
                String annotationName = annotation.getNameAsString();
                EntryPoint entryPoint = createEntryPoint(method, annotation, annotationName, projectPath);
                if (entryPoint != null) {
                    entryPoints.add(entryPoint);
                }
            }
        });

        return entryPoints;
    }

    private EntryPoint createEntryPoint(MethodDeclaration method, AnnotationExpr annotation,
            String annotationName, String projectPath) {

        EntryPointType type = determineEntryPointType(annotationName);
        if (type == null) {
            return null;
        }

        String nodeId = generateNodeId(method);
        String entryKey = extractEntryKey(annotation, type);
        String entryInfo = extractEntryInfo(annotation);

        return EntryPoint.builder()
            .nodeId(nodeId)
            .entryType(type)
            .entryKey(entryKey)
            .entryInfo(entryInfo)
            .projectPath(projectPath)
            .build();
    }

    private EntryPointType determineEntryPointType(String annotationName) {
        if (HTTP_ANNOTATIONS.contains(annotationName)) return EntryPointType.HTTP;
        if (SCHEDULED_ANNOTATIONS.contains(annotationName)) return EntryPointType.SCHEDULED;
        if (MQ_ANNOTATIONS.contains(annotationName)) return EntryPointType.MQ;
        if (EVENT_ANNOTATIONS.contains(annotationName)) return EntryPointType.EVENT;
        if (WEBSOCKET_ANNOTATIONS.contains(annotationName)) return EntryPointType.WEBSOCKET;
        if (RPC_ANNOTATIONS.contains(annotationName)) return EntryPointType.RPC;
        if (LIFECYCLE_ANNOTATIONS.contains(annotationName)) return EntryPointType.LIFECYCLE;
        return null;
    }

    private String extractEntryKey(AnnotationExpr annotation, EntryPointType type) {
        switch (type) {
            case HTTP:
                return extractHttpPath(annotation);
            case SCHEDULED:
                return extractCronExpression(annotation);
            case MQ:
                return extractQueueName(annotation);
            case EVENT:
                return extractEventType(annotation);
            default:
                return annotation.getNameAsString();
        }
    }

    private String extractEntryInfo(AnnotationExpr annotation) {
        if (annotation instanceof NormalAnnotationExpr) {
            NormalAnnotationExpr normal = (NormalAnnotationExpr) annotation;
            Map<String, String> info = new HashMap<>();
            normal.getPairs().forEach(pair ->
                info.put(pair.getNameAsString(), pair.getValue().toString())
            );
            return info.toString();
        }
        return "";
    }

    private String extractHttpPath(AnnotationExpr annotation) {
        // 提取HTTP路径逻辑
        if (annotation instanceof NormalAnnotationExpr) {
            NormalAnnotationExpr normal = (NormalAnnotationExpr) annotation;
            return normal.getPairs().stream()
                .filter(p -> "value".equals(p.getNameAsString()) || "path".equals(p.getNameAsString()))
                .findFirst()
                .map(p -> p.getValue().toString())
                .orElse("");
        }
        return "";
    }

    private String extractCronExpression(AnnotationExpr annotation) {
        if (annotation instanceof NormalAnnotationExpr) {
            NormalAnnotationExpr normal = (NormalAnnotationExpr) annotation;
            return normal.getPairs().stream()
                .filter(p -> "cron".equals(p.getNameAsString()))
                .findFirst()
                .map(p -> p.getValue().toString())
                .orElse("");
        }
        return "";
    }

    private String extractQueueName(AnnotationExpr annotation) {
        if (annotation instanceof NormalAnnotationExpr) {
            NormalAnnotationExpr normal = (NormalAnnotationExpr) annotation;
            return normal.getPairs().stream()
                .filter(p -> "queues".equals(p.getNameAsString()) || "topics".equals(p.getNameAsString()))
                .findFirst()
                .map(p -> p.getValue().toString())
                .orElse("");
        }
        return "";
    }

    private String extractEventType(AnnotationExpr annotation) {
        // 事件类型通常从方法参数推断
        return "";
    }

    private String generateNodeId(MethodDeclaration method) {
        // 生成唯一节点ID
        return method.findCompilationUnit()
            .flatMap(cu -> cu.getPrimaryTypeName())
            .map(name -> name + "." + method.getNameAsString())
            .orElse(method.getNameAsString());
    }
}
```

---

## Task 5: 创建方法节点扫描器

**Files:**
- Create: `src/main/java/com/huawei/hisi/knowledgegraph/scanner/MethodNodeScanner.java`

**Step 1: 创建方法节点扫描器**

```java
package com.huawei.hisi.knowledgegraph.scanner;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.huawei.hisi.knowledgegraph.model.MethodNode;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 方法节点扫描器 - 提取方法信息和方法体
 */
@Component
public class MethodNodeScanner {

    /**
     * 扫描编译单元中的所有方法节点
     */
    public List<MethodNode> scanMethodNodes(CompilationUnit cu, String filePath, String projectPath) {
        List<MethodNode> nodes = new ArrayList<>();

        String className = cu.getPrimaryTypeName().orElse("Unknown");

        cu.findAll(MethodDeclaration.class).forEach(method -> {
            MethodNode node = MethodNode.builder()
                .nodeId(generateNodeId(className, method))
                .className(getFullClassName(cu, className))
                .methodName(method.getNameAsString())
                .signature(method.getSignature().asString())
                .filePath(filePath)
                .startLine(method.getBegin().map(p -> p.line).orElse(0))
                .endLine(method.getEnd().map(p -> p.line).orElse(0))
                .complexity(calculateComplexity(method))
                .thrownExceptions(extractThrownExceptions(method))
                .caughtExceptions(extractCaughtExceptions(method))
                .methodBody(compressMethodBody(method))
                .projectPath(projectPath)
                .build();
            nodes.add(node);
        });

        return nodes;
    }

    private String generateNodeId(String className, MethodDeclaration method) {
        return className + "." + method.getNameAsString() + "." +
            Integer.toHexString(method.getSignature().hashCode());
    }

    private String getFullClassName(CompilationUnit cu, String className) {
        return cu.getPackageDeclaration()
            .map(pd -> pd.getNameAsString() + "." + className)
            .orElse(className);
    }

    private int calculateComplexity(MethodDeclaration method) {
        // 圈复杂度 = 1 + 分支语句数量
        int complexity = 1;
        complexity += method.findAll(com.github.javaparser.ast.stmt.IfStmt.class).size();
        complexity += method.findAll(com.github.javaparser.ast.stmt.ForStmt.class).size();
        complexity += method.findAll(com.github.javaparser.ast.stmt.WhileStmt.class).size();
        complexity += method.findAll(com.github.javaparser.ast.stmt.SwitchStmt.class).size();
        complexity += method.findAll(com.github.javaparser.ast.stmt.ForEachStmt.class).size();
        complexity += method.findAll(com.github.javaparser.ast.expr.ConditionalExpr.class).size();
        return complexity;
    }

    private List<String> extractThrownExceptions(MethodDeclaration method) {
        List<String> exceptions = new ArrayList<>();

        // throws 声明
        method.getThrownExceptions().forEach(ex -> exceptions.add(ex.toString()));

        // throw 语句
        method.findAll(ThrowStmt.class).forEach(throwStmt -> {
            String exType = extractExceptionType(throwStmt.getExpression().toString());
            if (exType != null && !exceptions.contains(exType)) {
                exceptions.add(exType);
            }
        });

        return exceptions;
    }

    private List<String> extractCaughtExceptions(MethodDeclaration method) {
        return method.findAll(CatchClause.class).stream()
            .map(cc -> cc.getParameter().getType().asString())
            .distinct()
            .collect(Collectors.toList());
    }

    private String extractExceptionType(String throwExpression) {
        // 从 throw new XxxException(...) 中提取异常类型
        if (throwExpression.startsWith("new ")) {
            int parenIndex = throwExpression.indexOf('(');
            if (parenIndex > 4) {
                return throwExpression.substring(4, parenIndex).trim();
            }
        }
        return null;
    }

    private String compressMethodBody(MethodDeclaration method) {
        return method.getBody()
            .map(body -> body.toString())
            .orElse("");
    }
}
```

---

## Task 6: 创建调用关系扫描器

**Files:**
- Create: `src/main/java/com/huawei/hisi/knowledgegraph/scanner/CallRelationScanner.java`

**Step 1: 创建调用关系扫描器**

此扫描器复用现有 HisiURIMethodChainToDBService 中的核心逻辑，但存储到新的数据结构。

```java
package com.huawei.hisi.knowledgegraph.scanner;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.huawei.hisi.knowledgegraph.model.CallRelation;
import org.springframework.stereotype.Component;
import java.util.*;

/**
 * 调用关系扫描器 - 提取方法间的调用关系
 */
@Component
public class CallRelationScanner {

    /**
     * 扫描编译单元中的调用关系
     */
    public List<CallRelation> scanCallRelations(CompilationUnit cu, String projectPath,
            Map<String, String> classNameToNodeId) {
        List<CallRelation> relations = new ArrayList<>();

        String callerClassName = cu.getPrimaryTypeName().orElse("Unknown");

        cu.findAll(MethodDeclaration.class).forEach(method -> {
            String callerId = classNameToNodeId.get(callerClassName + "." + method.getNameAsString());
            if (callerId == null) return;

            method.findAll(MethodCallExpr.class).forEach(call -> {
                String calleeId = resolveCalleeId(call, classNameToNodeId);
                if (calleeId != null) {
                    CallRelation relation = CallRelation.builder()
                        .callerId(callerId)
                        .calleeId(calleeId)
                        .callType(determineCallType(call))
                        .callLine(call.getBegin().map(p -> p.line).orElse(0))
                        .projectPath(projectPath)
                        .build();
                    relations.add(relation);
                }
            });
        });

        return relations;
    }

    private String resolveCalleeId(MethodCallExpr call, Map<String, String> classNameToNodeId) {
        try {
            // 尝试解析调用的方法
            String scope = call.getScope().map(Object::toString).orElse("");
            String methodName = call.getNameAsString();

            // 简单实现：根据方法名查找
            for (Map.Entry<String, String> entry : classNameToNodeId.entrySet()) {
                if (entry.getKey().endsWith("." + methodName)) {
                    return entry.getValue();
                }
            }
        } catch (Exception e) {
            // 解析失败，跳过
        }
        return null;
    }

    private String determineCallType(MethodCallExpr call) {
        if (call.getScope().isPresent()) {
            String scope = call.getScope().get().toString();
            if (scope.matches("[A-Z][a-zA-Z0-9]*")) {
                return "STATIC";
            }
        }
        return "DIRECT";
    }
}
```

---

## Task 7: 创建知识图谱构建服务

**Files:**
- Create: `src/main/java/com/huawei/hisi/knowledgegraph/service/KnowledgeGraphBuilder.java`

**Step 1: 创建知识图谱构建服务**

```java
package com.huawei.hisi.knowledgegraph.service;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.huawei.hisi.knowledgegraph.model.*;
import com.huawei.hisi.knowledgegraph.repository.*;
import com.huawei.hisi.knowledgegraph.scanner.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.io.File;
import java.io.FileInputStream;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * 知识图谱构建服务
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class KnowledgeGraphBuilder {

    private final MethodNodeScanner methodNodeScanner;
    private final CallRelationScanner callRelationScanner;
    private final EntryPointScanner entryPointScanner;
    private final MethodNodeRepository methodNodeRepository;
    private final CallRelationRepository callRelationRepository;
    private final EntryPointRepository entryPointRepository;

    /**
     * 为项目构建知识图谱
     */
    public Map<String, Object> buildKnowledgeGraph(String projectPath) {
        log.info("开始构建知识图谱: {}", projectPath);

        // 1. 清理旧数据
        cleanOldData(projectPath);

        // 2. 扫描所有Java文件
        List<File> javaFiles = findJavaFiles(projectPath);
        log.info("发现 {} 个Java文件", javaFiles.size());

        // 3. 构建类型解析器
        CombinedTypeSolver typeSolver = buildTypeSolver(projectPath);

        // 4. 扫描并收集数据
        List<MethodNode> allMethodNodes = new ArrayList<>();
        List<CallRelation> allCallRelations = new ArrayList<>();
        List<EntryPoint> allEntryPoints = new ArrayList<>();
        Map<String, String> classNameToNodeId = new ConcurrentHashMap<>();

        for (File javaFile : javaFiles) {
            try {
                CompilationUnit cu = parseFile(javaFile, typeSolver);
                String filePath = javaFile.getAbsolutePath();

                // 扫描方法节点
                List<MethodNode> methodNodes = methodNodeScanner.scanMethodNodes(cu, filePath, projectPath);
                allMethodNodes.addAll(methodNodes);

                // 建立类名到节点ID的映射
                methodNodes.forEach(node ->
                    classNameToNodeId.put(node.getClassName() + "." + node.getMethodName(), node.getNodeId())
                );

                // 扫描入口点
                List<EntryPoint> entryPoints = entryPointScanner.scanEntryPoints(cu, projectPath);
                allEntryPoints.addAll(entryPoints);

            } catch (Exception e) {
                log.warn("解析文件失败: {}", javaFile.getPath(), e);
            }
        }

        // 5. 二次扫描，建立调用关系（需要所有方法节点已收集）
        for (File javaFile : javaFiles) {
            try {
                CompilationUnit cu = parseFile(javaFile, typeSolver);
                List<CallRelation> relations = callRelationScanner.scanCallRelations(cu, projectPath, classNameToNodeId);
                allCallRelations.addAll(relations);
            } catch (Exception e) {
                log.warn("解析调用关系失败: {}", javaFile.getPath(), e);
            }
        }

        // 6. 批量保存数据
        methodNodeRepository.batchSave(allMethodNodes);
        callRelationRepository.batchSave(allCallRelations);
        entryPointRepository.batchSave(allEntryPoints);

        Map<String, Object> result = new HashMap<>();
        result.put("methodNodeCount", allMethodNodes.size());
        result.put("callRelationCount", allCallRelations.size());
        result.put("entryPointCount", allEntryPoints.size());

        log.info("知识图谱构建完成: {}", result);
        return result;
    }

    private void cleanOldData(String projectPath) {
        methodNodeRepository.deleteByProjectPath(projectPath);
        callRelationRepository.deleteByProjectPath(projectPath);
        entryPointRepository.deleteByProjectPath(projectPath);
    }

    private List<File> findJavaFiles(String projectPath) {
        try {
            return Files.walk(Paths.get(projectPath))
                .filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".java"))
                .map(Path::toFile)
                .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("扫描Java文件失败", e);
            return Collections.emptyList();
        }
    }

    private CombinedTypeSolver buildTypeSolver(String projectPath) {
        CombinedTypeSolver solver = new CombinedTypeSolver();
        // 添加项目源码路径
        solver.add(new com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver(projectPath));
        // 添加JDK类型解析
        solver.add(new com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver());
        return solver;
    }

    private CompilationUnit parseFile(File file, CombinedTypeSolver typeSolver) throws Exception {
        JavaSymbolSolver symbolSolver = new JavaSymbolSolver(typeSolver);
        JavaParser.getStaticConfiguration().setSymbolResolver(symbolSolver);

        try (FileInputStream fis = new FileInputStream(file)) {
            JavaParser parser = new JavaParser();
            return parser.parse(fis).getResult().orElse(null);
        }
    }
}
```

---

## Task 8: 创建知识图谱 Controller

**Files:**
- Create: `src/main/java/com/huawei/hisi/knowledgegraph/controller/KnowledgeGraphController.java`

**Step 1: 创建 Controller**

```java
package com.huawei.hisi.knowledgegraph.controller;

import com.huawei.hisi.knowledgegraph.service.KnowledgeGraphBuilder;
import com.huawei.hisi.model.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/**
 * 知识图谱 API 控制器
 */
@RestController
@RequestMapping("/api/knowledge-graph")
@Slf4j
@RequiredArgsConstructor
public class KnowledgeGraphController {

    private final KnowledgeGraphBuilder knowledgeGraphBuilder;

    /**
     * 为项目生成知识图谱
     */
    @PostMapping("/generate")
    public ApiResponse<Map<String, Object>> generate(@RequestBody Map<String, String> request) {
        String projectPath = request.get("projectPath");
        if (projectPath == null || projectPath.isEmpty()) {
            return ApiResponse.error(400, "项目路径不能为空");
        }

        try {
            Map<String, Object> result = knowledgeGraphBuilder.buildKnowledgeGraph(projectPath);
            return ApiResponse.success(result);
        } catch (Exception e) {
            log.error("生成知识图谱失败", e);
            return ApiResponse.error(500, "生成失败: " + e.getMessage());
        }
    }

    /**
     * 获取知识图谱状态
     */
    @GetMapping("/status")
    public ApiResponse<Map<String, Object>> getStatus(@RequestParam String projectPath) {
        // 返回知识图谱生成状态
        return ApiResponse.success(Map.of(
            "projectPath", projectPath,
            "status", "not_generated"
        ));
    }

    /**
     * 查询方法的调用者
     */
    @GetMapping("/callers")
    public ApiResponse<?> getCallers(
            @RequestParam String className,
            @RequestParam String methodName,
            @RequestParam String projectPath) {
        // 实现调用者查询
        return ApiResponse.success(null);
    }

    /**
     * 查询方法被调用的位置
     */
    @GetMapping("/callees")
    public ApiResponse<?> getCallees(
            @RequestParam String className,
            @RequestParam String methodName,
            @RequestParam String projectPath) {
        // 实现被调用者查询
        return ApiResponse.success(null);
    }

    /**
     * 查询入口点列表
     */
    @GetMapping("/entry-points")
    public ApiResponse<?> getEntryPoints(
            @RequestParam String projectPath,
            @RequestParam(required = false) String entryType) {
        // 实现入口点查询
        return ApiResponse.success(null);
    }
}
```

---

## Task 9: 创建数据库初始化脚本

**Files:**
- Create: `src/main/resources/db/migration/V003__knowledge_graph_tables.sql`

**Step 1: 创建迁移脚本**

```sql
-- 知识图谱相关表

-- 方法节点表
CREATE TABLE IF NOT EXISTS code_method_node (
    node_id VARCHAR(128) PRIMARY KEY,
    class_name VARCHAR(256) NOT NULL,
    method_name VARCHAR(128) NOT NULL,
    signature VARCHAR(512),
    file_path VARCHAR(512),
    start_line INTEGER,
    end_line INTEGER,
    complexity INTEGER DEFAULT 1,
    thrown_exceptions TEXT,
    caught_exceptions TEXT,
    method_body TEXT,
    project_path VARCHAR(512) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_method_node_class ON code_method_node(class_name);
CREATE INDEX IF NOT EXISTS idx_method_node_project ON code_method_node(project_path);
CREATE INDEX IF NOT EXISTS idx_method_node_method ON code_method_node(method_name);

-- 调用关系表
CREATE TABLE IF NOT EXISTS code_call_relation (
    id BIGSERIAL PRIMARY KEY,
    caller_id VARCHAR(128) NOT NULL,
    callee_id VARCHAR(128) NOT NULL,
    call_type VARCHAR(20) DEFAULT 'DIRECT',
    call_line INTEGER,
    project_path VARCHAR(512) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_call_relation UNIQUE(caller_id, callee_id, call_line)
);

CREATE INDEX IF NOT EXISTS idx_call_relation_caller ON code_call_relation(caller_id);
CREATE INDEX IF NOT EXISTS idx_call_relation_callee ON code_call_relation(callee_id);
CREATE INDEX IF NOT EXISTS idx_call_relation_project ON code_call_relation(project_path);

-- 入口点表
CREATE TABLE IF NOT EXISTS code_entry_point (
    id BIGSERIAL PRIMARY KEY,
    node_id VARCHAR(128) NOT NULL,
    entry_type VARCHAR(32) NOT NULL,
    entry_key VARCHAR(256),
    entry_info TEXT,
    project_path VARCHAR(512) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_entry_point_type ON code_entry_point(entry_type);
CREATE INDEX IF NOT EXISTS idx_entry_point_project ON code_entry_point(project_path);
CREATE INDEX IF NOT EXISTS idx_entry_point_node ON code_entry_point(node_id);
```

---

## Task 10: 前端 API 封装

**Files:**
- Create: `src/api/knowledgeGraph.ts` (前端)

**Step 1: 创建 API 封装**

```typescript
// src/api/knowledgeGraph.ts
import request from '@/utils/request'

export interface KnowledgeGraphStatus {
  projectPath: string
  status: 'not_generated' | 'generating' | 'generated' | 'failed'
  methodNodeCount?: number
  callRelationCount?: number
  entryPointCount?: number
  generatedAt?: string
}

export interface GenerateRequest {
  projectPath: string
}

export const knowledgeGraphApi = {
  /**
   * 生成知识图谱
   */
  generate(data: GenerateRequest) {
    return request.post<{ methodNodeCount: number; callRelationCount: number; entryPointCount: number }>(
      '/api/knowledge-graph/generate',
      data
    )
  },

  /**
   * 获取状态
   */
  getStatus(projectPath: string) {
    return request.get<KnowledgeGraphStatus>('/api/knowledge-graph/status', {
      params: { projectPath }
    })
  },

  /**
   * 查询调用者
   */
  getCallers(className: string, methodName: string, projectPath: string) {
    return request.get('/api/knowledge-graph/callers', {
      params: { className, methodName, projectPath }
    })
  },

  /**
   * 查询被调用者
   */
  getCallees(className: string, methodName: string, projectPath: string) {
    return request.get('/api/knowledge-graph/callees', {
      params: { className, methodName, projectPath }
    })
  },

  /**
   * 查询入口点
   */
  getEntryPoints(projectPath: string, entryType?: string) {
    return request.get('/api/knowledge-graph/entry-points', {
      params: { projectPath, entryType }
    })
  }
}
```

---

## Task 11: 前端项目管理页面集成

**Files:**
- Modify: `src/views/project/ProjectList.vue`

**Step 1: 添加知识图谱生成按钮**

在项目管理列表的操作列中添加"生成知识图谱"按钮，与现有的"生成调用链"按钮并列。

```vue
<!-- 在操作列模板中添加 -->
<el-table-column label="操作" width="300">
  <template #default="{ row }">
    <el-button size="small" @click="generateCallChain(row)">生成调用链</el-button>
    <el-button size="small" type="success" @click="generateKnowledgeGraph(row)">生成知识图谱</el-button>
  </template>
</el-table-column>
```

**Step 2: 添加生成方法**

```typescript
const generateKnowledgeGraph = async (project: ProjectInfo) => {
  loading.value = true
  try {
    const result = await knowledgeGraphApi.generate({
      projectPath: project.path
    })
    ElMessage.success(`知识图谱生成完成: ${result.methodNodeCount} 个方法节点`)
  } catch (error) {
    ElMessage.error('知识图谱生成失败')
  } finally {
    loading.value = false
  }
}
```

---

## 任务清单汇总

| Phase | Task | 描述 | 预计时间 |
|-------|------|------|----------|
| 1 | Task 1 | 创建入口类型枚举 | 5min |
| 1 | Task 2 | 创建知识图谱数据模型 | 15min |
| 1 | Task 3 | 创建知识图谱 Repository | 20min |
| 2 | Task 4 | 创建入口点扫描器 | 20min |
| 2 | Task 5 | 创建方法节点扫描器 | 15min |
| 2 | Task 6 | 创建调用关系扫描器 | 20min |
| 3 | Task 7 | 创建知识图谱构建服务 | 30min |
| 3 | Task 8 | 创建知识图谱 Controller | 15min |
| 3 | Task 9 | 创建数据库初始化脚本 | 10min |
| 4 | Task 10 | 前端 API 封装 | 10min |
| 4 | Task 11 | 前端项目管理页面集成 | 20min |

**总计预计时间**: 约 3 小时

---

## 验证清单

- [ ] 数据库表创建成功
- [ ] 后端编译通过
- [ ] API 接口可访问
- [ ] 前端按钮显示正常
- [ ] 知识图谱生成功能正常
- [ ] 不影响原有调用链生成功能
