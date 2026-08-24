# 轻量化系统重构 v2 — SDD 驱动完整定框

> **定框日期**: 2026-07-31 | **路由**: Standard | **风险**: Medium | **分支**: release_0731

---

## 意图

### 目标与成功标准

**目标**: 将 HiSi DevTool v5.0 从 25 路由/286 入口瘦身为轻量化系统，并通过 Clean Light 设计系统重塑前端体验。

**可观察的成功结果**:
1. `npm run build` 零 TypeScript 错误
2. `mvn test` 零新回归（预存 2 个失败修复后全部通过）
3. 删除 ~25+ 废弃文件，净减 >3500 行
4. 前端路由 25→17，侧边栏 12→10 项（分组清晰）
5. CSS 变量驱动设计系统，Element Plus 全局主题覆盖
6. 自定义侧边栏替代 el-menu（消除 Element Plus 样式约束）
7. WCAG AA 对比度 100% 达标

### 边界与非目标

| 在范围内 | 不在范围内 |
|----------|-----------|
| 删死代码（前端+后端） | 修改 Agent 核心逻辑 |
| Clean Light 设计系统 | 升级框架版本（Spring Boot / Vue） |
| 自定义侧边栏重建 | 重构 knowledgegraph 扫描器 |
| Element Plus 全局主题覆盖 | 新增功能/页面 |
| 预存测试修复（2 个） | 数据库 schema 变更 |
| CSS 变量驱动布局/配色 | 修改 Neo4j / KG 生成逻辑 |
| 核心页面样式统一（project、knowledge-graph、ram） | APM 调试 / Claude 终端 / Fix Chat / 技能市场 页面 |

---

## 代码事实

### 现状摘要（已完成 3 个 commit）

```
83003c2a Phase3-clean-light-theme-and-prototypes  ← CSS变量dead+WCAG不达标+侧边栏问题
2d7dc80a Phase2-backend-cleanup                    ← 删除11个后端文件,残留配置/stale注释
464f6e93 Phase1-frontend-cleanup                   ← 删除12个前端文件,types/search.ts遗漏
```

**3 个并行 Audit Agent 审计发现（12 项）**: 详见下方证据表第 13-24 行。

### 可复用 / 需扩展 / 冲突

#### 可直接复用
- 现有 100+ 测试套件（回归基线）
- JWT 认证体系 + ApiResponse 格式
- AppLayout.vue 布局壳（微调）
- 所有 core 功能模块（知识图谱/RAM/Fix/日志/合入分析/项目管理/技能市场）

#### 需要扩展
- `AppSidebar.vue` → 重建为自定义组件（替换 el-menu）
- `global.css` → CSS 变量全部重写，覆盖 Element Plus 主题
- `AppHeader.vue` → 匹配新设计系统
- 核心页面 → 卡片/表格/按钮样式统一

#### 需要新建
- `styles/element-overrides.css` — Element Plus 全局主题覆盖

#### 需求与现状冲突
- 当前 global.css 定义了 9 个 CSS 变量但零使用（全部硬编码 hex）
- AppLayout 背景 `#f8fafb` vs 侧边栏 `#ffffff` → 需统一色调
- 侧边栏用 el-menu → 无法精细控制 is-opened/disabled-hover/focus-visible 状态
- `#9ca3af` 在白色背景对比度 2.54:1（不满足 WCAG AA 4.5:1）

---

## 证据表

