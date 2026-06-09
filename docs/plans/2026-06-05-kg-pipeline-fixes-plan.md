# KG Pipeline Bug Fixes & Enhancement Plan (v2)

**Goal:** Fix Python/Java KG pipeline critical bugs causing project-internal calls to be silently dropped. Enhance Python entry point detection.

**Principle:** No unresolved placeholder nodes. No unresolved edge persistence. Fix resolution logic so project-internal calls are properly resolved. Truly external calls (stdlib, third-party libs, jar/native/Lombok) are silently skipped.

**Tech Stack:** Java 17, ANTLR4 (Python), JavaParser, Spring Data Neo4j, Cypher

---

## Phase 1: Python P0 Bug Fixes (Critical — ~0.5 day)

> **Impact:** Restores 70-80% of Python call chains that are currently dropped due to resolution logic bugs, not due to being truly external.

### Task 1.1: Fix `normalizeExpression()` for nested parentheses

**File:** `hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/python/call/PythonCallGraphResolver.java` (lines 147-150)

**Problem:** Regex `\\([^)]*\\)\\.` fails on nested parentheses like `Foo(Bar()).method()`. This makes resolvable calls look unresolvable.

**Current:**
```java
private String normalizeExpression(String expression) {
    return expression.replaceAll("\\([^)]*\\)\\.", ".");
}
```

**Fix:** Replace with iterative paren-depth tracking:
```java
private String normalizeExpression(String expression) {
    StringBuilder result = new StringBuilder();
    int depth = 0;
    for (int i = 0; i < expression.length(); i++) {
        char c = expression.charAt(i);
        if (c == '(') {
            depth++;
        } else if (c == ')' && depth > 0) {
            depth--;
            if (i + 1 < expression.length() && expression.charAt(i + 1) == '.') {
                result.append('.');
                i++;
            }
        } else if (depth == 0) {
            result.append(c);
        }
    }
    return result.toString();
}
```

**Verification:** `mvn compile -q`

---

### Task 1.2: Detect `if __name__ == "__main__":` as MAIN entry point + fix module-level caller

**Files:**
- Modify: `hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/python/call/PythonCallGraphResolver.java` (lines 350-376)
- Modify: `hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/python/PythonKnowledgeGraphBuilder.java`
- Possibly modify: `hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/python/PythonAstVisitor.java` (if main-block detection needs AST support)

**Problem:** Module-level calls (outside any function) have `enclosingFunction == null`, causing `computeCallerNodeId()` to return null. The entire call is dropped even though both caller and callee are project-internal. Specifically, `if __name__ == "__main__":` is the Python equivalent of Java's `public static void main` — a legitimate entry point.

**Design:**
1. **新增入口类型 `MAIN`** — 与 CONTROLLER、SCHEDULED 等并列
2. **检测 `if __name__ == "__main__":` 块** — 在 AST visitor 或 resolver 中识别
3. **创建 EntryPointNode(type=MAIN)** — 代表模块的 main 入口
4. **创建伪方法节点 `module.__main__`** — 作为 main 块内调用的 caller
5. **`computeCallerNodeId()` 为 main 块内的调用返回 `module.__main__` 的 nodeId**

**Fix — `computeCallerNodeId()`:**
```java
private String computeCallerNodeId(PyModule module, PyCall call) {
    String enclosing = call.getEnclosingFunction();
    if (enclosing == null || enclosing.isEmpty()) {
        // Module-level call in __main__ block → use MAIN entry caller
        if (call.isInMainBlock()) {  // 需要在 PyCall 中标记是否在 if __name__ == "__main__": 内
            return PythonKnowledgeGraphBuilder.toNodeId(module.getModulePath())
                + ".__main__";
        }
        return null; // 非 main 块的模块级调用仍跳过
    }
    // ... existing logic
}
```

