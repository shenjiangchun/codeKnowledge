# 知识图谱 Neo4j 迁移与 GraphRAG 实施计划

## 文档信息
- **创建日期**: 2026-04-17
- **版本**: 1.0
- **设计文档**: [2026-04-17-neo4j-graphrag-design.md](./2026-04-17-neo4j-graphrag-design.md)

---

## 阶段 1: Neo4j 环境搭建

### 任务 1.1: 安装 Neo4j

#### 步骤 1.1.1: Docker 方式安装 Neo4j

**执行命令**:
```bash
docker run -d \
  --name neo4j \
  -p 7474:7474 -p 7687:7687 \
  -e NEO4J_AUTH=neo4j/***REMOVED_NEO4J_PASSWORD*** \
  -e NEO4J_PLUGINS='["apoc", "graph-data-science"]' \
  -e NEO4J_dbms_memory_heap_initial__size=512m \
  -e NEO4J_dbms_memory_heap_max__size=2G \
  -v neo4j_data:/data \
  -v neo4j_logs:/logs \
  neo4j:5.15.0
```

#### 步骤 1.1.2: 验证 Neo4j 安装

**验证**: 浏览器访问 http://localhost:7474，使用 neo4j/***REMOVED_NEO4J_PASSWORD*** 登录

---

### 任务 1.2: 安装 GenAI 插件（嵌入生成）

#### 步骤 1.2.1: 下载 GenAI 插件

```bash
# 进入 Neo4j 容器
docker exec -it neo4j bash

# 下载 GenAI 插件
cd plugins
wget https://github.com/neo4j-labs/genai-plugin/releases/download/1.0.0/neo4j-genai-plugin-1.0.0.jar

# 重启容器
docker restart neo4j
```

#### 步骤 1.2.2: 验证插件安装

**Cypher 测试**:
```cypher
// 验证 APOC
RETURN apoc.version();

// 验证 GDS
RETURN gds.version();

// 验证 GenAI（需配置 API）
// RETURN genai.vector.encode("test", "HuggingFace", {model: "all-MiniLM-L6-v2"});
```

---

### 任务 1.3: 创建数据库约束和索引

**文件**: `docs/neo4j/schema/01_constraints.cypher`

```cypher
// ============================================
// 节点唯一性约束
// ============================================

// Method 节点
CREATE CONSTRAINT method_node_id_unique IF NOT EXISTS
FOR (m:Method) REQUIRE m.nodeId IS UNIQUE;

CREATE CONSTRAINT method_node_id_key IF NOT EXISTS
FOR (m:Method) REQUIRE m.nodeId IS NODE KEY;

// EntryPoint 节点
CREATE CONSTRAINT entry_point_id_unique IF NOT EXISTS
FOR (e:EntryPoint) REQUIRE e.id IS UNIQUE;

// Interface 节点
CREATE CONSTRAINT interface_name_unique IF NOT EXISTS
FOR (i:Interface) REQUIRE i.interfaceName IS UNIQUE;

// Implementation 节点
CREATE CONSTRAINT impl_name_unique IF NOT EXISTS
FOR (impl:Implementation) REQUIRE impl.className IS UNIQUE;

// Mapper 节点
CREATE CONSTRAINT mapper_interface_unique IF NOT EXISTS
FOR (m:Mapper) REQUIRE m.mapperInterface IS UNIQUE;

// SqlStatement 节点
CREATE CONSTRAINT sql_id_unique IF NOT EXISTS
FOR (s:SqlStatement) REQUIRE s.sqlId IS UNIQUE;

// Service 节点
CREATE CONSTRAINT service_name_unique IF NOT EXISTS
FOR (s:Service) REQUIRE s.name IS UNIQUE;

// Project 节点
CREATE CONSTRAINT project_path_unique IF NOT EXISTS
FOR (p:Project) REQUIRE p.projectPath IS UNIQUE;

// ============================================
// 向量索引
// ============================================

CREATE VECTOR INDEX method_vector_index IF NOT EXISTS
FOR (m:Method)
ON m.fusedEmbedding
OPTIONS {
  indexConfig: {
    `vector.dimensions`: 384,
    `vector.similarity_function`: 'cosine'
  }
};

// ============================================
// 全文索引（关键词搜索）
// ============================================

CREATE FULLTEXT INDEX method_fulltext_index IF NOT EXISTS
FOR (m:Method)
ON EACH [m.className, m.methodName, m.signature, m.commentSummary];

CREATE FULLTEXT INDEX entry_point_fulltext_index IF NOT EXISTS
FOR (e:EntryPoint)
ON EACH [e.entryKey, e.entryType];
```

---

## 阶段 2: Spring Boot 集成 Neo4j

### 任务 2.1: 添加 Maven 依赖

#### 步骤 2.1.1: 更新 pom.xml

**文件**: `pom.xml`

```xml
<!-- Neo4j Java Driver -->
<dependency>
    <groupId>org.neo4j.driver</groupId>
    <artifactId>neo4j-java-driver</artifactId>
    <version>5.15.0</version>
</dependency>

<!-- Spring Data Neo4j -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-neo4j</artifactId>
</dependency>

<!-- ONNX Runtime (本地嵌入生成) -->
<dependency>
    <groupId>com.microsoft.onnxruntime</groupId>
    <artifactId>onnxruntime</artifactId>
    <version>1.18.0</version>
</dependency>

<!-- DJL (可选，备用嵌入方案) -->
<dependency>
    <groupId>ai.djl.huggingface</groupId>
    <artifactId>tokenizers</artifactId>
    <version>0.29.0</version>
</dependency>
```

---

### 任务 2.2: 创建 Neo4j 配置类

