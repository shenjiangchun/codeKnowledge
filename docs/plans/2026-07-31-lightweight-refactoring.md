# HiSi DevTool v5.0 轻量化重构实施计划

> **执行者必读：** 本计划在 `release_0731` 分支上执行，每个步骤完成后需通过回归测试再进入下一步。

**目标：** 将 HiSi DevTool v5.0 从当前 200+ 文件 / 286 入口点 / 25 前端路由的规模瘦身为轻量化系统，移除废弃代码、丧尸代码和僵尸路由，优化前端体验。

**架构现状：**
- 后端：Spring Boot 3.2，Java 17，~200 个 Java 文件，5051 方法节点，286 入口点
- 前端：Vue 3.5 + TypeScript + Element Plus，~70 个 Vue 组件，25 个路由，30+ API 模块
- 测试：~100 个后端测试文件
- 附模块：hisi-mcp-server、hisi-capture-spring-boot-starter、hisi-otel-extension

**技术栈：** Spring Boot 3.2 / Vue 3.5 / Neo4j / SQLite / Element Plus / Pinia

---

## 1. 需求理解

**核心目标**：系统瘦身——移除废弃功能、丧尸代码和僵尸路由，优化前端体验，建立安全的渐进式重构流程。

**功能范围**：
- 识别并移除未使用的前端页面/路由/组件
- 识别并移除后端未使用的 Controller/Service
- 清理重复/废弃的配置（多 embedding 服务等）
- 优化前端侧边栏和导航体验
- 建立"独立页面逐步搬迁"的安全重构模式

**约束条件**：
- 在 `release_0731` 分支操作，不动 main/master
- 每一步完成后必须回归测试（`mvn test` 全绿）
- 外科手术式改动——只删明确死代码，不动存疑代码
- 前端和后端清理分开进行，每部分独立验证

**隐含需求**：
- 保留所有核心功能：知识图谱、RAM Chat、Fix Chat、日志分析、合入分析、项目管理、技能市场
- 前端 API 代理配置不变（`vite.config.ts` → `http://localhost:8080`）

---

## 2. 现状分析

### 2.1 前端死代码清单

| # | 文件/路由 | 状态 | 证据 |
|---|----------|------|------|
| 1 | `views/HomeView.vue` | **死代码** | 不在 router 中，Vue scaffold 残留 |
| 2 | `views/AboutView.vue` | **死代码** | 不在 router 中，Vue scaffold 残留 |
| 3 | `/mcp-guide` 路由 | **僵尸重定向** | 仅执行 `redirect: '/skill-market'` |
| 4 | `views/mcp/McpGuide.vue` | **死代码** | 路由已重定向，页面不再被访问 |
| 5 | `/call-chain` 路由 (3条) | **僵尸重定向** | 全部 `redirect: '/knowledge-graph?tab=methodRef'` |
| 6 | `/call-chain/*` 视图组 | **可能死代码** | 路由全重定向，但需确认是否有直接导航 |
| 7 | `views/search/SemanticSearchView.vue` | **确认死代码** | CLAUDE.md 标注"历史残留，暂不可用" |
| 8 | `api/index.ts` | **示例代码** | 含 `health`/`devices` 示例，非项目 API |
| 9 | `/claude-session` 路由 | **孤立页面** | 不在侧边栏菜单中，需确认是否仍使用 |
| 10 | `api/naturalLanguage.ts` | **废弃** | 侧边栏注释"自然语言诊断已移除" |
| 11 | `api/claude.ts` | **待确认** | 检查前端是否仍在使用 |
| 12 | `api/mcp.ts` | **待确认** | McpGuide 已废弃，检查是否还有其他调用方 |

### 2.2 后端可疑代码

