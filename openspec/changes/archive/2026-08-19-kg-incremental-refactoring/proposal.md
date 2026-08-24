# KG 增量图谱构建重构

> **定框日期**: 2026-08-05 | **路由**: spec-driven | **风险**: Medium

---

## 意图

### 目标与成功标准

**目标**: 消除现有 3 套增量刷新服务（V0/V1/V2）与全量 KnowledgeGraphBuilder 之间的不一致，基于全量构建方法实现零差异增量构建。

**可观察的成功结果**:
1. 增量刷新后的 Neo4j 图状态 = 全量重建后的图状态（同 commit 下等价）
2. 增量走同一个 KgGenerationQueue，与全量构建串行排队
3. V0/V1/V2 服务标记 `@Deprecated`
4. `mvn test` 全量用例零回归

### 边界与非目标

| 在范围内 | 不在范围内 |
|----------|-----------|
| Java 增量构建（全覆盖 23 步） | TS/JS codegraph 增量支持 |
| Python 增量构建（对齐全量 Python 路径） | 并发多项目 KG 构建 |
| KgGenerationQueue 集成增量排隊 | GlobalAnalysisCache 架构重构 |
| Neo4j dispatch 边先删后建 | MyBatis XML 增量检测（全量重扫可接受） |
| 废弃旧增量服务 | 跨服务链接（CrossServiceLinker）逻辑修改 |

---

## 核心设计

### 原则：全量 23 步，增量仅替换 3 处

```
全量 buildJavaKnowledgeGraph:       增量 incrementalRefresh:

1. cleanOldData()        ←替换→     DETACH DELETE 变更文件节点 + 入口点
                                     删 incoming CALLS
                                     删 dispatch-typed CALLS

2-5. TypeSolver + cache  ←相同→     Phase A: 全量扫描初始化
     扫描 bridge + 扫描 XML           GlobalAnalysisCache

6. 扫描所有文件           ←替换→     Phase C: 仅扫描变更文件
   方法节点 + 入口点 + implMap       scanMethodNodes + createEntryPoints
                                     （复用父类 protected 方法）

7. 扫描所有文件调关系      ←替换→    Phase D: 全量扫描调用关系
   保存全部 CALLS                    过滤 rebuiltNodeIds → 保存

8-23. 后处理全部步骤       ←相同→    Phase E+F: 完全相同的后处理
  IMPLEMENTS/EXTENDS/OVERRIDE        convertFromGlobalCache
  PROXY/dispatch/bridge/synthetic    buildExtends/Override/ProxyRelations
  SQL/EXECUTES_SQL/DataModel          dispatch 先删后建
  向量生成 + checkpoint              vectorGeneration + saveGenerationLog
```

### 组合模式（非继承）

`IncrementalKnowledgeGraphBuilder` 作为独立 `@Service`，持有 `KnowledgeGraphBuilder` 引用（Spring DI），直接调用其 `protected` 方法编排增量流程。

理由：
- 无 CGLIB 双重代理
- 无 super() 参数爆炸（Step 1 已将字段+方法改为 protected）
- 通过 `kgb.generationSemaphore` 共享互斥锁