#### 步骤 2.2.1: 创建配置类

**文件**: `src/main/java/com/huawei/hisi/config/Neo4jConfig.java`

```java
package com.huawei.hisi.config;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.neo4j.core.DatabaseSelectionProvider;
import org.springframework.data.neo4j.core.transaction.Neo4jTransactionManager;

@Configuration
public class Neo4jConfig {

    @Value("${neo4j.uri:bolt://localhost:7687}")
    private String uri;

    @Value("${neo4j.username:neo4j}")
    private String username;

    @Value("${neo4j.password:neo4j}")
    private String password;

    @Bean
    public Driver neo4jDriver() {
        return GraphDatabase.driver(uri, AuthTokens.basic(username, password));
    }

    @Bean
    public Neo4jTransactionManager transactionManager(Driver driver) {
        return new Neo4jTransactionManager(driver);
    }
}
```

#### 步骤 2.2.2: 更新 application.yml

**文件**: `src/main/resources/application.yml`

```yaml
# Neo4j 配置
neo4j:
  uri: ${NEO4J_URI:bolt://localhost:7687}
  username: ${NEO4J_USERNAME:neo4j}
  password: ${NEO4J_PASSWORD:***REMOVED_NEO4J_PASSWORD***}

# 向量搜索配置
vector:
  search:
    embedding-model: all-MiniLM-L6-v2
    embedding-dimension: 384
    vector-weight: 0.7
    graph-weight: 0.3
    default-top-k: 10
    max-depth: 3
```

---

### 任务 2.3: 创建 Neo4j 实体类

#### 步骤 2.3.1: 创建 MethodNode 实体

**文件**: `src/main/java/com/huawei/hisi/neo4j/model/MethodNode.java`

```java
package com.huawei.hisi.neo4j.model;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import org.springframework.data.neo4j.core.schema.*;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Node("Method")
public class MethodNode {

    @Id
    @Property("nodeId")
    private String nodeId;

    @Property("className")
    private String className;

    @Property("methodName")
    private String methodName;

    @Property("signature")
    private String signature;

    @Property("filePath")
    private String filePath;

    @Property("startLine")
    private Integer startLine;

    @Property("endLine")
    private Integer endLine;

    @Property("complexity")
    private Integer complexity;

    @Property("thrownExceptions")
    private List<String> thrownExceptions;

    @Property("caughtExceptions")
    private List<String> caughtExceptions;

    @Property("methodBody")
    private String methodBody;

    @Property("projectPath")
    private String projectPath;

    @Property("commentSummary")
    private String commentSummary;

    @Property("serviceName")
    private String serviceName;

    @Property("annotations")
    private String annotations;

    // 向量属性
    @Property("embedding")
    private float[] embedding;

    @Property("graphEmbedding")
    private float[] graphEmbedding;

    @Property("fusedEmbedding")
    private float[] fusedEmbedding;

    // 关系
    @Relationship(type = "CALLS", direction = Relationship.Direction.OUTGOING)
    private List<CallRelation> calls;

    @Relationship(type = "CALLS", direction = Relationship.Direction.INCOMING)
    private List<CallRelation> calledBy;

    @Relationship(type = "BELONGS_TO", direction = Relationship.Direction.OUTGOING)
    private ServiceNode service;
}
```

#### 步骤 2.3.2: 创建其他实体

**文件**: `src/main/java/com/huawei/hisi/neo4j/model/CallRelation.java`

```java
package com.huawei.hisi.neo4j.model;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import org.springframework.data.neo4j.core.schema.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@RelationshipProperties
public class CallRelation {

    @Id
    @GeneratedValue
    private Long id;

    @Property("callType")
    private String callType;

    @Property("callLine")
    private Integer callLine;

    @Property("bridgeType")
    private String bridgeType;

    @Property("sqlId")
    private String sqlId;

    @Property("targetService")
    private String targetService;

    @Property("targetEndpoint")
    private String targetEndpoint;

    @TargetNode
    private MethodNode callee;
}
```

**文件**: `src/main/java/com/huawei/hisi/neo4j/model/EntryPointNode.java`

```java
package com.huawei.hisi.neo4j.model;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import org.springframework.data.neo4j.core.schema.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Node("EntryPoint")
public class EntryPointNode {

    @Id
    @GeneratedValue
    private Long id;

    @Property("nodeId")
    private String nodeId;

    @Property("entryType")
    private String entryType;

    @Property("entryKey")
    private String entryKey;

    @Property("entryInfo")
    private String entryInfo;

    @Property("projectPath")
    private String projectPath;

    @Relationship(type = "ENTRY_OF", direction = Relationship.Direction.OUTGOING)
    private MethodNode method;
}
```

**文件**: `src/main/java/com/huawei/hisi/neo4j/model/ServiceNode.java`

```java
package com.huawei.hisi.neo4j.model;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import org.springframework.data.neo4j.core.schema.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Node("Service")
public class ServiceNode {

    @Id
    @Property("name")
    private String name;

    @Property("projectPath")
    private String projectPath;
}
```

---

### 任务 2.4: 创建 Repository 层

#### 步骤 2.4.1: 创建 MethodNodeRepository

**文件**: `src/main/java/com/huawei/hisi/neo4j/repository/MethodNodeRepository.java`

