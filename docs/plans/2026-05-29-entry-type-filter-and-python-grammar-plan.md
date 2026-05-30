# Entry Type Dynamic Filter + Python 3.12 Grammar Replacement — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Two independent improvements — (A) dynamic entry-type filter for GraphExplorerTab with empty-state guidance for non-web projects, (B) replace Python ANTLR grammar with grammars-v4 python3_12 to support walrus `:=`, `except*`, `type` statements, type params, and f-string PEP 701.

**Architecture:** Feature A adds one backend endpoint + frontend dynamic rendering. Feature B replaces 4 grammar/base-class files and rewrites PythonAstVisitor to map to new rule names, keeping PyModule/PyClass/PyFunction/PyImport/PyCall models untouched.

**Tech Stack:** Spring Boot 3.2 + Java 17 + Neo4j 5.11+ + ANTLR4 4.13.1 + Vue 3.5 + TypeScript + Element Plus

---

## Feature A: Dynamic Entry Type Filter

### Task 1: Backend — Add `findDistinctEntryTypesByProjectPaths` to repository

**Files:**
- Modify: `hisi-dev-tool/src/main/java/com/huawei/hisi/neo4j/repository/Neo4jEntryPointNodeRepository.java`

**Step 1: Add the query method**

Add this method after `countByProjectPathsAndEntryType` (after line 221):

```java
/**
 * 获取多个项目下所有不同的入口类型
 */
@Query("""
    MATCH (entry:EntryPoint)
    WHERE entry.projectPath IN $projectPaths
    RETURN DISTINCT entry.entryType
    ORDER BY entry.entryType
    """)
List<String> findDistinctEntryTypesByProjectPaths(@Param("projectPaths") List<String> projectPaths);
```

**Step 2: Verify compilation**

Run: `cd hisi-dev-tool && mvn compile -q -pl .`
Expected: BUILD SUCCESS (no errors)

**Step 3: Commit**

```bash
git add hisi-dev-tool/src/main/java/com/huawei/hisi/neo4j/repository/Neo4jEntryPointNodeRepository.java
git commit -m "feat(kg): add findDistinctEntryTypesByProjectPaths to repository"
```

---

### Task 2: Backend — Add `/entry-types` endpoint to controller

**Files:**
- Modify: `hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/controller/KnowledgeGraphController.java`

**Step 1: Add the endpoint**

Add this method just BEFORE the existing `@GetMapping("/entry-points")` (before line 715):

```java
/**
 * 获取项目下所有不同的入口类型列表
 */
@GetMapping("/entry-types")
public ApiResponse<List<String>> getDistinctEntryTypes(
        @RequestParam(required = false) String projectPath,
        @RequestParam(required = false) List<String> projectPaths) {

    List<String> paths = ProjectPathResolver.resolve(projectPath, projectPaths);
    if (paths.isEmpty()) {
        return ApiResponse.error(400, "projectPath or projectPaths required");
    }

    List<String> types = neo4jEntryPointNodeRepository.findDistinctEntryTypesByProjectPaths(paths);
    return ApiResponse.success(types);
}
```

**Step 2: Verify compilation**

Run: `cd hisi-dev-tool && mvn compile -q -pl .`
Expected: BUILD SUCCESS

**Step 3: Commit**

```bash
git add hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/controller/KnowledgeGraphController.java
git commit -m "feat(kg): add /entry-types endpoint for dynamic entry type filter"
```

---

### Task 3: Frontend — Add `getEntryTypes` API function

**Files:**
- Modify: `hisi-dev-tool-frontend/src/api/knowledgeGraph.ts`

**Step 1: Add the API function**

Add this method right BEFORE `getEntryPoints` (before the `getEntryPoints` function around line 470):

```typescript
/**
 * 获取项目下所有不同的入口类型
 */
getEntryTypes(projectPath: string, projectPaths?: string[]) {
  return request.get<string[]>('/knowledge-graph/entry-types', {
    params: { projectPath, projectPaths }
  })
},
```

**Step 2: Verify types**

Run: `cd hisi-dev-tool-frontend && npx vue-tsc --noEmit`
Expected: No errors

**Step 3: Commit**