**Fix — `PythonKnowledgeGraphBuilder` 中创建 MAIN 入口点和伪方法节点:**
- 扫描所有模块，为包含 `if __name__ == "__main__":` 的模块创建：
  - MethodNode(`methodName=__main__`, `className=<module_name>`)
  - EntryPointNode(`entryType=MAIN`, `methodNodeId=<module.__main__ nodeId>`)
- 将这些伪方法节点加入 `validNodeIds`，使 edge filter 不会丢弃

**Verification:** `mvn compile -q`
```

同时需要在 `PythonKnowledgeGraphBuilder` 中为每个模块创建一个 `__module_init__` 伪方法节点，使 validNodeIds 包含这些 ID。

**Verification:** `mvn compile -q`

---

### Task 1.3: Fix `resolveCall()` — reduce premature null returns

**File:** `hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/python/call/PythonCallGraphResolver.java` (lines 189-207)

**Problem:** `resolveCall()` has two `return null` paths. After fixing Task 1.1 and 1.2, fewer calls will reach these paths. For the remaining truly unresolvable ones (stdlib, third-party), skip with better logging.

**Fix:** Replace `return null` with `return unresolvedEdge(...)` BUT these unresolved edges will be filtered out by the edge filter in `PythonKnowledgeGraphBuilder` (Task 1.4). The key benefit: the log now distinguishes "we tried but couldn't resolve" from "we never tried".

Line ~192:
```java
// Before:
return null;
// After:
return unresolvedEdge(callerNodeId, head, call.getLineNumber());
```

Line ~207:
```java
// Before:
return null;
// After:
return unresolvedEdge(callerNodeId, expression, call.getLineNumber());
```

Note: `unresolvedEdge()` already exists at lines 583-586 but is never called.

**Verification:** `mvn compile -q`

---

### Task 1.4: Fix edge filter in `PythonKnowledgeGraphBuilder`

**File:** `hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/python/PythonKnowledgeGraphBuilder.java` (lines 169-182)

**Problem:** Current filter requires BOTH callerId and calleeId in `validNodeIds`. After Task 1.2, module-level callers (`__module_init__`) need to be in `validNodeIds`. After Task 1.3, unresolved edges (callee starts with `"unresolved:"`) need to be filtered out cleanly.

**Fix:**
1. Ensure `__module_init__` pseudo nodes are added to `validNodeIds`
2. Keep existing filter logic — unresolved edges naturally filtered because their callee won't be in validNodeIds
3. Add logging to show resolved vs dropped counts

```java
long resolved = result.callRelations.stream()
    .filter(rel -> {
        String cid = (String) rel.get("callerId");
        String eid = (String) rel.get("calleeId");
        return cid != null && validNodeIds.contains(cid)
            && eid != null && validNodeIds.contains(eid);
    }).count();
log.info("[Python KG] Call edges: total={}, persistable={}, dropped={}",
    result.callRelations.size(), resolved,
    result.callRelations.size() - resolved);