```java
package com.huawei.hisi.neo4j.repository;

import com.huawei.hisi.neo4j.model.MethodNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface MethodNodeRepository extends Neo4jRepository<MethodNode, String> {

    Optional<MethodNode> findByNodeId(String nodeId);

    List<MethodNode> findByProjectPath(String projectPath);

    List<MethodNode> findByServiceName(String serviceName);

    List<MethodNode> findByClassName(String className);

    @Query("MATCH (m:Method) WHERE m.className CONTAINS $keyword OR m.methodName CONTAINS $keyword RETURN m")
    List<MethodNode> searchByKeyword(@Param("keyword") String keyword);

    @Query("""
        MATCH (m:Method)-[:BELONGS_TO]->(s:Service)
        WHERE s.name = $serviceName
        AND (m.className CONTAINS $keyword OR m.methodName CONTAINS $keyword)
        RETURN m
        """)
    List<MethodNode> searchByServiceAndKeyword(
        @Param("serviceName") String serviceName,
        @Param("keyword") String keyword
    );

    // 向量搜索
    @Query("""
        CALL db.index.vector.queryNodes('method_vector_index', $topK, $embedding)
        YIELD node AS m, score
        WHERE m.projectPath = $projectPath
        RETURN m, score
        ORDER BY score DESC
        LIMIT $topK
        """)
    List<MethodNode> vectorSearch(
        @Param("embedding") float[] embedding,
        @Param("projectPath") String projectPath,
        @Param("topK") int topK
    );

    // 获取调用者
    @Query("MATCH (caller:Method)-[:CALLS]->(m:Method {nodeId: $nodeId}) RETURN caller")
    List<MethodNode> findCallers(@Param("nodeId") String nodeId);

    // 获取被调用者
    @Query("MATCH (m:Method {nodeId: $nodeId})-[:CALLS]->(callee:Method) RETURN callee")
    List<MethodNode> findCallees(@Param("nodeId") String nodeId);

    // 图遍历扩展
    @Query("""
        MATCH (start:Method {nodeId: $nodeId})-[:CALLS*1..$depth]->(related:Method)
        WHERE related.projectPath = $projectPath
        RETURN DISTINCT related
        """)
    List<MethodNode> expandDownstream(
        @Param("nodeId") String nodeId,
        @Param("depth") int depth,
        @Param("projectPath") String projectPath
    );

    // 向上游追踪
    @Query("""
        MATCH (related:Method)-[:CALLS*1..$depth]->(target:Method {nodeId: $nodeId})
        WHERE related.projectPath = $projectPath
        RETURN DISTINCT related
        """)
    List<MethodNode> expandUpstream(
        @Param("nodeId") String nodeId,
        @Param("depth") int depth,
        @Param("projectPath") String projectPath
    );
}
```

---

## 阶段 3: 数据迁移

### 任务 3.1: 创建数据迁移服务

#### 步骤 3.1.1: 创建迁移服务

**文件**: `src/main/java/com/huawei/hisi/neo4j/service/DataMigrationService.java`

```java
package com.huawei.hisi.neo4j.service;

import com.huawei.hisi.neo4j.model.*;
import com.huawei.hisi.neo4j.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class DataMigrationService {

    private final JdbcTemplate jdbcTemplate;
    private final MethodNodeRepository methodNodeRepository;
    private final EntryPointRepository entryPointRepository;
    private final ServiceRepository serviceRepository;

    /**
     * 迁移方法节点
     */
    @Transactional
    public int migrateMethodNodes(String projectPath) {
        String sql = """
            SELECT node_id, class_name, method_name, signature, file_path,
                   start_line, end_line, complexity, thrown_exceptions, caught_exceptions,
                   method_body, project_path, annotations
            FROM code_method_node
            WHERE project_path = ?
            """;

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, projectPath);
        int count = 0;

        for (Map<String, Object> row : rows) {
            MethodNode node = MethodNode.builder()
                .nodeId((String) row.get("node_id"))
                .className((String) row.get("class_name"))
                .methodName((String) row.get("method_name"))
                .signature((String) row.get("signature"))
                .filePath((String) row.get("file_path"))
                .startLine((Integer) row.get("start_line"))
                .endLine((Integer) row.get("end_line"))
                .complexity((Integer) row.get("complexity"))
                .projectPath((String) row.get("project_path"))
                .annotations((String) row.get("annotations"))
                .serviceName(extractServiceName((String) row.get("project_path")))
                .build();

            methodNodeRepository.save(node);
            count++;

            if (count % 1000 == 0) {
                log.info("已迁移 {} 个方法节点", count);
            }
        }

        log.info("方法节点迁移完成，共 {} 条", count);
        return count;
    }

    /**
     * 迁移调用关系
     */
    @Transactional
    public int migrateCallRelations(String projectPath) {
        String sql = """
            SELECT caller_id, callee_id, call_type, call_line,
                   bridge_type, sql_id, target_service, target_endpoint
            FROM code_call_relation
            WHERE project_path = ?
            """;

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, projectPath);
        int count = 0;

        for (Map<String, Object> row : rows) {
            String callerId = (String) row.get("caller_id");
            String calleeId = (String) row.get("callee_id");

            Optional<MethodNode> callerOpt = methodNodeRepository.findByNodeId(callerId);
            Optional<MethodNode> calleeOpt = methodNodeRepository.findByNodeId(calleeId);

            if (callerOpt.isPresent() && calleeOpt.isPresent()) {
                // 创建关系通过 Cypher
                createCallRelation(callerId, calleeId, row);
                count++;
            }

            if (count % 1000 == 0) {
                log.info("已迁移 {} 条调用关系", count);
            }
        }

        log.info("调用关系迁移完成，共 {} 条", count);
        return count;
    }

    private void createCallRelation(String callerId, String calleeId, Map<String, Object> row) {
        // 使用 Neo4j Driver 直接执行 Cypher
        String cypher = """
            MATCH (caller:Method {nodeId: $callerId})
            MATCH (callee:Method {nodeId: $calleeId})
            CREATE (caller)-[:CALLS {
                callType: $callType,
                callLine: $callLine,
                bridgeType: $bridgeType,
                sqlId: $sqlId,
                targetService: $targetService,
                targetEndpoint: $targetEndpoint
            }]->(callee)
            """;
        // 执行 Cypher...
    }

    private String extractServiceName(String projectPath) {
        // 从项目路径提取服务名
        // 如: C:\projects\order-service -> order-service
        if (projectPath == null) return "unknown";
        String[] parts = projectPath.split("[/\\\\]");
        return parts[parts.length - 1];
    }
}
```

