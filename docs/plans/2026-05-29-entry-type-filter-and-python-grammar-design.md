# 入口类型动态筛选 + Python 3.12 Grammar 替换 设计文档

## 概述

两个独立改进：

1. **Feature A**: 图谱探索页签的入口类型筛选补全——从硬编码 5 种类型改为动态获取项目实际存在的入口类型，同时为无入口的非 web 项目提供引导体验
2. **Feature B**: Python ANTLR grammar 从 Bart Kiers 3.10 grammar 替换为 RobEin Python 3.12 grammar（grammars-v4），覆盖 walrus `:=`、`except*`、`type` 语句、type params `[T]`、f-string PEP 701

---

## Feature A: 入口类型动态筛选

### 问题

- `GraphExplorerTab.vue` 硬编码 5 个入口类型按钮（ALL/CONTROLLER/SCHEDULED/MQ_LISTENER/FEIGN_CLIENT）
- 后端 `EntryPointNode` 实际支持 10 种类型：HTTP, SCHEDULED, MQ_CONSUMER, FEIGN_CLIENT, FASTAPI_ROUTE, FLASK_ROUTE, DJANGO_VIEW, CELERY_TASK, GRPC, RMI
- 非 web 项目（纯工具/库/CLI）无入口点时，"按入口类型"页签空白无引导

### 方案: 后端新增 `/entry-types` 端点 + 前端动态渲染

#### 后端

**KnowledgeGraphController.java** — 新增端点：

```java
@GetMapping("/entry-types")
public ApiResponse<List<String>> getDistinctEntryTypes(
        @RequestParam(required = false) String projectPath,
        @RequestParam(required = false) List<String> projectPaths)
```

**Neo4jEntryPointNodeRepository.java** — 新增查询：

```java
@Query("MATCH (e:EntryPointNode) WHERE e.projectPath IN $paths RETURN DISTINCT e.entryType ORDER BY e.entryType")
List<String> findDistinctEntryTypesByProjectPaths(@Param("paths") List<String> paths);
```

#### 前端

**knowledgeGraph.ts** — 新增 `getEntryTypes()` 函数

**GraphExplorerTab.vue** — 改动：
- `onMounted` / 项目切换时调用 `getEntryTypes` 获取类型列表
- 动态渲染 `el-radio-button`（不再硬编码）
- 中文 label 映射：

| 类型 | 显示名 |
|------|--------|
| HTTP | HTTP接口 |
| SCHEDULED | 定时任务 |
| MQ_CONSUMER | MQ消费 |
| FEIGN_CLIENT | Feign |
| FASTAPI_ROUTE | FastAPI |
| FLASK_ROUTE | Flask |
| DJANGO_VIEW | Django |
| CELERY_TASK | Celery |
| GRPC | gRPC |
| RMI | RMI |

- 类型列表为空时显示 `el-empty` + "切换到按类浏览" 引导按钮
- 保留用户已有分页逻辑（`entryPagination`、`PageResult`）

---

## Feature B: Python ANTLR Grammar 替换

### 问题

当前 `Python3Parser.g4` / `Python3Lexer.g4`（Bart Kiers, MIT）覆盖到 Python 3.10（match/case）。缺失：

| 语法特性 | Python 版本 | 影响 |
|---------|------------|------|
| walrus `:=` | 3.8 | 整文件 parse failure |
| `except*` (ExceptionGroup) | 3.11 | 整文件 parse failure |
| `type X = ...` (type alias) | 3.12 PEP 695 | 整文件 parse failure |
| `[T]` type params | 3.12 PEP 695 | 整文件 parse failure |
| f-string 嵌套引号 | 3.12 PEP 701 | 可能 tokenize 错误 |

使用这些语法的文件会被 `PythonKnowledgeGraphBuilder` 的 per-file try/catch 捕获并 WARN 跳过，丢失所有方法/类/调用信息。

### 方案: 整体替换为 grammars-v4 python3_12 grammar