```bash
git add hisi-dev-tool-frontend/src/api/knowledgeGraph.ts
git commit -m "feat(kg): add getEntryTypes API function"
```

---

### Task 4: Frontend — Dynamic entry type rendering in GraphExplorerTab

**Files:**
- Modify: `hisi-dev-tool-frontend/src/views/knowledge-graph/components/GraphExplorerTab.vue`

**IMPORTANT:** This file has been modified by the user with pagination support. Preserve ALL existing code — only change the entry-type filter section.

**Step 1: Add state and label map**

In the `<script setup>` section, after `const entryPagination = ref(...)` (line 40), add:

```typescript
const availableEntryTypes = ref<string[]>([])
const entryTypesLoading = ref(false)

const ENTRY_TYPE_LABELS: Record<string, string> = {
  HTTP: 'HTTP接口',
  SCHEDULED: '定时任务',
  MQ_CONSUMER: 'MQ消费',
  FEIGN_CLIENT: 'Feign',
  FASTAPI_ROUTE: 'FastAPI',
  FLASK_ROUTE: 'Flask',
  DJANGO_VIEW: 'Django',
  CELERY_TASK: 'Celery',
  GRPC: 'gRPC',
  RMI: 'RMI',
}

function entryTypeLabel(type: string): string {
  return ENTRY_TYPE_LABELS[type] || type
}
```

**Step 2: Add `loadEntryTypes` function**

In the Entry Type Mode section (after `handleEntryTypeChange`), add:

```typescript
async function loadEntryTypes() {
  entryTypesLoading.value = true
  try {
    availableEntryTypes.value = (await knowledgeGraphApi.getEntryTypes(props.projectPath, props.projectPaths)) ?? []
  } catch {
    availableEntryTypes.value = []
  } finally {
    entryTypesLoading.value = false
  }
}
```

**Step 3: Update `handleModeChange` to load entry types**

Replace the existing `handleModeChange` function:

```typescript
function handleModeChange() {
  if (browseMode.value === 'entryType') {
    if (availableEntryTypes.value.length === 0) {
      loadEntryTypes()
    }
    if (entryPoints.value.length === 0) {
      loadEntryPoints()
    }
  }
  if (browseMode.value === 'class' && classList.value.length === 0) {
    loadClassList()
  }
}
```

**Step 4: Update project watch to reset entry types**

In the `watch(() => props.projectPath, ...)` callback, add `availableEntryTypes.value = []` to the reset block (after `entryPoints.value = []`).

**Step 5: Replace hardcoded radio buttons in template**

Replace the hardcoded `el-radio-group` block (lines 347-353):

```html
<el-radio-group v-model="entryTypeFilter" @change="handleEntryTypeChange" size="small" v-loading="entryTypesLoading">
  <el-radio-button value="ALL">全部</el-radio-button>
  <el-radio-button
    v-for="t in availableEntryTypes"
    :key="t"
    :value="t"
  >{{ entryTypeLabel(t) }}</el-radio-button>
</el-radio-group>
```

**Step 6: Add empty state guidance**

After the `el-pagination` component (after line 385), add:

```html
<el-empty
  v-if="!entryLoading && !entryTypesLoading && availableEntryTypes.length === 0"
  description="该项目未检测到入口点"
>
  <el-button type="primary" @click="browseMode = 'class'">切换到按类浏览</el-button>
</el-empty>
```

**Step 7: Update entry type tag color in table**

Replace the hardcoded tag type logic (line 367) with a more generic approach:

```html
<el-tag size="small" :type="row.entryType === 'HTTP' ? 'primary' : row.entryType === 'SCHEDULED' ? 'warning' : 'info'">
  {{ entryTypeLabel(row.entryType) }}
</el-tag>
```

**Step 8: Verify types**

Run: `cd hisi-dev-tool-frontend && npx vue-tsc --noEmit`
Expected: No errors

**Step 9: Commit**

```bash
git add hisi-dev-tool-frontend/src/views/knowledge-graph/components/GraphExplorerTab.vue
git commit -m "feat(kg): dynamic entry type filter with empty state guidance"
```

---

## Feature B: Python 3.12 Grammar Replacement

### Task 5: Download and place grammar files from grammars-v4