---

## 阶段 4: 代理向量生成

### 任务 4.1: 实现注释提取

#### 步骤 4.1.1: 创建注释提取工具

**文件**: `src/main/java/com/huawei/hisi/knowledgegraph/util/CommentExtractor.java`

```java
package com.huawei.hisi.knowledgegraph.util;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.comments.JavadocComment;
import lombok.extern.slf4j.Slf4j;

import java.io.FileInputStream;
import java.nio.file.Path;
import java.util.Optional;

@Slf4j
public class CommentExtractor {

    private static final JavaParser parser = new JavaParser();

    /**
     * 从 Java 源文件提取方法注释摘要
     */
    public static MethodCommentResult extractMethodComments(Path javaFile) {
        try {
            FileInputStream in = new FileInputStream(javaFile.toFile());
            CompilationUnit cu = parser.parse(in).getResult().orElse(null);

            if (cu == null) return new MethodCommentResult();

            MethodCommentResult result = new MethodCommentResult();

            cu.findAll(MethodDeclaration.class).forEach(method -> {
                String methodSignature = method.getDeclarationAsString(false, false);
                String commentSummary = extractCommentText(method);

                result.addMethodComment(methodSignature, commentSummary);
            });

            return result;
        } catch (Exception e) {
            log.error("解析文件失败: {}", javaFile, e);
            return new MethodCommentResult();
        }
    }

    /**
     * 提取方法注释文本
     */
    private static String extractCommentText(MethodDeclaration method) {
        // 1. 尝试获取 Javadoc
        Optional<JavadocComment> javadoc = method.getJavadocComment();
        if (javadoc.isPresent()) {
            return parseJavadoc(javadoc.get());
        }

        // 2. 尝试获取行注释
        Optional<Comment> comment = method.getComment();
        if (comment.isPresent()) {
            return comment.get().getContent().trim();
        }

        // 3. 从方法名推断
        return inferFromMethodName(method.getNameAsString());
    }

    /**
     * 解析 Javadoc 提取描述
     */
    private static String parseJavadoc(JavadocComment javadoc) {
        String content = javadoc.parse().getDescription().toText();
        // 截取第一段作为摘要
        int firstPeriod = content.indexOf('。');
        int firstNewline = content.indexOf('\n');
        int endPos = Math.min(
            firstPeriod > 0 ? firstPeriod + 1 : content.length(),
            firstNewline > 0 ? firstNewline : content.length()
        );
        return content.substring(0, Math.min(endPos, 200)).trim();
    }

    /**
     * 从方法名推断功能描述
     */
    private static String inferFromMethodName(String methodName) {
        // 驼峰转空格 + 中文描述
        // getUserName -> 获取 用户名
        // createUser -> 创建 用户
        // deleteUser -> 删除 用户
        // updateStatus -> 更新 状态
        // validateToken -> 校验 令牌

        StringBuilder result = new StringBuilder();

        // 常见前缀映射
        Map<String, String> prefixMap = Map.of(
            "get", "获取", "find", "查询", "query", "查询",
            "create", "创建", "add", "添加", "insert", "插入",
            "update", "更新", "modify", "修改", "edit", "编辑",
            "delete", "删除", "remove", "移除",
            "validate", "校验", "check", "检查", "verify", "验证",
            "process", "处理", "handle", "处理", "execute", "执行",
            "calculate", "计算", "compute", "计算",
            "parse", "解析", "format", "格式化", "convert", "转换",
            "send", "发送", "receive", "接收", "publish", "发布",
            "load", "加载", "save", "保存", "init", "初始化"
        );

        for (Map.Entry<String, String> entry : prefixMap.entrySet()) {
            if (methodName.toLowerCase().startsWith(entry.getKey())) {
                result.append(entry.getValue()).append(" ");
                String remainder = methodName.substring(entry.getKey().length());
                result.append(camelToReadable(remainder));
                return result.toString();
            }
        }

        // 无匹配前缀，直接转可读形式
        return camelToReadable(methodName);
    }

    /**
     * 驼峰转可读形式
     */
    private static String camelToReadable(String str) {
        if (str == null || str.isEmpty()) return "";
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (Character.isUpperCase(c) && i > 0) {
                result.append(' ');
            }
            result.append(Character.toLowerCase(c));
        }
        return result.toString();
    }

    public static class MethodCommentResult {
        private final Map<String, String> methodComments = new HashMap<>();

        public void addMethodComment(String signature, String comment) {
            methodComments.put(signature, comment);
        }

        public String getComment(String signature) {
            return methodComments.getOrDefault(signature, "");
        }

        public Map<String, String> getAllComments() {
            return methodComments;
        }
    }
}
```

---

### 任务 4.2: 实现代理向量生成服务

#### 步骤 4.2.1: 创建代理向量服务

**文件**: `src/main/java/com/huawei/hisi/neo4j/service/ProxyVectorService.java`

