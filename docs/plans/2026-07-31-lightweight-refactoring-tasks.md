# HiSi DevTool v5.0 轻量化重构实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 从 286 入口点/25 前端路由/200+ Java 文件中移除废弃代码，瘦身为轻量化系统

**Architecture:** 在 `release_0731` 分支上执行——Phase 1 前端清理（零后端影响）→ Phase 2 后端逐模块删除+全量回归 → Phase 3 前端体验优化 → Phase 4 最终验证

**Tech Stack:** Spring Boot 3.2 / Vue 3.5 / TypeScript / Neo4j / SQLite / Element Plus

## Global Constraints

- 每步结束后必须验证（前端 `npm run build` 或后端 `mvn test`）
- 只删明确死代码，不动存疑代码
- 前端和后端分 Phase 独立进行
- 删除前用 Grep 确认无其他引用
- 回归命令：`cd hisi-dev-tool && mvn test --batch-mode`

---

## Phase 0: 准备

### Task 0.1: 检查测试基线

**Files:** 无

- [ ] **Step 1: 运行全量后端测试**
```bash
cd "C:\Users\47583\projects\hisi_dev_tool v5.0\hisi-dev-tool" && mvn test --batch-mode 2>&1 | tail -30
```

- [ ] **Step 2: 检查输出**
验证 `BUILD SUCCESS`，记录任何预存失败。若有不通过——必须先定位修复再继续。

### Task 0.2: 创建 release_0731 分支

- [ ] **Step 1: 切出新分支**
```bash
cd "C:\Users\47583\projects\hisi_dev_tool v5.0" && git checkout -b release_0731
```

- [ ] **Step 2: 验证分支**
```bash
git branch --show-current
```
预期输出：`release_0731`

---

## Phase 1: 前端清理

### Task 1.1: 移除 Vue scaffold 残留文件

**Files:**
- Delete: `hisi-dev-tool-frontend/src/views/HomeView.vue`
- Delete: `hisi-dev-tool-frontend/src/views/AboutView.vue`

- [ ] **Step 1: 确认文件存在且未被导入**
```bash
cd "C:\Users\47583\projects\hisi_dev_tool v5.0" && grep -r "HomeView" hisi-dev-tool-frontend/src/ --include="*.ts" --include="*.vue"
cd "C:\Users\47583\projects\hisi_dev_tool v5.0" && grep -r "AboutView" hisi-dev-tool-frontend/src/ --include="*.ts" --include="*.vue"
```
预期：仅在 HomeView.vue/AboutView.vue 自身中匹配（router 未引用）

- [ ] **Step 2: 删除文件**
Delete `hisi-dev-tool-frontend/src/views/HomeView.vue`
Delete `hisi-dev-tool-frontend/src/views/AboutView.vue`

- [ ] **Step 3: 验证前端 build**
```bash
cd "C:\Users\47583\projects\hisi_dev_tool v5.0\hisi-dev-tool-frontend" && npm run build 2>&1 | tail -10
```
验证：输出含 `✓ built in` 且无 ERROR

### Task 1.2: 清理僵尸路由

**Files:**
- Modify: `hisi-dev-tool-frontend/src/router/index.ts`

- [ ] **Step 1: 确认 call-chain 视图无直接 URL 使用**
```bash
cd "C:\Users\47583\projects\hisi_dev_tool v5.0" && grep -r "call-chain" hisi-dev-tool-frontend/src/ --include="*.ts" --include="*.vue" | grep -v "knowledge-graph" | grep -v "node_modules"
```
预期：仅在 router/index.ts 中找到路由重定向定义

- [ ] **Step 2: 删除僵尸路由定义**
在 `hisi-dev-tool-frontend/src/router/index.ts` 中删除以下路由块：
1. `{ path: '/mcp-guide', redirect: '/skill-market' }` (第11-13行)
2. `{ path: '/call-chain', redirect: '/knowledge-graph?tab=methodRef' }` (第69-71行)
3. `{ path: '/call-chain/uri-chain', redirect: '/knowledge-graph?tab=methodRef' }` (第73-75行)
4. `{ path: '/call-chain/method-reference', redirect: '/knowledge-graph?tab=methodRef' }` (第77-79行)
5. `{ path: '/call-chain/chain', redirect: '/knowledge-graph?tab=methodRef' }` (第81-83行)
6. 同时删除 router.beforeEach 中对应的 `/mcp-guide` 重定向逻辑（第288-290行）

- [ ] **Step 3: 验证前端 build**
```bash
cd "C:\Users\47583\projects\hisi_dev_tool v5.0\hisi-dev-tool-frontend" && npm run build 2>&1 | tail -10
```

### Task 1.3: 移除 SemanticSearchView 死代码