**Files:**
- Delete: `hisi-dev-tool/src/main/antlr4/com/huawei/hisi/knowledgegraph/python/parser/Python3Parser.g4`
- Delete: `hisi-dev-tool/src/main/antlr4/com/huawei/hisi/knowledgegraph/python/parser/Python3Lexer.g4`
- Create: `hisi-dev-tool/src/main/antlr4/com/huawei/hisi/knowledgegraph/python/parser/PythonParser.g4`
- Create: `hisi-dev-tool/src/main/antlr4/com/huawei/hisi/knowledgegraph/python/parser/PythonLexer.g4`

**Step 1: Download grammar files from grammars-v4**

Source: `https://github.com/antlr/grammars-v4/tree/master/python/python3_12`

Download `PythonParser.g4` and `PythonLexer.g4` from that directory. Place them in:
`hisi-dev-tool/src/main/antlr4/com/huawei/hisi/knowledgegraph/python/parser/`

**Step 2: Remove old grammar files**

```bash
rm hisi-dev-tool/src/main/antlr4/com/huawei/hisi/knowledgegraph/python/parser/Python3Parser.g4
rm hisi-dev-tool/src/main/antlr4/com/huawei/hisi/knowledgegraph/python/parser/Python3Lexer.g4
```

**Step 3: Adjust grammar package**

In `PythonParser.g4`, verify the options block has:
```
options {
    tokenVocab = PythonLexer;
    superClass = PythonParserBase;
}
```

No `@header` with package is needed — the antlr4-maven-plugin sets the package based on directory structure.

**Step 4: Do NOT commit yet — continue to Task 6**

---

### Task 6: Replace base classes

**Files:**
- Delete: `hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/python/parser/Python3ParserBase.java`
- Delete: `hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/python/parser/Python3LexerBase.java`
- Create: `hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/python/parser/PythonParserBase.java`
- Create: `hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/python/parser/PythonLexerBase.java`

**Step 1: Download base class files**

From the same grammars-v4 `python/python3_12/Java/` directory, download:
- `PythonParserBase.java`
- `PythonLexerBase.java`

Place them in `hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/python/parser/`

**Step 2: Set the package declaration**

In both files, ensure the first line is:
```java
package com.huawei.hisi.knowledgegraph.python.parser;
```

**Step 3: Remove old base classes**

```bash
rm hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/python/parser/Python3ParserBase.java
rm hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/python/parser/Python3LexerBase.java
```

**Step 4: Verify ANTLR generation compiles**

Run: `cd hisi-dev-tool && mvn generate-sources -q`

This runs ANTLR4 to generate parser/lexer Java sources from the new `.g4` files. If there are errors, they will be ANTLR grammar errors — fix them before proceeding.

Expected: No ANTLR generation errors.

**Step 5: Commit grammar + base class swap**

```bash
git add -A hisi-dev-tool/src/main/antlr4/com/huawei/hisi/knowledgegraph/python/parser/
git add -A hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/python/parser/Python3*.java
git add -A hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/python/parser/Python*.java
git commit -m "feat(kg): replace Python3 grammar with python3_12 grammar from grammars-v4"
```

---

### Task 7: Rewrite PythonAstVisitor for new grammar rule names

**Files:**
- Modify: `hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/python/PythonAstVisitor.java`

This is the largest task. The visitor must be rewritten to use the new grammar's rule names and context types. The key changes:

**Step 1: Study the generated parser to learn exact rule names**

After Task 6, the ANTLR-generated source is at:
`hisi-dev-tool/target/generated-sources/antlr4/com/huawei/hisi/knowledgegraph/python/parser/PythonParser.java`

Search this file for the context class names that correspond to:
- `file_input` → `File_inputContext` (likely unchanged)
- `function_def` / `function_def_raw` → the function definition rule
- `class_def` / `class_def_raw` → the class definition rule
- `import_name` → import name rule
- `import_from` → import from rule
- `primary` → the expression rule replacing `atom_expr`
- `decorators` / `decorator` → decorator rules
- `parameters` / `params` → parameter rules
- `arguments` → call arguments

Run:
```bash
grep -n "public static class.*Context extends" hisi-dev-tool/target/generated-sources/antlr4/com/huawei/hisi/knowledgegraph/python/parser/PythonParser.java | grep -i "function_def\|class_def\|import_\|primary\|decorator\|param\|argument\|file_input"
```