```java
package com.huawei.hisi.neo4j.service;

import com.huawei.hisi.neo4j.model.MethodNode;
import com.huawei.hisi.neo4j.repository.MethodNodeRepository;
import com.microsoft.onnxruntime.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.LongBuffer;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProxyVectorService {

    private final MethodNodeRepository methodNodeRepository;

    @Value("${vector.search.embedding-dimension:384}")
    private int embeddingDimension;

    @Value("${vector.search.embedding-model:all-MiniLM-L6-v2}")
    private String embeddingModel;

    private OrtEnvironment ortEnv;
    private OrtSession ortSession;

    /**
     * 初始化 ONNX Runtime
     */
    public void initOnnxRuntime(String modelPath) throws OrtException {
        ortEnv = OrtEnvironment.getEnvironment();
        ortSession = ortEnv.createSession(modelPath, new OrtSession.SessionOptions());
    }

    /**
     * 生成代理向量输入文本
     * 格式: "{className} {methodName} {signature} {commentSummary} {serviceName}"
     */
    public String generateProxyVectorInput(MethodNode node) {
        StringBuilder sb = new StringBuilder();

        // 1. 全限定类名
        if (node.getClassName() != null) {
            sb.append(node.getClassName()).append(" ");
        }

        // 2. 方法名
        if (node.getMethodName() != null) {
            sb.append(node.getMethodName()).append(" ");
        }

        // 3. 方法签名
        if (node.getSignature() != null) {
            // 简化签名，去除参数类型中的包名
            String simplifiedSignature = simplifySignature(node.getSignature());
            sb.append(simplifiedSignature).append(" ");
        }

        // 4. 方法注释摘要
        if (node.getCommentSummary() != null && !node.getCommentSummary().isEmpty()) {
            sb.append(node.getCommentSummary()).append(" ");
        }

        // 5. 所属微服务名
        if (node.getServiceName() != null) {
            sb.append(node.getServiceName());
        }

        return sb.toString().trim();
    }

    /**
     * 简化方法签名
     * com.example.User getUser(java.lang.String, int) -> getUser(String, int)
     */
    private String simplifySignature(String signature) {
        // 移除返回值类型
        // 移除参数类型的包名
        return signature
            .replaceAll("[\\w.]+\\s+(\\w+)\\s*\\(", "$1(")
            .replaceAll("java\\.lang\\.", "")
            .replaceAll("java\\.util\\.", "")
            .replaceAll("[\\w]+\\.([\\w]+)", "$1");
    }

    /**
     * 批量生成代理向量
     */
    public int generateEmbeddings(String projectPath) {
        List<MethodNode> methods = methodNodeRepository.findByProjectPath(projectPath);
        int count = 0;

        List<MethodNode> toSave = new ArrayList<>();

        for (MethodNode method : methods) {
            String inputText = generateProxyVectorInput(method);
            float[] embedding = generateEmbedding(inputText);

            if (embedding != null) {
                method.setEmbedding(embedding);
                toSave.add(method);
                count++;
            }

            // 批量保存
            if (toSave.size() >= 100) {
                methodNodeRepository.saveAll(toSave);
                toSave.clear();
                log.info("已生成 {} 个代理向量", count);
            }
        }

        // 保存剩余
        if (!toSave.isEmpty()) {
            methodNodeRepository.saveAll(toSave);
        }

        log.info("代理向量生成完成，共 {} 个", count);
        return count;
    }

    /**
     * 使用 ONNX Runtime 生成嵌入向量
     */
    public float[] generateEmbedding(String text) {
        try {
            // Tokenization (简化版，实际应使用 DJL Tokenizer)
            long[] tokenIds = tokenize(text);

            // 创建输入张量
            OnnxTensor inputTensor = OnnxTensor.createTensor(ortEnv, new long[][]{tokenIds});

            // 推理
            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put("input_ids", inputTensor);

            OrtSession.Result result = ortSession.run(inputs);

            // 获取输出
            float[][][] output = (float[][][]) result.get(0).getValue();

            // 平均池化
            return meanPooling(output[0]);

        } catch (Exception e) {
            log.error("生成嵌入向量失败: {}", text, e);
            return null;
        }
    }

    /**
     * 简单分词（实际应使用 HuggingFace Tokenizer）
     */
    private long[] tokenize(String text) {
        // 这里使用简化版本，实际应加载 tokenizer.json
        // 将文本转换为 token IDs
        return text.chars().asLongStream().limit(512).toArray();
    }

    /**
     * 平均池化
     */
    private float[] meanPooling(float[][] tokenEmbeddings) {
        int dim = tokenEmbeddings[0].length;
        float[] result = new float[dim];

        for (float[] tokenEmb : tokenEmbeddings) {
            for (int i = 0; i < dim; i++) {
                result[i] += tokenEmb[i];
            }
        }

        for (int i = 0; i < dim; i++) {
            result[i] /= tokenEmbeddings.length;
        }

        return result;
    }
}
```

---

## 阶段 5: GraphSAGE 图嵌入

### 任务 5.1: 配置 GDS 插件

#### 步骤 5.1.1: 投影图结构

**Cypher 脚本**: `docs/neo4j/gds/01_project_graph.cypher`

```cypher
// 投影调用图
CALL gds.graph.project(
  'callGraph',
  {
    Method: {
      label: 'Method',
      properties: ['complexity']
    }
  },
  {
    CALLS: {
      orientation: 'NATURAL',
      aggregation: 'SINGLE'
    }
  }
);
```

#### 步骤 5.1.2: 训练 GraphSAGE 模型

**Cypher 脚本**: `docs/neo4j/gds/02_train_graphsage.cypher`

```cypher
// 训练 GraphSAGE 模型
CALL gds.beta.graphSage.train('callGraph', {
  modelName: 'methodGraphEmbedding',
  featureProperties: ['complexity'],
  embeddingDimension: 128,
  epochs: 20,
  aggregation: 'mean',
  sampleSizes: [25, 10],
  learningRate: 0.1,
  degreeCutOff: 0
})
YIELD modelInfo
RETURN modelInfo;
```