| # | 模块 | 状态 | 证据 |
|---|------|------|------|
| 1 | `controller/ExceptionPathController.java` | **待确认** | 无对应前端页面 |
| 2 | `controller/GitController.java` | **待确认** | 前端有 `git.ts` API，需确认实际使用 |
| 3 | `service/intent/*` (DialogController/Coordinator) | **疑似废弃** | 侧边栏标注"自然语言诊断已移除" |
| 4 | `service/ZhipuService.java` | **待确认** | 与 Spring AI 迁移是否有冲突 |
| 5 | `service/IFlytekEmbeddingService.java` | **待确认** | 多 embedding 提供者，需确认活跃使用 |
| 6 | `service/SiliconFlowEmbeddingService.java` | **待确认** | 同上 |
| 7 | `config/IFlytekConfig.java` | **待确认** | 配套配置 |
| 8 | `config/SiliconFlowConfig.java` | **待确认** | 配套配置 |
| 9 | `project/remote/*` | **待确认** | 远程项目功能是否在使用 |
| 10 | `project/group/*` | **待确认** | 项目分组是否在使用 |
| 11 | `project/namegroup/*` | **待确认** | 项目名分组是否在使用 |

### 2.3 可复用资源

| 资源 | 路径 | 复用方式 |
|------|------|---------|
| 现有测试套件 | `hisi-dev-tool/src/test/` | 回归验证基线 |
| JWT 认证体系 | `config/Jwt*` | 保持不变 |
| 统一 API 响应格式 | `ApiResponse<T>` | 保持不变 |
| 前端布局组件 | `components/layout/` | 优化样式后保留 |
| WebSocket 基础设施 | `websocket/` | 保留核心功能 |

### 2.4 核心保留功能（不动）

以下功能確认为系统核心，本次重构不动：
- ✅ 知识图谱管理 / 生成 / 检索（`knowledgegraph/`）
- ✅ RAM Chat 对话式需求分析（`ram/chat/`）
- ✅ RAM Phase2 精确位置分析（`ram/nodes/Phase2*`）
- ✅ Fix Chat 异常修复对话流（`fixengine/`）
- ✅ Log Analysis 日志分析（`loganalysis/`）
- ✅ Merge Analysis 合入分析（`mergeanalysis/`）
- ✅ 项目管理（`project/`，除 remote/group 待确认）
- ✅ 技能市场（`skill/`）
- ✅ 用户管理 / 认证（`user/`, `auth/`）
- ✅ APM 调试（`apm/`）
- ✅ Claude 终端（`claude-terminal/`）

---

## 3. 方案设计

### 3.1 总体策略

```mermaid
graph TB
    subgraph "Phase 0: 准备"
        A0[创建 release_0731 分支] --> A1[运行 mvn test 建立基线]
    end
    
    subgraph "Phase 1: 前端清理（零后端影响）"
        B1[移除 Vue scaffold 残留] --> B2[清理僵尸路由和重定向]
        B2 --> B3[移除死代码页面和组件]
        B3 --> B4[清理废弃 API 模块]
        B4 --> B5[优化侧边栏导航]
        B5 --> B6[前端 build 验证]
    end
    
    subgraph "Phase 2: 后端清理（单模块删除 + 全量回归）"
        C1[移除自然语言诊断模块] --> C2[清理未使用的 Controller]
        C2 --> C3[移除废弃 Embedding 服务]
        C3 --> C4[清理配套 Config]
    end
    
    subgraph "Phase 3: 前后端联调"
        D1[全量回归测试] --> D2[前端 dev server 冒烟]
    end
    
    Phase 0 --> Phase 1
    Phase 1 --> B6
    B6 --> Phase 2
    Phase 2 --> Phase 3
```

### 3.2 安全原则

```
操作 = 删除文件 → 编译检查 → 全量测试 → 确认通过 → 下一步
       │                │            │
       ▼                ▼            ▼
  外科手术式        只删明确      若任何测试失败
  最小改动          死代码        → 回滚该步 → 分析 → 重试
```

### 3.3 前端架构目标

```mermaid
graph LR
    subgraph "移除的页面"
        R1[HomeView / AboutView]
        R2[/mcp-guide 重定向]
        R3[/call-chain 重定向]
        R4[SemanticSearchView]
        R5[McpGuide.vue]
        R6[naturalLanguage API]
    end
    
    subgraph "保留的核心页面 (19→15)"
        P1[项目管理]
        P2[知识图谱]
        P3[增强检索]
        P4[日志分析]
        P5[需求分析大师]
        P6[合入分析]
        P7[异常修复]
        P8[APM 调试]
        P9[Claude 终端]
        P10[技能市场]
        P11[KG Skills]
        P12[系统设置]
        P13[用户管理]
        P14[提示词配置]
        P15[术语管理]
    end
```

