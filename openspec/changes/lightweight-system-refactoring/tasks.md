# 实施任务 — 轻量化系统重构

## Phase 1: 前端清理 ✅ 已完成

- [x] Task 1.1: 移除 Vue scaffold 残留 → 验证: `npm run build`
- [x] Task 1.2: 清理僵尸路由 → 验证: `npm run build`
- [x] Task 1.3: 移除 SemanticSearchView 死代码 → 验证: `npm run build`
- [x] Task 1.4: 移除 MCP Guide 死代码 → 验证: `npm run build`
- [x] Task 1.5: 清理废弃 API 模块 → 验证: `npm run build`
- [x] Task 1.6: 检查 claude-session → 决策: 保留
- [x] Task 1.7: 优化侧边栏导航 → 验证: `npm run build`

## Phase 2: 后端清理

### Task 2.1: 移除自然语言诊断模块 🔄 进行中
- **文件**: 删除 `service/intent/` 包（9 文件）
- **验证**: `mvn compile` + `mvn test`
- **确认**: 已 Grep 确认零外部引用

### Task 2.2: 移除 ExceptionPathController
- **文件**: 如无前端引用 → 删除 `controller/ExceptionPathController.java`
- **验证**: Grep 前端引用 → `mvn compile` + `mvn test`

### Task 2.3: 移除 GitController
- **文件**: 如 `api/git.ts` 无消费者 → 删除 `controller/GitController.java`
- **验证**: Grep 引用链 → `mvn compile` + `mvn test`

### Task 2.4: 清理废弃 Embedding 服务
- **文件**: 如确认仅 UnifiedEmbeddingService 活跃 → 删除 IFlytek + SiliconFlow 4 个文件
- **验证**: Grep 引用链 → `mvn compile` + `mvn test`

### Task 2.5: 检查 remote project 模块
- **文件**: 如前端 `api/remote-project.ts` 无消费者 → 删除 `project/remote/` 包
- **验证**: Grep 引用链 → `mvn compile` + `mvn test`

### Task 2.6: 检查 project group/namegroup
- **文件**: 如无消费者 → 删除对应包
- **验证**: Grep 引用链 → `mvn compile` + `mvn test`

## Phase 3: 前端体验优化

### Task 3.1: 统一主题样式
- **文件**: `AppSidebar.vue` / `AppLayout.vue` / 全局 styles
- **验证**: `npm run build` + dev server 肉眼检查

### Task 3.2: API 错误处理标准化
- **文件**: `utils/request.ts` 拦截器
- **验证**: `npm run build`

## Phase 4: 最终验证

### Task 4.1: 全量后端回归
- **命令**: `mvn test`
- **预期**: BUILD SUCCESS, 0 failures

### Task 4.2: 前端生产构建
- **命令**: `npm run build`
- **预期**: `✓ built in`

### Task 4.3: 提交
- **Commands**: `git add -A` + `git commit`
