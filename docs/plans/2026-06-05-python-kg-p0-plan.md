# P0: Python KG 核心短板补齐 — 实施计划

**Goal**: 将 Python KG 从"部分覆盖"提升到"覆盖主流 Web 框架核心场景"，补齐继承解析、类型注解、扫描器增强、数据模型提取、复杂度计算。

**Architecture**: 四阶段递进 — 模型层→继承→扫描器→数据模型+复杂度。每阶段独立编译验证。

**Tech Stack**: Java 17, ANTLR 4.13.1, Spring Data Neo4j, Lombok

---

## 现状总结（已对齐）

### 已有能力
- ANTLR4 语法覆盖 Python 3.0-3.12
- 框架检测：FastAPI、Django、Flask
- 入口扫描：FastAPI 路由、Django URL、Flask route、Celery task、__main__
- 调用解析：self/direct/import/wildcard/module-level
- 数据模型：Pydantic、Django Model、SQLAlchemy、dataclass（字段仅从 __init__ 提取）
- HTTP/MQ bridge 边

### 缺口清单
1. **无继承解析** — super() 在 BUILTINS 中被跳过，self.method() 不搜索父类，无 IMPLEMENTS/EXTENDS/OVERRIDE
2. **无类型注解** — PyFunction 无 paramTypes/returnType，PythonAstVisitor 不提取 tfpdef.test()
3. **Flask 残缺** — 无 @app.get/post 快捷方式、methods=[...] 仅取第一个、无 Blueprint prefix
4. **Django 残缺** — 无 DRF ViewSet、无 router.register()、ViewResolver 跳过通配符 import
5. **数据模型字段提取差** — 依赖 __init__ 参数代理，不提取类注解赋值
6. **无复杂度** — MethodNode.complexity 从未赋值
7. **无方法体** — MethodNode.methodBody 从未赋值

### 关键代码位置
| 文件 | 职责 |
|------|------|
| `PythonAstVisitor.java` | AST 遍历，提取类/函数/导入/调用 |
| `PyFunction.java` | 函数模型（@Value @Builder） |
| `PyClass.java` | 类模型（@Value），有 baseClasses 但未解析 |
| `PyCall.java` | 调用站点模型 |
| `PythonCallGraphResolver.java` | 调用图解析，self/super/import 解析 |
| `PythonKnowledgeGraphBuilder.java` | KG 构建编排，节点/边创建 |
| `FlaskRouteScanner.java` | Flask 路由扫描 |
| `DjangoUrlScanner.java` + `DjangoViewResolver.java` | Django URL + 视图解析 |
| `PythonDataModelScanner.java` | 数据模型检测+字段提取 |

---

## Phase 1: 模型层扩展（Foundation）

> 前置依赖：无。后续所有 Phase 均依赖此阶段。

### Task 1.1: PyFunction 扩展 — 添加参数类型和返回类型

**文件**: `hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/python/model/PyFunction.java`

**当前**: @Value @Builder class，字段：name, qualName, paramNames, decorators, lineStart, lineEnd, isMethod, enclosingClass

**改动**: 新增两个字段：
```java
List<String> paramTypes    // 参数类型注解文本，如 ["str", "Optional[int]", ""]（无注解则为空字符串）
String returnType           // 返回类型注解文本，如 "List[User]"，无则 null
```

**验证**: `mvn compile -q`

---

### Task 1.2: PyClass 扩展 — 添加类属性字段

**文件**: `hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/python/model/PyClass.java`

**当前**: @Value class，字段：name, baseClasses, decorators, methods, lineStart, lineEnd

**改动**: 新增字段：
```java
List<PyClassAttribute> classAttributes  // 类级别的注解赋值和属性赋值
```

**新增模型**: `PyClassAttribute.java`（同目录）
```java
@Value @Builder
public class PyClassAttribute {
    String name;              // 属性名
    String typeAnnotation;    // 类型注解（如 "str"、"Column(Integer)"），无则 null
    String defaultValue;      // 默认值文本，无则 null
}
```

**验证**: `mvn compile -q`

---

### Task 1.3: PythonAstVisitor 扩展 — 提取类型注解和类属性

**文件**: `hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/python/PythonAstVisitor.java`

**改动 1 — visitFuncdef 提取参数类型和返回类型**（当前 line 136-181）:

在遍历 `typedargslist` 的 `typedelem` 时，除了取 `tfpdef.name()` 还要取 `tfpdef.test()`（类型注解）：
```java
// 现有：paramNames.add(elem.tfpdef().name().getText());
// 新增：
paramTypes.add(elem.tfpdef().test() != null ? elem.tfpdef().test().getText() : "");
```

提取返回类型：`funcdef` 规则有 `('->' test)?` 部分。在 funcdef context 中检查 `ctx.test()` 获取返回类型。

**改动 2 — 添加 visitExpr_stmt 处理类级别赋值**:

新增 override `visitExpr_stmt`。当处于 class scope（classFrames 非空）时：
- 检查 `annassign` 子规则（`name: type = value`）→ 提取为 PyClassAttribute(name, typeAnnotation, defaultValue)
- 检查普通赋值（`name = CallExpr()`）→ 提取为 PyClassAttribute(name, null, valueText)
  - 重点识别：`field_name = models.CharField(...)` / `field_name = Column(...)` / `field_name = Field(...)` 等 ORM 字段

**改动 3 — ClassFrame 扩展**:

内部类 ClassFrame 新增 `List<PyClassAttribute> classAttributes` 字段。在 `visitClassdef` 构建 PyClass 时传入 `classAttributes(frame.classAttributes)`。

**注意**: PyFunction 和 PyClass 是 @Value 不可变类（Lombok @Builder），修改后需要确认 builder 模式仍然正确编译。

**验证**: `mvn compile -q`

---

## Phase 2: 继承解析（Inheritance Resolution）

> 前置依赖：Phase 1 完成（需要 PyClass.baseClasses 和跨模块类索引）

### Task 2.1: 构建跨模块类注册表

**文件**: `hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/python/call/PythonCallGraphResolver.java`

**改动**: 在 `indexModules()` 方法中，新增一个 project-wide 的类索引：
```java
// 新增索引
Map<String, PyClass> classesByQualifiedName;  // "module_path.ClassName" -> PyClass
Map<String, List<PyClass>> classesBySimpleName;  // "ClassName" -> [PyClass1, PyClass2, ...]
```

`classesByQualifiedName` 在 indexModules 时填充：对每个 module 的每个 class，key = `module.getModulePath() + "." + cls.getName()`。

`classesBySimpleName` 也填充：key = `cls.getName()`。当有多个同名类时，通过 import 信息消歧。

**新增方法**: `resolveBaseClass(String baseClassText, PyModule currentModule)` — 将 baseClasses 中的原始文本解析为 FQN：
1. 如果是当前模块中的类 → `currentModule.modulePath + "." + baseClassText`
2. 如果是 imported 的类（通过 import 索引查找）→ 目标 module + className
3. 如果是 dotted name（如 `serializers.ModelSerializer`）→ 尝试解析为模块路径
4. 如果无法解析 → 返回 null（跳过该继承关系）

**验证**: `mvn compile -q`

---

### Task 2.2: 创建 IMPLEMENTS / EXTENDS / OVERRIDE 关系

**文件**: `hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/python/PythonKnowledgeGraphBuilder.java`

**改动 — 新增方法 `buildInheritanceRelations()`**:

遍历所有模块的所有类，对每个有 baseClasses 的类：
1. **解析 base class FQN**（调用 resolveBaseClass）
2. **区分 IMPLEMENTS vs EXTENDS**：
   - Python 无语法层面区分。策略：如果 base class 的方法有 `@abstractmethod` 装饰器 → IMPLEMENTS；否则 → EXTENDS
   - 简化策略：全部标记为 EXTENDS，后续有需求再区分（因为 Python 的 ABC/Protocol 在运行时与普通类无语法差异）
3. **构建 EXTENDS 边**：`(子类方法) -[:EXTENDS]-> (父类方法)` — 使用 className 匹配，Java 管线已有的 Cypher 可直接复用
4. **构建 OVERRIDE 边**：对每个 EXTENDS 的父子类对，同名方法创建 `(子类方法) -[:OVERRIDE]-> (父类方法)`

**数据结构**: 复用 Java 管线的 `ClassExtends` 和 `MethodOverride` 模型（已存在于 `knowledgegraph/model/`）。

**持久化**: 调用 `neo4jStorageService` 已有的 `createExtendsRelations()` 和 `createOverrideRelations()` 方法。

**验证**: `mvn compile -q`，然后用含继承的 Python 项目测试

---

### Task 2.3: 修复 super() 调用解析

**文件**: `hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/python/call/PythonCallGraphResolver.java`