---

## 4. 实施步骤

### Phase 0：准备

#### 步骤 0.1：创建 release_0731 分支
- **目标**：在独立分支上开始重构
- **文件**：无
- **操作**：
  ```bash
  git checkout -b release_0731
  ```
- **验证**：`git branch --show-current` 输出 `release_0731`

#### 步骤 0.2：建立测试基线
- **目标**：确认当前所有测试通过
- **操作**：
  ```bash
  cd hisi-dev-tool && mvn test --batch-mode
  ```
- **验证**：`BUILD SUCCESS`，0 failures
- **⚠️ 关键**：如果基线不通过，必须先修复再继续

---

### Phase 1：前端清理（后端零改动，随时可独立验证）

#### 步骤 1.1：移除 Vue scaffold 残留文件
- **目标**：删除脚手架生成的未使用页面
- **文件**：
  - 删除：`hisi-dev-tool-frontend/src/views/HomeView.vue`
  - 删除：`hisi-dev-tool-frontend/src/views/AboutView.vue`
- **验证**：`npm run build`（在前端目录）无 TS 编译错误
- **技能**：无需

#### 步骤 1.2：清理僵尸路由
- **目标**：移除仅用于重定向的路由定义
- **文件**：
  - 修改：`hisi-dev-tool-frontend/src/router/index.ts`
  - 删除路由：`/mcp-guide`（重定向到 skill-market）
  - 删除路由：`/call-chain`（重定向到 knowledge-graph）
  - 删除路由：`/call-chain/uri-chain`
  - 删除路由：`/call-chain/method-reference`
  - 删除路由：`/call-chain/chain`
- **验证**：
  1. `npm run build` 无错误
  2. 确认侧边栏无引用这些路由
- **技能**：无需

#### 步骤 1.3：移除 SemanticSearchView 死代码
- **目标**：删除标注为"历史残留"的页面及其 API 模块
- **文件**：
  - 删除：`hisi-dev-tool-frontend/src/views/search/SemanticSearchView.vue`
  - 删除：`hisi-dev-tool-frontend/src/views/search/components/CodePreviewPanel.vue`（检查是否仅 SemanticSearchView 使用）
  - 删除：`hisi-dev-tool-frontend/src/views/search/components/SearchResultsPanel.vue`（同上）
  - 删除：`hisi-dev-tool-frontend/src/api/search.ts`（检查 `/api/search/*` 后端是否存在）
- **确认流程**：先用 Grep 检查文件引用链，确认无其他导入后再删除
- **验证**：`npm run build` 无错误
- **技能**：无需

#### 步骤 1.4：移除 MCP Guide 死代码
- **目标**：删除已无路由引用的 MCP 页面
- **文件**：
  - 删除：`hisi-dev-tool-frontend/src/views/mcp/McpGuide.vue`
  - 删除：`hisi-dev-tool-frontend/src/api/mcp.ts`（检查引用链）
- **验证**：`npm run build` 无错误
- **技能**：无需

#### 步骤 1.5：清理废弃 API 模块
- **目标**：删除前端不再使用的 API 模块文件
- **文件**：
  - 删除：`hisi-dev-tool-frontend/src/api/naturalLanguage.ts`（自然语言诊断已移除）
  - 清理：`hisi-dev-tool-frontend/src/api/index.ts`（移除示例 health/devices 代码，仅保留 request 导出）
- **验证**：`npm run build` 无错误
- **技能**：无需

#### 步骤 1.6：检查 claude-session 孤立页面
- **目标**：确认 `/claude-session` 路由是否仍需保留
- **操作**：
  1. Grep 搜索 `claude-session` 在项目中的所有引用
  2. 检查 `views/claude-session/ClaudeSession.vue` 的功能与 `ClaudeTerminal.vue` 是否重叠
  3. 如果确认废弃 → 删除路由和页面；如果仍使用 → 保留并添加侧边栏入口
- **验证**：`npm run build` + 决策确认
- **技能**：无需

#### 步骤 1.7：优化侧边栏导航
- **目标**：清理侧边栏，移除已删除页面入口，优化菜单结构
- **文件**：
  - 修改：`hisi-dev-tool-frontend/src/components/layout/AppSidebar.vue`
  - 移除 `MenuKey` 中已删除页面对应的类型
  - 精简图标导入（移除未使用的图标）