#### 步骤 5.1.3: 生成图嵌入

**Cypher 脚本**: `docs/neo4j/gds/03_generate_embeddings.cypher`

```cypher
// 生成图嵌入并存储到节点
CALL gds.beta.graphSage.inference('callGraph', 'methodGraphEmbedding')
YIELD nodeId, embedding
MATCH (m:Method)
WHERE elementId(m) = nodeId
SET m.graphEmbedding = embedding
RETURN count(m) as updatedCount;
```

---

### 任务 5.2: 实现向量融合服务

#### 步骤 5.2.1: 创建向量融合服务

**文件**: `src/main/java/com/huawei/hisi/neo4j/service/VectorFusionService.java`

```java
package com.huawei.hisi.neo4j.service;

import com.huawei.hisi.neo4j.model.MethodNode;
import com.huawei.hisi.neo4j.repository.MethodNodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class VectorFusionService {

    private final MethodNodeRepository methodNodeRepository;

    @Value("${vector.search.vector-weight:0.7}")
    private double vectorWeight;

    @Value("${vector.search.graph-weight:0.3}")
    private double graphWeight;

    @Value("${vector.search.embedding-dimension:384}")
    private int embeddingDimension;

    /**
     * 融合代理向量和图嵌入
     * fusedEmbedding = vectorWeight * embedding + graphWeight * graphEmbedding
     */
    public int fuseEmbeddings(String projectPath) {
        List<MethodNode> methods = methodNodeRepository.findByProjectPath(projectPath);
        int count = 0;

        for (MethodNode method : methods) {
            float[] embedding = method.getEmbedding();
            float[] graphEmbedding = method.getGraphEmbedding();

            if (embedding != null) {
                float[] fused;

                if (graphEmbedding != null && graphEmbedding.length == 128) {
                    fused = fuseWithGraphEmbedding(embedding, graphEmbedding);
                } else {
                    // 无图嵌入，仅使用代理向量
                    fused = embedding.clone();
                }

                method.setFusedEmbedding(fused);
                count++;
            }
        }

        methodNodeRepository.saveAll(methods);
        log.info("向量融合完成，共 {} 个节点", count);
        return count;
    }

    /**
     * 融合代理向量和图嵌入
     */
    private float[] fuseWithGraphEmbedding(float[] embedding, float[] graphEmbedding) {
        int embDim = embedding.length;
        int graphDim = graphEmbedding.length;

        float[] fused = new float[embDim];

        // 前 graphDim 维：加权融合
        for (int i = 0; i < graphDim && i < embDim; i++) {
            fused[i] = (float) (vectorWeight * embedding[i] + graphWeight * graphEmbedding[i]);
        }

        // 剩余维度：仅使用代理向量
        for (int i = graphDim; i < embDim; i++) {
            fused[i] = (float) (vectorWeight * embedding[i]);
        }

        return fused;
    }
}
```

---

## 阶段 6: 混合检索实现

### 任务 6.1: 创建意图识别服务

#### 步骤 6.1.1: 创建意图模型

**文件**: `src/main/java/com/huawei/hisi/neo4j/model/QueryIntent.java`

```java
package com.huawei.hisi.neo4j.model;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueryIntent {
    private String entity;          // 核心实体：订单、用户、库存
    private String methodType;      // 方法类型：核心入口、工具方法、异步任务
    private String serviceName;     // 微服务名：order-service
    private List<String> keywords;  // 关键词列表
}
```

#### 步骤 6.1.2: 创建意图识别服务

**文件**: `src/main/java/com/huawei/hisi/neo4j/service/IntentRecognitionService.java`

```java
package com.huawei.hisi.neo4j.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.neo4j.model.QueryIntent;
import com.huawei.hisi.service.LLMService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class IntentRecognitionService {

    private final LLMService llmService;
    private final ObjectMapper objectMapper;

    private static final String INTENT_PROMPT_TEMPLATE = """
你是一个Java代码检索助手，需要将用户的自然语言问题转换为结构化的查询条件。
请严格按照以下JSON格式输出，不要输出任何其他内容：
{
  "entity": "要查询的核心实体（如订单、用户、库存）",
  "methodType": "方法类型（如核心入口、工具方法、异步任务、定时任务）",
  "serviceName": "所属微服务包名（如order-service、user-service、stock-service）",
  "keywords": ["关键词1", "关键词2"]
}
注意：
1. 如果无法识别某个字段，请将其设为null
2. serviceName 应该是微服务名称格式，如 xxx-service
3. keywords 应该是可能出现在方法名或类名中的关键词

用户问题：%s
""";

    /**
     * 解析用户查询意图
     */
    public QueryIntent parseIntent(String userQuery) {
        try {
            String prompt = String.format(INTENT_PROMPT_TEMPLATE, userQuery);
            String response = llmService.generateText(prompt);

            // 提取 JSON 部分
            String json = extractJson(response);

            // 解析为 QueryIntent 对象
            return objectMapper.readValue(json, QueryIntent.class);

        } catch (Exception e) {
            log.error("意图解析失败: {}", userQuery, e);
            // 返回默认意图
            return QueryIntent.builder()
                .entity(null)
                .methodType(null)
                .serviceName(null)
                .keywords(java.util.Collections.emptyList())
                .build();
        }
    }

    /**
     * 从响应中提取 JSON
     */
    private String extractJson(String response) {
        // 尝试找到 JSON 对象
        int start = response.indexOf('{');
        int end = response.lastIndexOf('}');

        if (start >= 0 && end > start) {
            return response.substring(start, end + 1);
        }

        return response;
    }
}
```