**Files:**
- Delete: `hisi-dev-tool-frontend/src/views/search/SemanticSearchView.vue`
- Potentially Delete: `hisi-dev-tool-frontend/src/views/search/components/CodePreviewPanel.vue`
- Potentially Delete: `hisi-dev-tool-frontend/src/views/search/components/SearchResultsPanel.vue`
- Potentially Delete: `hisi-dev-tool-frontend/src/api/search.ts`

- [ ] **Step 1: 确认 SemantiSearchView 无其他引用**
```bash
cd "C:\Users\47583\projects\hisi_dev_tool v5.0" && grep -r "SemanticSearchView\|semanticSearch\|semantic-search" hisi-dev-tool-frontend/src/ --include="*.ts" --include="*.vue" | grep -v node_modules
```

- [ ] **Step 2: 确认组件文件引用链**
```bash
cd "C:\Users\47583\projects\hisi_dev_tool v5.0" && grep -r "CodePreviewPanel\|SearchResultsPanel" hisi-dev-tool-frontend/src/ --include="*.ts" --include="*.vue"
```
如果仅在 SemanticSearchView.vue 中被引用 → 可安全删除

- [ ] **Step 3: 确认 search.ts 引用链**
```bash
cd "C:\Users\47583\projects\hisi_dev_tool v5.0" && grep -r "from.*api/search\|from.*@/api/search" hisi-dev-tool-frontend/src/ --include="*.ts" --include="*.vue"
```

- [ ] **Step 4: 删除确认的死代码文件**

- [ ] **Step 5: 验证前端 build**
```bash
cd "C:\Users\47583\projects\hisi_dev_tool v5.0\hisi-dev-tool-frontend" && npm run build 2>&1 | tail -10
```

### Task 1.4: 移除 MCP Guide 死代码

**Files:**
- Delete: `hisi-dev-tool-frontend/src/views/mcp/McpGuide.vue`
- Potentially Delete: `hisi-dev-tool-frontend/src/api/mcp.ts`

- [ ] **Step 1: 确认引用链**
```bash
cd "C:\Users\47583\projects\hisi_dev_tool v5.0" && grep -r "McpGuide\|mcp-guide\|api/mcp" hisi-dev-tool-frontend/src/ --include="*.ts" --include="*.vue" | grep -v node_modules
```

- [ ] **Step 2: 删除确认死代码文件**

- [ ] **Step 3: 验证前端 build**
```bash
cd "C:\Users\47583\projects\hisi_dev_tool v5.0\hisi-dev-tool-frontend" && npm run build 2>&1 | tail -10
```

### Task 1.5: 清理废弃 API 模块

**Files:**
- Delete: `hisi-dev-tool-frontend/src/api/naturalLanguage.ts`
- Modify: `hisi-dev-tool-frontend/src/api/index.ts`

- [ ] **Step 1: 确认 naturalLanguage.ts 无其他引用**
```bash
cd "C:\Users\47583\projects\hisi_dev_tool v5.0" && grep -r "naturalLanguage\|natural-language\|api/naturalLanguage" hisi-dev-tool-frontend/src/ --include="*.ts" --include="*.vue" | grep -v node_modules
```

- [ ] **Step 2: 删除 naturalLanguage.ts**

- [ ] **Step 3: 清理 api/index.ts 示例代码**
将 `api/index.ts` 中的 `health`/`devices` 示例函数删除，仅保留 `request` 导出和 `api` 对象（或根据实际使用情况整个简化）

- [ ] **Step 4: 验证前端 build**

### Task 1.6: 检查 claude-session 页面

**Files:**
- Potentially Delete: `hisi-dev-tool-frontend/src/views/claude-session/ClaudeSession.vue`
- Potentially Modify: `hisi-dev-tool-frontend/src/router/index.ts`

- [ ] **Step 1: 检查引用链**
```bash
cd "C:\Users\47583\projects\hisi_dev_tool v5.0" && grep -r "claude-session\|ClaudeSession" hisi-dev-tool-frontend/src/ --include="*.ts" --include="*.vue" | grep -v node_modules
```

- [ ] **Step 2: 决策——保留或删除**
如果 claude-session 不在侧边栏且与 ClaudeTerminal 功能重叠 → 删除路由和页面
如果仍有独立用途 → 保留并考虑添加侧边栏入口

- [ ] **Step 3: 执行决策 + 验证前端 build**

### Task 1.7: 优化侧边栏导航

**Files:**
- Modify: `hisi-dev-tool-frontend/src/components/layout/AppSidebar.vue`

- [ ] **Step 1: 移除 MenuKey 类型中已删除页面对应的 key**
从 `MenuKey` 联合类型中删除不再使用的类型字面量（如 mcp-guide、claude-session 等被删除的页面）