- **验证**：
  1. `npm run build` 无错误
  2. 侧边栏菜单项数量减少且全部可点击
- **技能**：无需

#### 步骤 1.8：前端最终构建验证
- **目标**：前端完整构建 + TypeScript 类型检查
- **操作**：
  ```bash
  cd hisi-dev-tool-frontend && npm run build
  ```
- **验证**：`BUILD SUCCESS`，产物在 `dist/` 目录

---

### Phase 2：后端清理（每步骤 → 全量回归）

#### 步骤 2.1：移除自然语言诊断模块
- **目标**：删除与前端已移除的"自然语言诊断"对应的后端代码
- **文件**（待 Grep 精确确认）：
  - 删除：`hisi-dev-tool/src/main/java/com/huawei/hisi/service/intent/DialogController.java`
  - 删除：`hisi-dev-tool/src/main/java/com/huawei/hisi/service/intent/DialogContext.java`
  - 删除：`hisi-dev-tool/src/main/java/com/huawei/hisi/service/intent/DialogStateManager.java`
  - 删除：`hisi-dev-tool/src/main/java/com/huawei/hisi/service/intent/EntityExtraction.java`
  - 删除：`hisi-dev-tool/src/main/java/com/huawei/hisi/service/intent/IntentResult.java`
  - 删除：`hisi-dev-tool/src/main/java/com/huawei/hisi/service/intent/IntentType.java`
  - 删除：`hisi-dev-tool/src/main/java/com/huawei/hisi/service/intent/InterventionHandler.java`
  - 删除：`hisi-dev-tool/src/main/java/com/huawei/hisi/service/intent/NaturalLanguageDiagnosisCoordinator.java`
  - 删除：`hisi-dev-tool/src/main/java/com/huawei/hisi/service/intent/DiagnosisResponse.java`
  - 如果 `ApiList.vue` 和 `InterventionPanel.vue` 引用了后端已删除的东西，也要清理
- **⚠️ 重要**：删除前先用 Grep 确认这些类没有被其他模块引用
- **回归**：`mvn test --batch-mode`
- **验证**：`BUILD SUCCESS`

#### 步骤 2.2：移除 ExceptionPathController（如无前端使用）
- **目标**：检查并清理无前端的 Controller
- **操作**：
  1. Grep 搜索 `ExceptionPath` 在前端代码中的引用
  2. 若前端无引用 → 删除 `ExceptionPathController.java` + 相关 service/repository
  3. 若前端有引用 → 跳过
- **回归**：`mvn test --batch-mode`
- **验证**：`BUILD SUCCESS`

#### 步骤 2.3：移除 GitController（如无前端使用）
- **目标**：同上
- **操作**：Grep 搜索 `Git` 在前端 `api/` 中的引用，确认 `git.ts` 的实际用途
- **回归**：`mvn test --batch-mode`
- **验证**：`BUILD SUCCESS`

#### 步骤 2.4：清理废弃的 Embedding 服务
- **目标**：统一 Embedding 服务，移除不再使用的提供者
- **操作**：
  1. Grep 搜索 `IFlytekEmbeddingService` / `SiliconFlowEmbeddingService` 的注入点
  2. 搜索 `@Autowired.*IFlytek` / `@Autowired.*SiliconFlow` 确认使用情况
  3. 若仅 `UnifiedEmbeddingService` 活跃使用 → 删除 IFlytek 和 SiliconFlow 两个类
  4. 同步删除 `IFlytekConfig.java` 和 `SiliconFlowConfig.java`
- **⚠️ 重要**：此步骤可能影响较大，需特别小心确认
- **回归**：`mvn test --batch-mode`
- **验证**：`BUILD SUCCESS`

#### 步骤 2.5：清理 remote project 模块（如确认废弃）
- **目标**：检查远程项目功能是否在使用
- **操作**：
  1. 搜索前端 `remote-project.ts` 的引用
  2. 搜索前端 `RemoteProject` 组件的引用
  3. 若确认废弃 → 删除整个 `project/remote/` 包