---

### 任务 6.2: 创建混合检索服务

#### 步骤 6.2.1: 创建检索服务

**文件**: `src/main/java/com/huawei/hisi/neo4j/service/HybridSearchService.java`

```java
package com.huawei.hisi.neo4j.service;

import com.huawei.hisi.neo4j.model.MethodNode;
import com.huawei.hisi.neo4j.model.QueryIntent;
import com.huawei.hisi.neo4j.repository.MethodNodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class HybridSearchService {

    private final MethodNodeRepository methodNodeRepository;
    private final IntentRecognitionService intentRecognitionService;
    private final ProxyVectorService proxyVectorService;

    @Value("${vector.search.default-top-k:10}")
    private int defaultTopK;

    @Value("${vector.search.max-depth:3}")
    private int maxDepth;

    /**
     * 混合检索入口
     * 三层检索: 关键词过滤 → 向量匹配 → 图遍历扩展
     */
    public SearchResult hybridSearch(String userQuery, String projectPath) {
        long startTime = System.currentTimeMillis();

        // 1. 意图识别
        QueryIntent intent = intentRecognitionService.parseIntent(userQuery);
        log.info("解析意图: {}", intent);

        // 2. 第一层：关键词过滤
        List<MethodNode> candidates = keywordFilter(intent, projectPath);
        log.info("关键词过滤后候选数: {}", candidates.size());

        // 3. 第二层：向量匹配
        List<MethodNode> vectorResults = vectorSearch(userQuery, projectPath, defaultTopK);
        log.info("向量匹配结果数: {}", vectorResults.size());

        // 4. 第三层：图遍历扩展
        List<MethodNode> graphResults = graphExpansion(vectorResults, projectPath, maxDepth);
        log.info("图遍历扩展结果数: {}", graphResults.size());

        // 5. RRF 结果融合
        List<MethodNode> finalResults = fuseResults(vectorResults, graphResults);
        log.info("融合后结果数: {}", finalResults.size());

        long costTime = System.currentTimeMillis() - startTime;
        log.info("混合检索完成，耗时: {}ms", costTime);

        return SearchResult.builder()
            .query(userQuery)
            .intent(intent)
            .results(finalResults)
            .totalCount(finalResults.size())
            .costTimeMs(costTime)
            .build();
    }

    /**
     * 第一层：关键词过滤
     */
    private List<MethodNode> keywordFilter(QueryIntent intent, String projectPath) {
        List<MethodNode> results = new ArrayList<>();

        // 按服务名过滤
        if (intent.getServiceName() != null) {
            results = methodNodeRepository.findByServiceName(intent.getServiceName());
        }

        // 按关键词过滤
        if (intent.getKeywords() != null && !intent.getKeywords().isEmpty()) {
            for (String keyword : intent.getKeywords()) {
                List<MethodNode> keywordResults = methodNodeRepository.searchByKeyword(keyword);
                results.addAll(keywordResults);
            }
        }

        // 去重
        return results.stream()
            .distinct()
            .filter(m -> projectPath == null || projectPath.equals(m.getProjectPath()))
            .collect(Collectors.toList());
    }

    /**
     * 第二层：向量匹配
     */
    private List<MethodNode> vectorSearch(String query, String projectPath, int topK) {
        // 生成查询向量
        float[] queryEmbedding = proxyVectorService.generateEmbedding(query);

        if (queryEmbedding == null) {
            log.warn("查询向量生成失败");
            return Collections.emptyList();
        }

        return methodNodeRepository.vectorSearch(queryEmbedding, projectPath, topK);
    }

    /**
     * 第三层：图遍历扩展
     */
    private List<MethodNode> graphExpansion(List<MethodNode> seeds, String projectPath, int depth) {
        Set<String> visitedIds = new HashSet<>();
        List<MethodNode> expanded = new ArrayList<>();

        for (MethodNode seed : seeds) {
            // 向下游扩展
            List<MethodNode> downstream = methodNodeRepository.expandDownstream(
                seed.getNodeId(), depth, projectPath
            );

            for (MethodNode node : downstream) {
                if (!visitedIds.contains(node.getNodeId())) {
                    visitedIds.add(node.getNodeId());
                    expanded.add(node);
                }
            }
        }

        return expanded;
    }

    /**
     * RRF 结果融合
     */
    private List<MethodNode> fuseResults(
        List<MethodNode> vectorResults,
        List<MethodNode> graphResults
    ) {
        Map<String, Double> scoreMap = new HashMap<>();
        Map<String, MethodNode> nodeMap = new HashMap<>();

        double k = 60.0; // RRF 平滑参数

        // 向量结果权重: 1 / (k + rank)
        for (int i = 0; i < vectorResults.size(); i++) {
            MethodNode node = vectorResults.get(i);
            String nodeId = node.getNodeId();
            scoreMap.merge(nodeId, 1.0 / (k + i + 1), Double::sum);
            nodeMap.putIfAbsent(nodeId, node);
        }

        // 图遍历结果权重: 0.8 / (k + rank)
        for (int i = 0; i < graphResults.size(); i++) {
            MethodNode node = graphResults.get(i);
            String nodeId = node.getNodeId();
            scoreMap.merge(nodeId, 0.8 / (k + i + 1), Double::sum);
            nodeMap.putIfAbsent(nodeId, node);
        }

        // 按分数排序返回
        return scoreMap.entrySet().stream()
            .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
            .limit(defaultTopK)
            .map(e -> nodeMap.get(e.getKey()))
            .collect(Collectors.toList());
    }
}
```

#### 步骤 6.2.2: 创建检索结果模型

**文件**: `src/main/java/com/huawei/hisi/neo4j/model/SearchResult.java`