**当前**: `super` 在 PYTHON_BUILTINS 中（line 52），所有 `super().method()` 调用被直接跳过。

**改动**:
1. 从 PYTHON_BUILTINS 中移除 `"super"`
2. 新增 `resolveSuperCall()` 方法：
   - `super().method()` 被归一化为 `super.method`
   - parts = ["super", "method"]
   - 找到 enclosing class
   - 通过 Task 2.1 的跨模块类索引找到父类
   - 在父类中查找 method
   - 返回 edge，callType = "SUPER"
3. 在 `resolveCall()` 的调用分发中，当 head == "super" 时调用 `resolveSuperCall()`

**改动 — 修复 self.method() 继承查找**:

修改 `findMethod()` (line 562-569)：当在当前类找不到方法时，沿继承链向上搜索父类。使用 Task 2.1 的 resolveBaseClass 找到父类 PyClass，递归查找。

**验证**: `mvn compile -q`

---

## Phase 3: 扫描器增强（Scanner Enhancement）

> 前置依赖：Phase 1 完成（需要 PyClass.baseClasses 解析能力用于 DRF ViewSet 检测）。与 Phase 2 可并行。

### Task 3.1: Flask 扫描器增强

**文件**: `hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/python/scanner/FlaskRouteScanner.java`

**改动**:

1. **新增方法快捷装饰器支持** — 扩展 decorator 匹配正则，增加 `@app.get/post/put/delete/patch` 模式（与 FastApiRouteScanner 相同的 `<identifier>.<method>(...)` 模式）

2. **多方法路由** — 从 `methods=[...]` 中提取所有方法，每个方法生成一个 EntryPointNode

3. **Blueprint URL prefix** — 复制 FastApiRouteScanner 的 `buildRouterPrefixMap` 模式：
   - 扫描模块级 PyCall 中的 `Blueprint(...)` 构造调用
   - 从 source lines 中提取变量名
   - 提取 `url_prefix` 参数（从 firstStringArg 或 keyword arg）
   - 在 scanFunction 中按 identifier 查找 prefix

4. **删除第一个方法的限制** — 当 methods 列表有多个值时，全部生成

**验证**: `mvn compile -q`

---

### Task 3.2: Django DRF ViewSet 支持

**文件**:
- `hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/python/scanner/DjangoUrlScanner.java`
- `hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/python/scanner/DjangoViewResolver.java`

**改动 1 — DjangoUrlScanner 检测 router.register()**:

在 `scanModule()` 中新增对 `router.register()` 调用的处理：
- `calleeExpression` 最后一段为 `register`，且前一截为 `router` / `DefaultRouter` / `SimpleRouter` 变量名
- `firstStringArg` = URL prefix（如 `r'users'`）
- `secondPositionalArg` = ViewSet 类名
- 对每个 register 调用，解析 ViewSet 的 DRF action 方法（list/create/retrieve/update/partial_update/destroy）
- 生成对应的 EntryPointNode

**改动 2 — DjangoViewResolver 新增 DRF action 映射**:

新增 DRF ViewSet 基类识别：
- 检测 baseClasses 是否包含 `ViewSet`、`ModelViewSet`、`ReadOnlyModelViewSet`、`GenericViewSet`
- 当识别为 ViewSet 时，使用 DRF action 映射而非 Django CBV 的 HTTP 方法映射：
  ```
  list → GET (list URL)
  create → POST (list URL)
  retrieve → GET (detail URL)
  update → PUT (detail URL)
  partial_update → PATCH (detail URL)
  destroy → DELETE (detail URL)
  ```
- 检测 `@action(detail=True/False, methods=[...])` 装饰器，提取自定义 action

**改动 3 — 处理通配符 import**:

在 `findBindingFor()` 中，当 `symbol == "*"` 时，不跳过，改为尝试在目标模块中搜索匹配的函数/类名。

**验证**: `mvn compile -q`

---

## Phase 4: 数据模型 + 复杂度

> 前置依赖：Phase 1 完成（需要 classAttributes）

### Task 4.1: 数据模型字段提取增强

**文件**: `hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/python/PythonDataModelScanner.java`

**改动**: 重写字段提取逻辑（当前 line 48-52）：