- **回归**：`mvn test --batch-mode`
- **验证**：`BUILD SUCCESS`

#### 步骤 2.6：清理 project group/namegroup（如确认废弃）
- **目标**：检查项目分组功能是否在使用
- **操作**：
  1. 搜索前端 `projectGroup.ts` / `projectNameGroup.ts` 的引用
  2. 若确认废弃 → 删除 `project/group/` 和 `project/namegroup/`
- **回归**：`mvn test --batch-mode`
- **验证**：`BUILD SUCCESS`

---

### Phase 3：前端体验优化

#### 步骤 3.1：统一主题和配色
- **目标**：优化全局样式，统一设计语言
- **文件**：
  - 修改：`hisi-dev-tool-frontend/src/styles/` 下的全局样式
  - 修改：`hisi-dev-tool-frontend/src/components/layout/AppLayout.vue`
- **优化点**：
  - 侧边栏颜色更现代化（当前 `#304156` 保留或微调）
  - 全局字体、间距统一
  - 过渡动画优化
- **验证**：`npm run build` + `npm run dev` 肉眼检查

#### 步骤 3.2：统一 API 错误处理
- **目标**：优化 Axios 拦截器，减少各组件中重复的错误处理
- **文件**：
  - 修改：`hisi-dev-tool-frontend/src/utils/request.ts`（检查是否存在）
  - 或修改 `api/index.ts` 的拦截器逻辑
- **验证**：`npm run build` 无错误

---

### Phase 4：最终验证

#### 步骤 4.1：全量后端回归测试
- **操作**：`cd hisi-dev-tool && mvn test --batch-mode`
- **验证**：`BUILD SUCCESS`，0 failures，0 errors

#### 步骤 4.2：前端生产构建
- **操作**：`cd hisi-dev-tool-frontend && npm run build`
- **验证**：BUILD SUCCESS，`dist/` 目录有产物

#### 步骤 4.3：功能冒烟测试（手动）
- **操作**：
  1. 启动后端：`cd hisi-dev-tool && mvn spring-boot:run`
  2. 启动前端：`cd hisi-dev-tool-frontend && npm run dev`
  3. 打开 http://localhost:5173
  4. 快速检查：项目管理 → 知识图谱 → RAM Chat → 日志分析 → 异常修复 → 合入分析
- **验证**：所有核心页面可访问，无控制台错误

#### 步骤 4.4：提交代码
- **操作**：
  ```bash
  git add -A
  git commit -m "refactor: lightweight system cleanup - remove dead code, optimize frontend
  - Remove Vue scaffold files (HomeView, AboutView)
  - Remove zombie routes (mcp-guide, call-chain redirects)
  - Remove SemanticSearchView dead code
  - Remove MCP Guide dead page
  - Clean up unused API modules
  - Remove natural language diagnosis backend
  - Clean up unused controllers and embedding services
  - Optimize sidebar navigation
  - All regression tests pass"
  ```
- **验证**：`git status` 干净

---

## 5. 风险评估

| 风险 | 可能性 | 影响 | 应对策略 |
|------|--------|------|---------|
| 基线测试不通过 | 中 | 高 | 先修复基线再开始重构 |
| 删除的文件被未知引用 | 中 | 高 | 每步删除前用 Grep 全项目搜索引用 |
| embedding 服务删除影响核心功能 | 低 | 高 | 特别小心确认引用链后再删 |
| 前端 build 失败（TS 类型错误） | 中 | 中 | 每步立即 build 验证，快速定位 |
| call-chain 视图被直接 URL 导航访问 | 低 | 低 | 检查是否有外部链接或书签 |
| claude-session 仍有用户使用 | 中 | 中 | 先确认再决定保留或删除 |

## 6. 预估工作量

| 阶段 | 步骤数 | 预估时间 | 风险级别 |
|------|--------|----------|---------|
| Phase 0: 准备 | 2 | 15 min | 低 |
| Phase 1: 前端清理 | 8 | 2-3 h | 低 |
| Phase 2: 后端清理 | 6 | 3-4 h | 中 |
| Phase 3: 前端体验优化 | 2 | 1 h | 低 |
| Phase 4: 最终验证 | 4 | 30 min | 低 |
| **总计** | **22** | **7-9 h** | - |