来源: [RobEin/ANTLR4-parser-for-Python-3.12](https://github.com/RobEin/ANTLR4-parser-for-Python-3.12)（MIT，收录于 antlr/grammars-v4）

Python 3.13 无新语法特性，3.12 grammar 即可解析 3.13 代码。

#### 文件替换清单

| 旧文件 | 新文件 | 位置 |
|--------|--------|------|
| `Python3Parser.g4` | `PythonParser.g4` | `src/main/antlr4/.../parser/` |
| `Python3Lexer.g4` | `PythonLexer.g4` | `src/main/antlr4/.../parser/` |
| `Python3ParserBase.java` | `PythonParserBase.java` | `src/main/java/.../parser/` |
| `Python3LexerBase.java` | `PythonLexerBase.java` | `src/main/java/.../parser/` |

Package: `com.huawei.hisi.knowledgegraph.python.parser`（不变）

#### PythonAstVisitor 规则名映射

| 当前 Visitor 方法 | 当前规则 | 新规则 | 适配 |
|---|---|---|---|
| `visitDecorated` | `decorated` | 不存在 | decorator 内嵌在 `function_def`/`class_def` 中，在对应 visit 方法中提取 |
| `visitClassdef` | `classdef` | `class_def_raw` | override `visitClass_def_raw`，从中提取类名、基类、decorators |
| `visitFuncdef` | `funcdef` | `function_def_raw` | override `visitFunction_def_raw`，提取函数名、参数、decorators |
| `visitImport_name` | `import_name` | `import_name` | 子规则访问方式变化 |
| `visitImport_from` | `import_from` | `import_from` | 子规则访问方式变化 |
| `visitAtom_expr` | `atom_expr: atom trailer*` | `primary`（左递归） | 改为遍历 primary context 树检测 call |

#### 调用检测策略

当前: 线性遍历 `atom → trailer[]`，每个 `trailer.OPEN_PAREN()` 即为一次调用

新: `primary` 是左递归规则，生成嵌套 context。策略改为递归遍历 `PrimaryContext` 树，当发现 `arguments()` 非空的 primary 节点时记录调用，callee 取该 primary 的子 primary 的文本。

#### PythonKnowledgeGraphBuilder 改动

```java
// 旧: Python3Lexer / Python3Parser
PythonLexer lexer = new PythonLexer(CharStreams.fromString(source));
PythonParser parser = new PythonParser(new CommonTokenStream(lexer));
PyModule module = new PythonAstVisitor().visit(parser.file_input(), filePath, modulePath);
```

#### 不变的部分

- PyModule / PyClass / PyFunction / PyImport / PyCall 模型
- PythonKnowledgeGraphBuilder 业务逻辑
- CallGraphResolver / PythonEntryPointScanner
- ANTLR4 版本 4.13.1 + Maven 插件配置

#### 风险

| 风险 | 应对 |
|------|------|
| INDENT/DEDENT 处理逻辑不同 | 完整替换 LexerBase |
| 规则名映射遗漏 | E2E 测试验证节点/调用数量不退化 |
| 语义谓词 Java target 兼容性 | Base 类一起替换 |
| primary 遍历准确性 | 对比现有 parse 结果 |

---

## 文件变更总览

### Feature A
| 文件 | 动作 |
|------|------|
| `Neo4jEntryPointNodeRepository.java` | 新增 `findDistinctEntryTypesByProjectPaths` |
| `KnowledgeGraphController.java` | 新增 `/entry-types` 端点 |
| `knowledgeGraph.ts` | 新增 `getEntryTypes()` |
| `GraphExplorerTab.vue` | 动态入口类型 + 空状态引导 |

### Feature B
| 文件 | 动作 |
|------|------|
| `Python3Parser.g4` | 删除 → 替换为 `PythonParser.g4` |
| `Python3Lexer.g4` | 删除 → 替换为 `PythonLexer.g4` |
| `Python3ParserBase.java` | 删除 → 替换为 `PythonParserBase.java` |
| `Python3LexerBase.java` | 删除 → 替换为 `PythonLexerBase.java` |
| `PythonAstVisitor.java` | 重写 ~300 行适配新规则名 |
| `PythonKnowledgeGraphBuilder.java` | 更新 Lexer/Parser 类名引用 |