**Step 2: Rewrite PythonAstVisitor**

Replace the entire `PythonAstVisitor.java` file. The new version must:

1. Extend `PythonParserBaseVisitor<Void>` (was `Python3ParserBaseVisitor<Void>`)
2. Import from `PythonParser` (was `Python3Parser`)
3. Override visitor methods using the new grammar's context class names
4. Keep the same public API: `visit(File_inputContext ctx, String filePath, String modulePath) → PyModule`
5. Keep the same output model: PyModule, PyClass, PyFunction, PyImport, PyCall
6. Preserve `ClassFrame`, `ScopeKind`, `scopeStack`, `classFrames`, `functionQualNameStack` internal state

**Rule-by-rule mapping guidance:**

- **Decorators:** In the new grammar, `function_def` and `class_def` contain decorators inline. Instead of `visitDecorated`, extract decorators from the `function_def`/`class_def` context's `decorators()` accessor.

- **Function definitions:** Override the raw function def rule. Extract:
  - Name: `ctx.NAME()` or the name token
  - Parameters: from the `params()` sub-rule (structure will differ from `typedargslist`)
  - Return type annotation: from `expression()` after `->`
  - Body: `block()` sub-rule

- **Class definitions:** Override the raw class def rule. Extract:
  - Name: `ctx.NAME()` or the name token
  - Base classes: from `arguments()` sub-rule
  - Body: `block()` sub-rule

- **Imports:** `import_name` and `import_from` rules should have similar names but different sub-rule accessors. Adapt accordingly.

- **Call detection:** The new grammar uses left-recursive `primary` rule. Instead of `atom_expr` with `trailer` list:
  - Override `visitPrimary` (or use a tree walker)
  - When a `primary` context has a call form (parenthesized arguments), record a PyCall
  - The callee expression is the text of the child primary context (without the arguments part)
  - Extract `firstStringArg` and `secondPositionalArg` from the `arguments` sub-rule

**Step 3: Verify the visitor compiles**

Run: `cd hisi-dev-tool && mvn compile -q -pl .`

Fix any compilation errors from incorrect context class names. Reference the generated `PythonParser.java` to find exact names.

Expected: BUILD SUCCESS

**Step 4: Commit**

```bash
git add hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/python/PythonAstVisitor.java
git commit -m "feat(kg): rewrite PythonAstVisitor for python3_12 grammar rules"
```

---

### Task 8: Update PythonKnowledgeGraphBuilder to use new parser classes

**Files:**
- Modify: `hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/python/PythonKnowledgeGraphBuilder.java`

**Step 1: Update imports and parser references**

In `parseFileInternal` method (around line 261-268), change:

```java
// Old:
import com.huawei.hisi.knowledgegraph.python.parser.Python3Lexer;
import com.huawei.hisi.knowledgegraph.python.parser.Python3Parser;
// ...
Python3Lexer lexer = new Python3Lexer(CharStreams.fromString(source));
Python3Parser parser = new Python3Parser(new CommonTokenStream(lexer));
PyModule module = new PythonAstVisitor().visit(parser.file_input(), filePath, modulePath);
```

To:

```java
// New:
import com.huawei.hisi.knowledgegraph.python.parser.PythonLexer;
import com.huawei.hisi.knowledgegraph.python.parser.PythonParser;
// ...
PythonLexer lexer = new PythonLexer(CharStreams.fromString(source));
PythonParser parser = new PythonParser(new CommonTokenStream(lexer));
PyModule module = new PythonAstVisitor().visit(parser.file_input(), filePath, modulePath);
```

**Step 2: Search for any other references to Python3Lexer/Python3Parser in the file**

Run: `grep -n "Python3" hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/python/PythonKnowledgeGraphBuilder.java`

Update ALL occurrences.

**Step 3: Verify compilation**

Run: `cd hisi-dev-tool && mvn compile -q -pl .`
Expected: BUILD SUCCESS

**Step 4: Commit**

```bash
git add hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/python/PythonKnowledgeGraphBuilder.java
git commit -m "feat(kg): update PythonKnowledgeGraphBuilder for new parser class names"
```

---

### Task 9: Create test Python files with 3.8–3.12 syntax features