| # | 类型 | 结论 | 证据 |
|---|------|------|------|
| 1 | 事实 | HomeView/AboutView 零引用 | Grep: 0 matches outside self |
| 2 | 事实 | SemanticSearchView 确认不可用 | CLAUDE.md 标注"历史残留" |
| 3 | 事实 | naturalLanguage API 零消费者 | Grep: useNaturalLanguage → 仅 store 自身 |
| 4 | 事实 | service/intent/ 无外部引用 | Grep: com.huawei.hisi.service.intent. → 0 matches |
| 5 | 事实 | IFlytek/SiliconFlow embedding 无 @Autowired | Grep: 0 injection points outside self |
| 6 | 事实 | ExceptionPath chain 零消费者 | Grep: ExceptionPathAnalyzer → 0 callers |
| 7 | 决策 | claude-session 保留 | MethodReferenceGraph router.push ClaudeSession |
| 8 | 决策 | call-chain 视图保留（KG 页内嵌） | KnowledgeGraph.vue 导入 ChainChart |
| 9 | 决策 | GitController 保留 | 3 前端文件引用 |
| 10 | 决策 | RemoteProject 保留 | 9 前端文件引用 |
| 11 | 决策 | ProjectGroup/NameGroup 保留 | 7 前端文件引用 |
| 12 | 事实 | Phase 1+2 mvn test 零新回归 | surefire-reports: 仅 2 预存失败 |
| 13 | 审计 | types/search.ts 未删除（孤儿死代码） | 消费者 api/search.ts 已删 → 0 导入 |
| 14 | 审计 | application.yml:132-135 残留配置 | siliconflow/iflytek enabled:false 无对应类 |
| 15 | 审计 | 3 个文件含 stale Javadoc 引用 | UnifiedEmbeddingService/ProxyConfig/EmbeddingModelConfig |
| 16 | 审计 | global.css CSS 变量零使用 | :root 定义 9 变量 → AppSidebar/AppLayout 全硬编码 |
| 17 | 审计 | WCAG AA 对比度失败 | #9ca3af on #fff = 2.54:1 (需 4.5:1) |
| 18 | 审计 | el-sub-menu.is-opened 无样式 | 展开/折叠视觉无区别 |
| 19 | 审计 | :nth-child(5/9/13) 选择器脆弱 | 管理员登录时 splice 插入打破顺序 |
| 20 | 审计 | Disabled 菜单项响应 hover | .is-disabled 未阻止 hover 背景变色 |
| 21 | 审计 | 预存测试失败 (2 类, 4 个用例) | RemediationIntegrationTest+ApiRegressionTest |
| 22 | 决策 | 侧边栏重建为自定义组件 | User: "重建自定义侧边栏" |
| 23 | 决策 | Element Plus 全局主题覆盖 | User: "全局覆盖 Element Plus 主题" |
| 24 | 决策 | 删除 prototypes + 立即清理审计发现 | User: confirmed in grill |

---

## 消歧与闸门

### 开放问题清单（全部已决策）

| # | 问题 | 状态 | 最终决策 |
|---|------|------|---------|
| 1 | Clean Light 主题问题处理 | ✅ 已决策 | 修复 + CSS 变量重构 |
| 2 | 前端体验优化深度 | ✅ 已决策 | 全站重新设计（布局 + 核心页面） |
| 3 | 重设计范围 | ✅ 已决策 | 布局壳 + 核心页面（project/knowledge-graph/ram） |
| 4 | Element Plus 关系 | ✅ 已决策 | 全局 CSS 变量覆盖 Element Plus 主题 |
| 5 | 侧边栏方案 | ✅ 已决策 | 重建自定义侧边栏（替换 el-menu） |
| 6 | 审计发现处理 | ✅ 已决策 | 立即清理全部 12 项 |
| 7 | Prototypes 保留 | ✅ 已决策 | 删除 |
| 8 | 预存测试修复 | ✅ 已决策 | 本次修复 |

### 澄清完整性扫描
- 所有 8 个开放问题已通过 3 轮 AskUserQuestion 全部决策
- 无 TBD/待定项
- 边界清晰：不碰 APM/Fix/ClaudeTerminal/技能市场/Agent 逻辑

### 风险定级与闸门建议

- **风险等级**: Medium
- **命中风险特征**:
  - 跨模块边界：前端布局壳 + 核心页面 + Element Plus 全局覆盖（影响所有页面）
  - 侧边栏重建：完全替换现有导航组件，路由逻辑和权限控制需完整迁移
- **未命中高风险特征**: Auth/Payment/Privacy/Data migration 均不涉及
- **验证策略**: 每步 `npm run build` + `mvn test` + 逐步 agent-browser 冒烟

---

## Explore 交接消费

N/A — 无 explore handoff。本变更为事后定框（重新纠正 SDD 流程）。

## 状态源与工件位置

- 分支: `release_0731`
- OpenSpec 变更: `openspec/changes/lightweight-system-refactoring/`
- 已提交: 3 commits (464f6e93, 2d7dc80a, 83003c2a)
- 待执行: Phase 4 (审计修复) → Phase 5 (设计系统重建)