```java
// 当前（仅从 __init__ 提取）:
List<String> fields = pyClass.getMethods().stream()
    .filter(m -> m.getName().equals("__init__"))
    .flatMap(m -> m.getParamNames().stream())
    .filter(p -> !p.equals("self"))
    .collect(Collectors.toList());

// 改为（优先从 classAttributes 提取）:
List<String> fields = new ArrayList<>();
// 1. 优先使用类级别属性（Pydantic/dataclass/Django Model 的主流写法）
if (pyClass.getClassAttributes() != null && !pyClass.getClassAttributes().isEmpty()) {
    fields = pyClass.getClassAttributes().stream()
        .map(PyClassAttribute::getName)
        .collect(Collectors.toList());
}
// 2. 回退到 __init__ 参数（兼容传统写法）
if (fields.isEmpty()) {
    fields = pyClass.getMethods().stream()
        .filter(m -> "__init__".equals(m.getName()))
        .flatMap(m -> m.getParamNames().stream())
        .filter(p -> !"self".equals(p))
        .collect(Collectors.toList());
}
```

同时，字段信息可以包含类型注解（从 PyClassAttribute.typeAnnotation），丰富 DataModelNode.fields 的内容（可选增强：从 bare names 改为 "name: type" 格式）。

**验证**: `mvn compile -q`

---

### Task 4.2: McCabe 复杂度计算

**文件**: `hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/python/PythonKnowledgeGraphBuilder.java`

**改动**: 新增方法 `calculateComplexity(PyModule module, PyFunction function)`:

由于 PyFunction 不存储方法体文本，需要从源文件中按行范围提取，然后用正则计数：
```java
private int calculateComplexity(String filePath, int startLine, int endLine) {
    List<String> lines = readSourceLines(filePath, startLine, endLine);
    int complexity = 1;  // 基础复杂度
    String body = String.join(" ", lines);

    // 计数决策点
    complexity += countKeyword(body, "if ");
    complexity += countKeyword(body, "elif ");
    complexity += countKeyword(body, "for ");
    complexity += countKeyword(body, "while ");
    complexity += countKeyword(body, "except ");
    complexity += countKeyword(body, "and ");
    complexity += countKeyword(body, "or ");

    // 三元表达式: X if COND else Y
    complexity += countTernary(body);

    return complexity;
}
```

在构建 MethodNode 时设置 `.complexity(calculateComplexity(...))`。

**验证**: `mvn compile -q`

---

### Task 4.3: 方法体提取

**文件**: `hisi-dev-tool/src/main/java/com/huawei/hisi/knowledgegraph/python/PythonKnowledgeGraphBuilder.java`

**改动**: 在构建 MethodNode 时，从源文件按行范围读取方法体文本，压缩后设置到 `methodBody` 字段。

```java
private String extractMethodBody(String filePath, int startLine, int endLine) {
    // 读取源文件 startLine 到 endLine 的内容
    // 压缩空白（与 Java 管线的 compressMethodBody 类似）
    // 返回压缩后的文本
}
```

**验证**: `mvn compile -q`

---

## 文件变更清单

| Phase | 文件 | 改动 |
|-------|------|------|
| P1 | `PyFunction.java` | 新增 paramTypes, returnType 字段 |
| P1 | `PyClassAttribute.java` | **新文件** — 类属性模型 |
| P1 | `PyClass.java` | 新增 classAttributes 字段 |
| P1 | `PythonAstVisitor.java` | 提取类型注解、类属性、新增 visitExpr_stmt |
| P2 | `PythonCallGraphResolver.java` | 跨模块类索引、resolveBaseClass、resolveSuperCall、继承链 findMethod |
| P2 | `PythonKnowledgeGraphBuilder.java` | buildInheritanceRelations、EXTENDS/OVERRIDE 边创建 |
| P3 | `FlaskRouteScanner.java` | @app.get/post、多方法、Blueprint prefix |
| P3 | `DjangoUrlScanner.java` | router.register() 检测 |
| P3 | `DjangoViewResolver.java` | DRF ViewSet action 映射、通配符 import |
| P4 | `PythonDataModelScanner.java` | classAttributes 字段提取 |
| P4 | `PythonKnowledgeGraphBuilder.java` | 复杂度计算、方法体提取 |

## 工期估算

| Phase | 工期 | 前置依赖 |
|-------|------|---------|
| P1: 模型层 | 0.5 天 | 无 |
| P2: 继承解析 | 1.5 天 | P1 |
| P3: 扫描器 | 1.5 天 | P1（可与 P2 并行） |
| P4: 数据模型+复杂度 | 0.5 天 | P1 |
| **Total** | **~4 天** | |
