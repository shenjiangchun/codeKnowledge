# Spring DI Call Chain Completeness Fix — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Fix all gaps in the knowledge graph's Spring DI call chain resolution — ensure interface→impl relationships are persisted to Neo4j, constructor injection is recognized, @Qualifier filtering works, and springAnnotationAware flag is functional.

**Architecture:** We fix 5 discrete issues in the existing call chain pipeline: (1) implement the Neo4j IMPLEMENTS relationship persistence stub, (2) add constructor injection awareness to `inferTypeFromScope` + `resolveFieldCallEnhanced`, (3) add @Qualifier filtering, (4) implement @Async/@Transactional proxy awareness behind the `springAnnotationAware` flag, (5) clean up dead code (`InterfaceImplementationScanner`).

**Tech Stack:** Java 17, Spring Boot 3.2.0, Spring Data Neo4j 7.2.0, Neo4j 5.11+, JavaParser, JUnit 5 + Mockito

---

## Task 1: Implement IMPLEMENTS Relationship Persistence in Neo4j

The most critical fix. `saveInterfaceImplementation` is a TODO stub that does nothing.

**Files:**
- Modify: `src/main/java/com/huawei/hisi/neo4j/repository/Neo4jMethodNodeRepository.java` (add Cypher query)
- Modify: `src/main/java/com/huawei/hisi/knowledgegraph/service/storage/Neo4jStorageService.java:120-138` (implement stub)
- Test: `src/test/java/com/huawei/hisi/knowledgegraph/service/storage/Neo4jStorageServiceDefaultsTest.java` (add test)

### Step 1: Add MERGE Cypher query to Neo4jMethodNodeRepository

Add a batch MERGE query for IMPLEMENTS relationships, following the existing `createCallRelations` pattern (line 227-239):

```java
@Query("""
    UNWIND $relations AS rel
    MATCH (impl:Method)
    WHERE impl.className = rel.implementationName
      AND impl.projectPath IN $projectPaths
    MATCH (iface:Method)
    WHERE iface.className = rel.interfaceName
      AND iface.projectPath IN $projectPaths
    WITH impl, iface, rel
    MERGE (impl)-[r:IMPLEMENTS]->(iface)
    SET r.projectPath = rel.projectPath
    """)
void createImplementsRelations(
    @Param("relations") List<Map<String, Object>> relations,
    @Param("projectPaths") List<String> projectPaths);

@Query("""
    MATCH (:Method)-[r:IMPLEMENTS]->(:Method)
    WHERE r.projectPath = $projectPath
    RETURN count(r)
    """)
int countImplementsRelations(@Param("projectPath") String projectPath);

@Query("""
    MATCH (impl:Method)-[:IMPLEMENTS]->(iface:Method {className: $interfaceName})
    WHERE impl.projectPath IN $projectPaths
    RETURN DISTINCT impl.className AS implementationName
    """)
List<String> findImplementationsByInterface(
    @Param("interfaceName") String interfaceName,
    @Param("projectPaths") List<String> projectPaths);
```

### Step 2: Implement Neo4jStorageService.saveInterfaceImplementation

Replace the TODO stub at lines 120-138:

```java
@Override
@Transactional(transactionManager = "neo4jTransactionManager")
public void saveInterfaceImplementation(InterfaceImplementation impl) {
    List<Map<String, Object>> relations = List.of(Map.of(
        "interfaceName", impl.getInterfaceName(),
        "implementationName", impl.getImplementationName(),
        "projectPath", impl.getProjectPath()
    ));
    methodNodeRepository.createImplementsRelations(relations, List.of(impl.getProjectPath()));
    log.debug("Interface implementation saved: {} -> {}", impl.getInterfaceName(), impl.getImplementationName());
}

@Override
@Transactional(transactionManager = "neo4jTransactionManager")
public void saveInterfaceImplementations(List<InterfaceImplementation> impls) {
    if (impls.isEmpty()) return;
    List<Map<String, Object>> relations = impls.stream()
        .map(impl -> Map.<String, Object>of(
            "interfaceName", impl.getInterfaceName(),
            "implementationName", impl.getImplementationName(),
            "projectPath", impl.getProjectPath()
        ))
        .toList();
    String projectPath = impls.get(0).getProjectPath();
    List<String> projectPaths = impls.stream()
        .map(InterfaceImplementation::getProjectPath)
        .distinct()
        .toList();
    methodNodeRepository.createImplementsRelations(relations, projectPaths);
    log.info("[Neo4j] 保存接口实现关系: {} 个", impls.size());
}

@Override
@Transactional(transactionManager = "neo4jTransactionManager", readOnly = true)
public int countInterfaceImplementations(String projectPath) {
    return methodNodeRepository.countImplementsRelations(projectPath);
}
```

### Step 3: Wire `findImplementationsByInterface` into KnowledgeGraphController

In the existing `kg_implementations` endpoint handler, use the new repository method instead of returning empty.