```

**Verification:** `mvn compile -q`

---

## Phase 2: Python Entry Point Enhancement (~1.5 days)

### Task 2.1: Add fallback framework detection in `PythonFrameworkDetector`

**File:** `hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/python/PythonFrameworkDetector.java`

**Problem:** When no manifest file exists, framework detection returns empty and no entry point scanners run.

**Fix:** Add import-based fallback after manifest file loop:
```java
if (found.isEmpty()) {
    // Scan Python files for framework imports
    try (Stream<Path> walk = Files.walk(Paths.get(projectPath))) {
        String importLines = walk
            .filter(p -> p.toString().endsWith(".py"))
            .limit(100)
            .map(p -> {
                try { return Files.readString(p, StandardCharsets.UTF_8); }
                catch (IOException e) { return ""; }
            })
            .collect(Collectors.joining("\n"));

        if (importLines.contains("from fastapi ") || importLines.contains("import fastapi"))
            found.add(Framework.FASTAPI);
        if (importLines.contains("from django ") || importLines.contains("import django"))
            found.add(Framework.DJANGO);
        if (importLines.contains("from flask ") || importLines.contains("import flask"))
            found.add(Framework.FLASK);
        if (importLines.contains("from celery ") || importLines.contains("import celery"))
            found.add(Framework.CELERY);
    } catch (IOException e) {
        log.warn("[Python KG] Fallback detection failed: {}", e.getMessage());
    }
}
```

**Verification:** Test against a Python project without manifest files.

---

### Task 2.2: Fix FastAPI multi-router prefix handling

**File:** `hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/python/scanner/FastApiRouteScanner.java`

**Problem:** When multiple `APIRouter()` exist, `extractSingleRouterPrefix` returns null (drops all prefixes).

**Fix:** Track variable-to-prefix mapping instead of single prefix. May require `PythonAstVisitor` changes to capture assignment targets.

**Verification:** Test with FastAPI project using multiple routers with different prefixes.

---

### Task 2.3: Relax Django URL scanning scope

**File:** `hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/python/scanner/DjangoUrlScanner.java`

**Problem:** Only scans files ending with `urls.py`. Django allows URL patterns in other files.

**Fix:** Soft gate — scan any file containing `path()`/`re_path()`/`url()` calls, not just `urls.py`.

**Verification:** Test with Django project that has URLs in non-standard files.

---

## Phase 3: Java KG Enhancement (~1 day)

> **注意：** Java 管线相对成熟，改动需格外谨慎，逐个 Task 确认后再动手。

### Task 3.1: Add `EnumDeclaration` support

**File:** `hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/service/KnowledgeGraphBuilder.java`

**Problem:** Only `ClassOrInterfaceDeclaration` is traversed, skipping enum types with methods.

**Verification:** `mvn compile -q`, test with Java project using enums with methods.

---

### Task 3.2: Add constructor call scanning (`ObjectCreationExpr`)

**File:** `hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/service/KnowledgeGraphBuilder.java`

**Problem:** Only `MethodCallExpr` is scanned, missing `new Foo()` constructor calls.

**Verification:** `mvn compile -q`

---

### Task 3.3: Implement proper cyclomatic complexity calculation

**File:** `hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/service/KnowledgeGraphBuilder.java`

**Problem:** Currently hardcoded to `return 1`.

**Fix:** McCabe complexity counting if/for/while/do/catch/switch/ternary/&&/||.

**Verification:** `mvn compile -q`

---

### ~~Task 3.4: Fix method overload collision in mapping key~~ (Deferred)

> Key format `className.methodName` 导致重载碰撞，但改动涉及所有 `methodSignatureToNodeId` 查找点，风险较高。单独评估后再决定是否实施。

---

## 文件变更清单

| Phase | 文件 | 改动 |
|-------|------|------|
| P1 | `PythonCallGraphResolver.java` | Fix `normalizeExpression()` nested parens |
| P1 | `PythonCallGraphResolver.java` | Fix `computeCallerNodeId()` + MAIN entry caller |
| P1 | `PythonCallGraphResolver.java` | Fix `resolveCall()` null → unresolved edge |
| P1 | `PythonKnowledgeGraphBuilder.java` | Edge filter + `__main__` pseudo nodes + MAIN EntryPointNode |
| P2 | `PythonFrameworkDetector.java` | Import-based fallback detection |
| P2 | `FastApiRouteScanner.java` | Multi-router prefix mapping |
| P2 | `DjangoUrlScanner.java` | Relax url file restriction |
| P3 | `KnowledgeGraphBuilder.java` | Enum + constructor + complexity |

## 工期估算

| Phase | 工期 | 前置依赖 |
|-------|------|---------|
| P1: Python P0 | 0.5 天 | 无 |
| P2: 入口点增强 | 1.5 天 | P1 完成 |
| P3: Java 增强 | 1 天 | 逐 Task 确认 |
| **Total** | **~3 天** | |
