# 设计文档 — 轻量化系统重构

## 架构决策

### ADR-1: 前端清理策略 — 路由先删后验

**状态**: 已采用

**决策**: 前端清理按引用链自顶向下（路由 → 页面 → 组件 → API → Store），每步 `npm run build` 验证。

**理由**: TypeScript 编译器是最可靠的引用检测器 —— 任何遗漏的引用都会导致编译失败，零误删风险。

**替代方案评估**:
- ❌ 手工 Grep 验证 → 可能漏掉动态 import、lazy-load 引用
- ✅ TypeScript 编译验证 → 编译器覆盖所有引用路径，100% 可靠

### ADR-2: 后端删除策略 — 逐个模块 + 全量回归

**状态**: 已采用

**决策**: 后端每次删除一个独立包/类后运行 `mvn test` 全量回归，确认通过再进入下一步。

**理由**: Java 编译器的引用检测覆盖编译期，`mvn test` 覆盖运行时（Spring context 注入、反射调用）。两步验证确保零误删。

**不可行方案**: 多个模块一起删然后一次测试 → 若失败难以定位是哪个模块误删。

### ADR-3: claude-session 保留决策

**状态**: 已采用

**决策**: `/claude-session` 路由和 `ClaudeSession.vue` 保留。虽然不在侧边栏，但被 `MethodReferenceGraph.vue`（知识图谱选项卡）和 `ClaudeTerminal.vue`（workspace 流程）动态导航使用。

**证据**: 
```typescript
// CallChainGraph.vue → MethodReferenceGraph.vue
router.push({ name: 'ClaudeSession', query: { sessionId } })

// ClaudeTerminal.vue
workspaceStore.bindClaudeSession(workspaceStore.currentSessionId, claudeSessionId)
```

### ADR-4: call-chain 视图组件保留策略

**状态**: 已采用

**决策**: 删除 `/call-chain/*` 5 条重定向路由，但保留 `views/call-chain/` 下所有组件。这些组件被 `KnowledgeGraphView.vue` 直接导入使用（tab=methodRef），删除组件会破坏知识图谱页面的功能。

## 文件影响地图

### Phase 1 已完成 — 前端清理

```
hisi-dev-tool-frontend/src/
├── views/
│   ├── ❌ HomeView.vue                    (Vue scaffold)
│   ├── ❌ AboutView.vue                   (Vue scaffold)
│   ├── ❌ search/SemanticSearchView.vue   (死代码)
│   │   └── components/
│   │       ├── ❌ CodePreviewPanel.vue
│   │       └── ❌ SearchResultsPanel.vue
│   └── ❌ mcp/McpGuide.vue                (死代码)
├── api/
│   ├── ✏️ index.ts                        (清理 scaffold)
│   ├── ❌ search.ts
│   ├── ❌ mcp.ts
│   └── ❌ naturalLanguage.ts
├── stores/
│   └── ❌ naturalLanguageStore.ts
├── router/
│   └── ✏️ index.ts                        (删 6 条路由)
└── components/layout/
    └── ✏️ AppSidebar.vue                  (清导航项)
```

### Phase 2 待执行 — 后端清理

```
hisi-dev-tool/src/main/java/com/huawei/hisi/
├── service/
│   └── ❌ intent/                         (9 文件, ~600 行)
│       ├── DialogController.java
│       ├── DialogContext.java
│       ├── DialogStateManager.java
│       ├── EntityExtraction.java
│       ├── IntentResult.java
│       ├── IntentType.java
│       ├── InterventionHandler.java
│       ├── NaturalLanguageDiagnosisCoordinator.java
│       └── DiagnosisResponse.java
├── controller/
│   ├── ❓ ExceptionPathController.java    (待确认)
│   └── ❓ GitController.java              (待确认)
├── service/
│   ├── ❓ IFlytekEmbeddingService.java    (待确认)
│   └── ❓ SiliconFlowEmbeddingService.java (待确认)
├── config/
│   ├── ❓ IFlytekConfig.java              (待确认)
│   └── ❓ SiliconFlowConfig.java          (待确认)
└── project/
    ├── ❓ remote/                         (待确认, 6 文件)
    ├── ❓ group/                          (待确认, 3 文件)
    └── ❓ namegroup/                       (待确认, 3 文件)
```

## 数据流（不变）

```
用户 → AppSidebar(导航) → Vue Router(路由守卫)
  → API Module(axios) → Spring Boot Controller
  → Service(业务逻辑) → Repository(SQLite/Neo4j)
```

## 安全边界

1. **前端删除**: TS 编译器 = 引用完整性保证
2. **后端删除**: `mvn compile` + `mvn test` = 编译期 + 运行时完整性保证
3. **回滚**: 每步独立 commit，`git revert <commit>` 单步回滚