### Step 4: Test

Run: `mvn test -Dtest="Neo4jStorageServiceDefaultsTest" -pl hisi-dev-tool`

### Step 5: Commit

```
feat: implement IMPLEMENTS relationship persistence in Neo4j

Previously saveInterfaceImplementation was a TODO stub. Now interface→impl
relationships are written as IMPLEMENTS edges in Neo4j via batch MERGE,
enabling kg_implementations queries to return real data.
```

---

## Task 2: Add Constructor Injection Awareness

Spring-recommended constructor injection is not recognized. `inferTypeFromScope` does not check constructor parameters, and `resolveFieldCallEnhanced` only looks at annotation-based field injection.

**Files:**
- Modify: `src/main/java/com/huawei/hisi/service/CodeAnalysisCoreService.java:967-1029` (resolveFieldCallEnhanced)
- Modify: `src/main/java/com/huawei/hisi/service/CodeAnalysisCoreService.java:1142-1171` (inferTypeFromScope)
- Test: `src/test/java/com/huawei/hisi/service/CodeAnalysisCoreServiceConstructorInjectionTest.java` (new)

### Step 1: Enhance `inferTypeFromScope` to check constructor parameters

After the method parameter check (line ~1153), add constructor parameter check:

```java
// 检查构造函数参数（支持构造器注入）
for (ConstructorDeclaration ctor : clazz.getConstructors()) {
    for (com.github.javaparser.ast.body.Parameter param : ctor.getParameters()) {
        if (param.getName().asString().equals(scopeName)) {
            return param.getType().asString();
        }
    }
}
```

### Step 2: Enhance `resolveFieldCallEnhanced` to treat final-field-with-constructor-param as injected

In the `isInjectedField` detection (line ~984), add logic: if the field is `final` and the class has a single constructor (or an `@AllArgsConstructor`/`@RequiredArgsConstructor` Lombok annotation), treat it as injected:

```java
// 检查是否有 Spring 注入注解
boolean isInjectedField = field.getAnnotations().stream()
    .anyMatch(a -> {
        String annoName = a.getNameAsString();
        return "Autowired".equals(annoName) ||
               "Resource".equals(annoName) ||
               "Inject".equals(annoName) ||
               "Lazy".equals(annoName);
    });

// 构造器注入检测：final 字段 + 类有构造函数接收该类型
if (!isInjectedField && field.isFinal()) {
    isInjectedField = isConstructorInjectedField(clazz, variable);
}
```

New helper method:

```java
private boolean isConstructorInjectedField(ClassOrInterfaceDeclaration clazz, VariableDeclarator variable) {
    String fieldName = variable.getName().asString();
    String fieldType = variable.getType().asString();

    // 检查 Lombok 注解
    boolean hasLombokConstructor = clazz.getAnnotations().stream()
        .anyMatch(a -> {
            String name = a.getNameAsString();
            return "AllArgsConstructor".equals(name) ||
                   "RequiredArgsConstructor".equals(name);
        });
    if (hasLombokConstructor) return true;

    // 检查显式构造函数是否有匹配参数
    for (ConstructorDeclaration ctor : clazz.getConstructors()) {
        boolean hasMatchingParam = ctor.getParameters().stream()
            .anyMatch(p -> p.getType().asString().equals(fieldType));
        if (hasMatchingParam) return true;
    }

    return false;
}
```

### Step 3: Write test

Create `CodeAnalysisCoreServiceConstructorInjectionTest.java` with test cases for:
- `final` field + explicit constructor → treated as injected, resolves to impl
- `final` field + `@RequiredArgsConstructor` → treated as injected
- non-final field without annotation → NOT treated as injected

### Step 4: Commit

```
feat: add constructor injection awareness to call chain resolution

Final fields with constructor params or Lombok @RequiredArgsConstructor
are now treated as DI-injected, enabling interface→impl resolution
for the Spring-recommended constructor injection pattern.
```

---

## Task 3: Add @Qualifier Filtering

When multiple implementations exist, all are currently added as call targets. @Qualifier should narrow to the specific one.

**Files:**
- Modify: `src/main/java/com/huawei/hisi/service/CodeAnalysisCoreService.java:967-1029` (resolveFieldCallEnhanced)
- Modify: `src/main/java/com/huawei/hisi/cache/GlobalAnalysisCache.java` (add beanNameMap)
- Modify: `src/main/java/com/huawei/hisi/service/CodeAnalysisCoreService.java` (buildImplementationMapEnhanced — capture @Component/@Service names)
- Test: new test

### Step 1: Add `beanNameMap` to GlobalAnalysisCache

```java
// Key: bean name (from @Component("name") or @Qualifier("name")), Value: FQN class name
private final Map<String, String> beanNameMap = new ConcurrentHashMap<>();
```

### Step 2: Populate `beanNameMap` during implementation map building