**Files:**
- Create: `hisi-dev-tool/src/test/resources/python-test-fixtures/python312_features.py`

**Step 1: Create test fixture file**

Create a Python file that uses ALL the previously-failing syntax features:

```python
"""Test fixture for Python 3.8-3.12 syntax features that previously caused parse failures."""
import os
from typing import Any

# --- Python 3.8: walrus operator ---
def find_first_match(items: list[str], prefix: str) -> str | None:
    if (match := next((i for i in items if i.startswith(prefix)), None)) is not None:
        return match
    return None

# --- Python 3.10: match/case (already supported, regression check) ---
def classify(value: Any) -> str:
    match value:
        case int():
            return "integer"
        case str():
            return "string"
        case _:
            return "other"

# --- Python 3.11: except* (ExceptionGroup) ---
def handle_errors() -> None:
    try:
        pass
    except* ValueError as eg:
        for e in eg.exceptions:
            print(e)
    except* TypeError as eg:
        for e in eg.exceptions:
            print(e)

# --- Python 3.12: type alias (PEP 695) ---
type Point = tuple[float, float]
type Matrix[T] = list[list[T]]

# --- Python 3.12: type params on function (PEP 695) ---
def first[T](items: list[T]) -> T:
    return items[0]

# --- Python 3.12: type params on class (PEP 695) ---
class Stack[T]:
    def __init__(self) -> None:
        self._items: list[T] = []

    def push(self, item: T) -> None:
        self._items.append(item)

    def pop(self) -> T:
        return self._items.pop()

# --- Python 3.12: f-string nested quotes (PEP 701) ---
def format_greeting(name: str) -> str:
    return f"Hello, {name.replace('World', 'Python')}!"

# --- Regular function for call detection baseline ---
def process_data(data: list[str]) -> list[str]:
    result = find_first_match(data, "test")
    greeting = format_greeting("World")
    os.path.exists("/tmp")
    return [r for r in data if r]
```

**Step 2: Commit**

```bash
git add hisi-dev-tool/src/test/resources/python-test-fixtures/python312_features.py
git commit -m "test(kg): add Python 3.8-3.12 syntax test fixture"
```

---

### Task 10: Verify grammar replacement with parse test

**Files:**
- None (verification only)

**Step 1: Full backend compilation**

Run: `cd hisi-dev-tool && mvn compile -q`
Expected: BUILD SUCCESS — this confirms ANTLR generation, base class compilation, visitor compilation, and builder compilation all pass.

**Step 2: Run existing tests**

Run: `cd hisi-dev-tool && mvn test -q 2>&1 | tail -20`

Check that no existing tests fail. Some tests may not exist for Python KG — that's fine. The goal is zero regression.

**Step 3: Manual verification (optional but recommended)**

If the application can be started locally:
1. Start backend
2. Point at a Python project using 3.12 syntax
3. Trigger KG generation
4. Verify no "Failed to parse Python file" WARN logs for 3.12 syntax files
5. Verify method nodes are created for functions using walrus, except*, type statements

**Step 4: Commit (if any fixes were needed)**

```bash
git add -A
git commit -m "fix(kg): resolve grammar integration issues"
```

---

## Verification Checklist

### Feature A
- [ ] `GET /api/knowledge-graph/entry-types?projectPath=X` returns list of distinct entry types
- [ ] GraphExplorerTab shows dynamic radio buttons matching project's actual entry types
- [ ] Non-web project (no entries) shows "该项目未检测到入口点" with guidance button
- [ ] Clicking guidance button switches to class browse mode
- [ ] Entry type filtering + pagination still works

### Feature B
- [ ] `mvn compile` succeeds (ANTLR generation + Java compilation)
- [ ] Python file with walrus `:=` parses successfully
- [ ] Python file with `except*` parses successfully
- [ ] Python file with `type X = ...` parses successfully
- [ ] Python file with `def first[T]` parses successfully
- [ ] Python file with `class Stack[T]` parses successfully
- [ ] Python file with f-string nested quotes parses successfully
- [ ] Existing Python 3.10 features (match/case) still parse correctly
- [ ] Method nodes, class nodes, import, and call-site extraction work correctly
- [ ] No regression in method/call counts for existing Python test projects