```java
package com.huawei.hisi.neo4j.model;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchResult {
    private String query;
    private QueryIntent intent;
    private List<MethodNode> results;
    private Integer totalCount;
    private Long costTimeMs;
}
```

---

### 任务 6.3: 创建检索控制器

#### 步骤 6.3.1: 创建控制器

**文件**: `src/main/java/com/huawei/hisi/neo4j/controller/VectorSearchController.java`

```java
package com.huawei.hisi.neo4j.controller;

import com.huawei.hisi.neo4j.model.SearchResult;
import com.huawei.hisi.neo4j.service.HybridSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/vector-search")
@RequiredArgsConstructor
public class VectorSearchController {

    private final HybridSearchService hybridSearchService;

    /**
     * 自然语言检索
     * POST /api/vector-search
     * Body: { "query": "查询订单创建的核心方法", "projectPath": "/path/to/project" }
     */
    @PostMapping
    public SearchResult search(@RequestBody SearchRequest request) {
        return hybridSearchService.hybridSearch(
            request.getQuery(),
            request.getProjectPath()
        );
    }

    @lombok.Data
    public static class SearchRequest {
        private String query;
        private String projectPath;
    }
}
```

---

## 阶段 7: 测试与验证

### 任务 7.1: 单元测试

#### 步骤 7.1.1: 创建测试类

**文件**: `src/test/java/com/huawei/hisi/neo4j/service/HybridSearchServiceTest.java`

```java
package com.huawei.hisi.neo4j.service;

import com.huawei.hisi.neo4j.model.MethodNode;
import com.huawei.hisi.neo4j.model.QueryIntent;
import com.huawei.hisi.neo4j.model.SearchResult;
import com.huawei.hisi.neo4j.repository.MethodNodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HybridSearchServiceTest {

    @Mock
    private MethodNodeRepository methodNodeRepository;

    @Mock
    private IntentRecognitionService intentRecognitionService;

    @Mock
    private ProxyVectorService proxyVectorService;

    @InjectMocks
    private HybridSearchService hybridSearchService;

    private MethodNode createTestMethod(String nodeId, String className, String methodName) {
        return MethodNode.builder()
            .nodeId(nodeId)
            .className(className)
            .methodName(methodName)
            .projectPath("/test/project")
            .serviceName("test-service")
            .build();
    }

    @Test
    void testHybridSearch() {
        // Given
        String query = "查询订单创建的核心方法";
        String projectPath = "/test/project";

        QueryIntent intent = QueryIntent.builder()
            .entity("订单创建")
            .methodType("核心入口")
            .serviceName("order-service")
            .keywords(Arrays.asList("Order", "create"))
            .build();

        MethodNode method1 = createTestMethod("m1", "OrderService", "createOrder");
        MethodNode method2 = createTestMethod("m2", "OrderController", "create");

        // When
        when(intentRecognitionService.parseIntent(query)).thenReturn(intent);
        when(methodNodeRepository.findByServiceName("order-service"))
            .thenReturn(Arrays.asList(method1, method2));
        when(proxyVectorService.generateEmbedding(anyString()))
            .thenReturn(new float[384]);
        when(methodNodeRepository.vectorSearch(any(), anyString(), anyInt()))
            .thenReturn(Arrays.asList(method1));
        when(methodNodeRepository.expandDownstream(anyString(), anyInt(), anyString()))
            .thenReturn(Arrays.asList(method2));

        SearchResult result = hybridSearchService.hybridSearch(query, projectPath);

        // Then
        assertNotNull(result);
        assertEquals(query, result.getQuery());
        assertNotNull(result.getResults());
        assertTrue(result.getCostTimeMs() >= 0);
    }

    @Test
    void testRRFFusion() {
        MethodNode m1 = createTestMethod("m1", "ServiceA", "methodA");
        MethodNode m2 = createTestMethod("m2", "ServiceB", "methodB");
        MethodNode m3 = createTestMethod("m3", "ServiceC", "methodC");

        List<MethodNode> vectorResults = Arrays.asList(m1, m2);
        List<MethodNode> graphResults = Arrays.asList(m2, m3);

        List<MethodNode> fused = hybridSearchService.fuseResults(vectorResults, graphResults);

        assertNotNull(fused);
        // m2 应该排在最前面（两个列表都有）
        assertTrue(fused.stream().anyMatch(m -> m.getNodeId().equals("m2")));
    }
}
```

---

## 验收清单

### 阶段 1 验收
- [ ] Neo4j Docker 容器正常运行
- [ ] 可通过浏览器访问 Neo4j Browser (http://localhost:7474)
- [ ] APOC、GDS 插件安装成功

### 阶段 2 验收
- [ ] Spring Boot 可连接 Neo4j
- [ ] Repository 层 CRUD 操作正常
- [ ] 约束和索引创建成功

### 阶段 3 验收
- [ ] PostgreSQL 数据完整迁移到 Neo4j
- [ ] 方法节点数量一致
- [ ] 调用关系正确创建

### 阶段 4 验收
- [ ] 注释提取功能正常
- [ ] 代理向量生成成功
- [ ] 向量维度正确 (384维)

### 阶段 5 验收
- [ ] GraphSAGE 模型训练成功
- [ ] 图嵌入生成成功
- [ ] 向量融合正常

### 阶段 6 验收
- [ ] 意图识别准确率 ≥ 85%
- [ ] 向量检索返回结果
- [ ] 图遍历扩展正常
- [ ] RRF 融合结果正确

### 阶段 7 验收
- [ ] 单元测试全部通过
- [ ] 混合检索响应时间 < 200ms
- [ ] Recall@3 ≥ 85%
- [ ] Python 桥接服务已下线