In `buildImplementationMapEnhanced`, when scanning `@Component`, `@Service`, `@Repository` annotations, extract the value as the bean name and store in `beanNameMap`. Also store the lowercase-first-letter of the simple class name as default bean name.

### Step 3: Filter by @Qualifier in `resolveFieldCallEnhanced`

After collecting implementations (line ~1003-1010), check if the field has `@Qualifier("name")`. If so, filter `typeNamesToTry` to only the matching bean:

```java
// @Qualifier 过滤
if (isInjectedField && implementations != null && implementations.size() > 1) {
    Optional<String> qualifier = field.getAnnotations().stream()
        .filter(a -> "Qualifier".equals(a.getNameAsString()))
        .filter(a -> a.isSingleMemberAnnotationExpr())
        .map(a -> a.asSingleMemberAnnotationExpr().getMemberValue().asStringLiteralExpr().asString())
        .findFirst();

    if (qualifier.isPresent()) {
        String qualifiedBean = globalCache.getBeanNameMap().get(qualifier.get());
        if (qualifiedBean != null) {
            typeNamesToTry.clear();
            typeNamesToTry.add(qualifiedBean);
        }
    }
}
```

### Step 4: Test and commit

```
feat: add @Qualifier filtering to reduce false-positive call edges
```

---

## Task 4: Implement springAnnotationAware Flag (Proxy-Aware Call Resolution)

The `springAnnotationAware` flag in `AnalysisFeatureConfig` is defined but never read. Implement detection for `@Async` and `@Transactional` proxy calls.

**Files:**
- Modify: `src/main/java/com/huawei/hisi/service/CodeAnalysisCoreService.java` (new method + integrate into call scan)
- Modify: `src/main/java/com/huawei/hisi/knowledgegraph/service/KnowledgeGraphBuilder.java` (tag CALLS edges)

### Step 1: Create proxy annotation detection

When `springAnnotationAware=true`, for each resolved callee method, check if it has `@Async` or `@Transactional`. If so, set the `callType` on the CALLS edge to `ASYNC_PROXY` or `TRANSACTIONAL_PROXY` respectively, instead of the default `DIRECT`.

```java
private String detectProxyCallType(MethodDeclaration targetMethod) {
    if (!featureConfig.isSpringAnnotationAware()) return "DIRECT";

    for (AnnotationExpr anno : targetMethod.getAnnotations()) {
        String name = anno.getNameAsString();
        if ("Async".equals(name)) return "ASYNC_PROXY";
        if ("Transactional".equals(name)) return "TRANSACTIONAL_PROXY";
    }
    return "DIRECT";
}
```

### Step 2: Wire into KnowledgeGraphBuilder.scanCallRelationsWithCoreService

Where call relations are created, use the proxy-aware call type.

### Step 3: Enable in application.yml

Set `spring-annotation-aware: true` (from current `false`).

### Step 4: Test and commit

```
feat: implement springAnnotationAware flag for @Async/@Transactional proxy detection
```

---

## Task 5: Clean Up Dead Code

**Files:**
- Delete: `src/main/java/com/huawei/hisi/knowledgegraph/scanner/InterfaceImplementationScanner.java` (unused @Component)
- Keep: `src/test/java/.../InterfaceImplementationScannerTest.java` → repurpose or delete

### Step 1: Verify no imports reference InterfaceImplementationScanner

```bash
grep -r "InterfaceImplementationScanner" src/main/java/
```

Should return only the file itself.

### Step 2: Delete the file and its test

### Step 3: Commit

```
chore: remove unused InterfaceImplementationScanner (dead code)
```

---

## Task 6: Wire kg_implementations MCP Tool to Real Data

Now that IMPLEMENTS relationships exist in Neo4j, ensure the MCP `kg_implementations` tool returns real data.

**Files:**
- Modify: `src/main/java/com/huawei/hisi/knowledgegraph/controller/KnowledgeGraphController.java` (the /implementations endpoint)
- Test: verify via MCP tool call

### Step 1: Check current implementations endpoint

Find the current `/implementations` endpoint handler. It likely queries Neo4j but returns nothing because no IMPLEMENTS edges existed. After Task 1, it should work. If it doesn't use the repository method, wire it.

### Step 2: Test via curl

```bash
curl "http://localhost:8080/api/knowledge-graph/implementations?interfaceName=com.example.MyInterface&projectPath=..."
```

### Step 3: Commit if changes needed

---

## Verification

After all tasks:

1. `mvn clean test -pl hisi-dev-tool` — all tests green
2. Build and restart backend: `.\build-all.bat backend`
3. Trigger KG rebuild for a test project
4. Verify via MCP tools:
   - `kg_implementations` returns real implementation classes
   - `kg_callees_tree` for a method with constructor-injected interface resolves to impl
   - `kg_affecting` traces through DI boundaries correctly
5. Check Neo4j browser: `MATCH ()-[r:IMPLEMENTS]->() RETURN count(r)` should return > 0