- [ ] **Step 2: 清理未使用的图标导入**
从 `@element-plus/icons-vue` 的导入中删除不再使用的图标

- [ ] **Step 3: 验证前端 build**

### Task 1.8: 前端最终构建验证

- [ ] **Step 1: 全量 TypeScript 类型检查 + 生产构建**
```bash
cd "C:\Users\47583\projects\hisi_dev_tool v5.0\hisi-dev-tool-frontend" && npm run build 2>&1 | tail -15
```
验证：`✓ built in` + 无 ERROR/FAIL

---

## Phase 2: 后端清理

### Task 2.1: 移除自然语言诊断模块

**Files:**
- Delete: 以下 `service/intent/` 下所有文件（共9个类）

- [ ] **Step 1: 确认引用链——全项目 Grep**
```bash
cd "C:\Users\47583\projects\hisi_dev_tool v5.0\hisi-dev-tool" && grep -r "DialogController\|DialogContext\|DialogStateManager\|EntityExtraction\|IntentResult\|IntentType\|InterventionHandler\|NaturalLanguageDiagnosisCoordinator\|DiagnosisResponse" src/main/java/ --include="*.java" | grep -v "src/main/java/com/huawei/hisi/service/intent/"
```
预期：零输出（无外部引用）

- [ ] **Step 2: 删除 intent 包**
删除 `hisi-dev-tool/src/main/java/com/huawei/hisi/service/intent/` 整个目录

- [ ] **Step 3: 删除前端引用（如有）**
检查并删除 `ApiList.vue` 和 `InterventionPanel.vue` 中对已删除后端的引用

- [ ] **Step 4: 编译测试**
```bash
cd "C:\Users\47583\projects\hisi_dev_tool v5.0\hisi-dev-tool" && mvn compile 2>&1 | tail -10
```

- [ ] **Step 5: 全量回归测试**
```bash
cd "C:\Users\47583\projects\hisi_dev_tool v5.0\hisi-dev-tool" && mvn test --batch-mode 2>&1 | tail -20
```
验证：`BUILD SUCCESS` + Tests run: ... Failures: 0

### Task 2.2: 移除 ExceptionPathController

**Files:**
- Potentially Delete: `hisi-dev-tool/src/main/java/com/huawei/hisi/controller/ExceptionPathController.java`

- [ ] **Step 1: 检查前端引用**
```bash
cd "C:\Users\47583\projects\hisi_dev_tool v5.0" && grep -r "exception-path\|ExceptionPath\|exceptionPath" hisi-dev-tool-frontend/src/ --include="*.ts" --include="*.vue"
```

- [ ] **Step 2: 检查后端其他引用**
```bash
cd "C:\Users\47583\projects\hisi_dev_tool v5.0\hisi-dev-tool" && grep -r "ExceptionPathController\|ExceptionPathAnalyzer" src/main/java/ --include="*.java" | grep -v "ExceptionPathController.java"
```

- [ ] **Step 3: 如确认无引用 → 删除 + 编译验证**
```bash
mvn compile 2>&1 | tail -5
```

- [ ] **Step 4: 全量回归测试**
```bash
mvn test --batch-mode 2>&1 | tail -20
```

### Task 2.3: 移除 GitController（如废弃）

**Files:** Potentially Delete `controller/GitController.java`

- [ ] **Step 1: 检查前端引用**
```bash
cd "C:\Users\47583\projects\hisi_dev_tool v5.0" && grep -r "git\|GitController" hisi-dev-tool-frontend/src/api/git.ts
```

- [ ] **Step 2: 检查 api/git.ts 的实际调用方**
```bash
cd "C:\Users\47583\projects\hisi_dev_tool v5.0" && grep -r "from.*api/git\|from.*@/api/git" hisi-dev-tool-frontend/src/ --include="*.ts" --include="*.vue"
```

- [ ] **Step 3: 根据结果决定保留或删除 + 回归测试**

### Task 2.4: 清理废弃 Embedding 服务

**Files:**
- Potentially Delete: `service/IFlytekEmbeddingService.java`
- Potentially Delete: `service/SiliconFlowEmbeddingService.java`
- Potentially Delete: `config/IFlytekConfig.java`
- Potentially Delete: `config/SiliconFlowConfig.java`

- [ ] **Step 1: 检查 IFlytek 引用链**
```bash
cd "C:\Users\47583\projects\hisi_dev_tool v5.0\hisi-dev-tool" && grep -r "IFlytek" src/main/java/com/huawei/hisi/ --include="*.java" | grep -v "IFlytekEmbeddingService.java\|IFlytekConfig.java"
```

- [ ] **Step 2: 检查 SiliconFlow 引用链**
```bash
cd "C:\Users\47583\projects\hisi_dev_tool v5.0\hisi-dev-tool" && grep -r "SiliconFlow" src/main/java/com/huawei/hisi/ --include="*.java" | grep -v "SiliconFlowEmbeddingService.java\|SiliconFlowConfig.java"
```

- [ ] **Step 3: 确认 UnifiedEmbeddingService 是否活跃**
```bash
cd "C:\Users\47583\projects\hisi_dev_tool v5.0\hisi-dev-tool" && grep -r "UnifiedEmbedding" src/main/java/ --include="*.java"
```

- [ ] **Step 4: 如确认废弃 → 删除 + 编译 + 回归测试**

### Task 2.5: 检查 remote project 模块

**Files:** Potentially Delete `project/remote/` 整个包 (6个文件)

- [ ] **Step 1: 检查前端引用**
```bash
cd "C:\Users\47583\projects\hisi_dev_tool v5.0" && grep -r "remote-project\|RemoteProject\|remoteProject" hisi-dev-tool-frontend/src/ --include="*.ts" --include="*.vue"
```

- [ ] **Step 2: 检查后端引用**
```bash
cd "C:\Users\47583\projects\hisi_dev_tool v5.0\hisi-dev-tool" && grep -r "RemoteProject\|remote.project" src/main/java/ --include="*.java" | grep -v "src/main/java/com/huawei/hisi/project/remote/"
```

- [ ] **Step 3: 如确认废弃 → 删除 + 编译 + 回归测试**

### Task 2.6: 检查 project group/namegroup

**Files:** Potentially Delete `project/group/` (3 files) + `project/namegroup/` (3 files)

- [ ] **Step 1: 检查前端引用**
```bash
cd "C:\Users\47583\projects\hisi_dev_tool v5.0" && grep -r "projectGroup\|ProjectGroup\|projectNameGroup\|ProjectNameGroup\|project-group\|project-name-group" hisi-dev-tool-frontend/src/ --include="*.ts" --include="*.vue"
```

- [ ] **Step 2: 检查后端引用**
```bash
cd "C:\Users\47583\projects\hisi_dev_tool v5.0\hisi-dev-tool" && grep -r "ProjectGroup\|ProjectNameGroup" src/main/java/ --include="*.java" | grep -v "src/main/java/com/huawei/hisi/project/group/\|src/main/java/com/huawei/hisi/project/namegroup/"
```

- [ ] **Step 3: 如确认废弃 → 删除 + 编译 + 回归测试**

---

## Phase 3: 前端体验优化

### Task 3.1: 统一侧边栏样式

**Files:**
- Modify: `hisi-dev-tool-frontend/src/components/layout/AppSidebar.vue`

- [ ] **Step 1: 优化配色和过渡动画**
- 菜单项 hover/active 状态微调（transition 加 0.2s ease）
- 确保图标对齐和间距一致

- [ ] **Step 2: 验证前端 build**

### Task 3.2: 验证 API 错误处理统一性

**Files:**
- Read: `hisi-dev-tool-frontend/src/utils/request.ts`（或 api/index.ts 拦截器）

- [ ] **Step 1: 检查错误处理逻辑**
确认 Axios 拦截器已覆盖：401 重定向登录、网络错误提示、500 错误提示

- [ ] **Step 2: 验证前端 build**

---

## Phase 4: 最终验证

### Task 4.1: 全量后端回归测试

- [ ] **Step 1: 运行全量测试**
```bash
cd "C:\Users\47583\projects\hisi_dev_tool v5.0\hisi-dev-tool" && mvn test --batch-mode 2>&1 | tail -20
```
验证：`BUILD SUCCESS`，Failures: 0, Errors: 0

### Task 4.2: 前端生产构建

- [ ] **Step 1: 运行前端构建**
```bash
cd "C:\Users\47583\projects\hisi_dev_tool v5.0\hisi-dev-tool-frontend" && npm run build 2>&1 | tail -15
```
验证：`✓ built in`

### Task 4.3: 提交代码

- [ ] **Step 1: 暂存所有变更**
```bash
cd "C:\Users\47583\projects\hisi_dev_tool v5.0" && git add -A
```

- [ ] **Step 2: 提交**
```bash
git commit -m "refactor: lightweight system cleanup — remove dead code, optimize frontend

Phase 1: Remove Vue scaffold files (HomeView, AboutView), zombie routes
(mcp-guide, call-chain redirects), SemanticSearchView dead code, MCP Guide,
and unused API modules (naturalLanguage).

Phase 2: Remove natural language diagnosis backend (service/intent/),
clean up unused controllers and embedding services.

Phase 3: Optimize sidebar navigation styles.

All regression tests pass. Build verified for both frontend and backend."
```

- [ ] **Step 3: 验证 git status 干净**
```bash
git status
```
