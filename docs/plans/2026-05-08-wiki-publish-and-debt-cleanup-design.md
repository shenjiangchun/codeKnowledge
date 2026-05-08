# Wiki 发布与历史包袱整改 — 设计文档

- **日期**:2026-05-08
- **作者**:Claude（superpowers:brainstorming 引导）
- **状态**:已批准（用户 2026-05-08 同意）
- **关联仓库**:`shenjiangchun/codeKnowlage`

---

## 1. 背景

CodeWiki 设计手册已为三个子项目分别生成完毕：

| 子项目 | 文件数 | 路径 |
|---|---|---|
| hisi-dev-tool（Java 后端） | 19 | `hisi-dev-tool/docs/codewiki/` |
| hisi-dev-tool-frontend（前端） | 17 | `hisi-dev-tool-frontend/docs/codewiki/` |
| hisi-mcp-server（MCP 服务） | 14 | `hisi-mcp-server/docs/codewiki/` |

生成过程中识别到 **3 项历史包袱**，需在本轮一并整改：

1. **MCP-PathUtils 缺失**：`hisi-mcp-server/src/tools/index.ts` 引用 `../utils/pathUtils.js`，但 `src/utils/` 目录不存在；`dist/utils/pathUtils.js` 编译产物存在，源码丢失。清理 dist 后构建会断裂。
2. **前端死路由与错调 API**：`SemanticSearchView.vue` 调用后端不存在的 `/search/semantic`；`/call-chain*` 系列路由全部为 redirect 残留。
3. **后端 README 描述与实际架构脱节**：`hisi-dev-tool/README.md` 仍提及 OpenGauss / MySQL / ChromaDB / hisi-vector-service，实际架构已切换至 Neo4j + SQLite。

## 2. 目标

1. 把三套 CodeWiki 合并发布到 GitHub Wiki（仓库：`codeKnowlage.wiki.git`），单一入口 + 统一侧边栏导航。
2. 通过 3 个独立 PR 在主仓库消除上述 3 项历史包袱。
3. 全过程脚本化、可复现，便于以后 Wiki 内容更新时一键重新同步。

## 3. 非目标（YAGNI）

- ❌ 不引入 GitHub Actions 自动同步 Wiki（本轮过重）
- ❌ 不搭建 mkdocs / vitepress 静态站点
- ❌ 不重写 codewiki 内容（已生成完毕，本轮只是发布）
- ❌ PR-1 不重构 MCP 服务架构，只补回最小化的 `pathUtils.ts`
- ❌ 不修改三个子项目的功能代码（除 PR-2 必要的死路由删除）

## 4. 方案

### 4.1 Wiki 发布机制（脚本驱动）

**采用方案 A：脚本自动转换 + 推送**（候选 B 手工拷贝 / C GitHub Actions 已被排除）。

脚本位置：`scripts/sync-wiki.sh`（兼容 Git Bash / WSL）

脚本流程：

```
1. 克隆 wiki 仓库到临时目录 .wiki-staging/
2. 清空临时目录（保留 .git）
3. 遍历 3 个子项目的 docs/codewiki/，按规则平铺 + 改名 + 加前缀
4. 重写所有 Markdown 内的相对链接（../02-架构设计/index.md → Backend-02-架构设计.md）
5. 生成 Home.md（合并 3 套 README，按角色提供阅读路径）
6. 生成 _Sidebar.md（GitHub Wiki 标准侧边栏）
7. 生成 _Footer.md（版本 / 更新日期 / 源码 commit hash）
8. dry-run 模式仅输出预览，不 push
9. commit + push（含 tag wiki-sync-YYYYMMDD-HHMM 便于回滚）
```

**命名映射示例**：

| 原路径 | Wiki 文件名 |
|---|---|
| `hisi-dev-tool/docs/codewiki/README.md` | `Backend-README.md` |
| `hisi-dev-tool/docs/codewiki/01-项目概览/index.md` | `Backend-01-项目概览.md` |
| `hisi-dev-tool/docs/codewiki/03-模块说明/REST接口层.md` | `Backend-03-REST接口层.md` |
| `hisi-dev-tool-frontend/docs/codewiki/02-架构设计/index.md` | `Frontend-02-架构设计.md` |
| `hisi-mcp-server/docs/codewiki/05-接口文档/index.md` | `MCP-05-接口文档.md` |

### 4.2 三个整改 PR

| PR | 分支 | 目录 | 范围 | 验证 |
|---|---|---|---|---|
| **PR-1** | `chore/mcp-restore-pathutils` | `hisi-mcp-server/` | 从 `dist/utils/pathUtils.js` 反推 → 写入 `src/utils/pathUtils.ts`；删除 dist；重新 `npm run build` 验证；冒烟测试关键 MCP 工具 | `npm run build` 干净通过；`kg_list_projects` / `hybrid_search` / `log_query` 各调用 1 次返回 200 |
| **PR-2** | `chore/frontend-prune-dead-routes` | `hisi-dev-tool-frontend/` | 删除 `views/search/SemanticSearchView.vue`；从 `router/` 删除 `/search/semantic` 和 `/call-chain*` redirect；从导航菜单（layout）摘除入口；删除关联未用的 i18n 文案 | `npm run typecheck` + `npm run test` + `npx playwright test` 全过 |
| **PR-3** | `docs/backend-readme-align` | `hisi-dev-tool/` | 重写 `README.md`：删除 OpenGauss/MySQL/ChromaDB/hisi-vector-service 描述；改为 Neo4j + SQLite + PTY4J + Playwright + ANTLR4；与 CLAUDE.md 现状对齐；新增"架构演进"段落（v3 ChromaDB → v5 Neo4j 迁移记录） | 人工 Review；与 `CLAUDE.md`、`docs/codewiki/02-架构设计/index.md` 双向交叉验证 |

### 4.3 执行顺序

```
1. 写设计文档（本文档） → commit
2. 调用 superpowers:writing-plans 生成实施计划
3. 实施 Wiki 发布脚本 + dry-run 验证 + 推送
4. 串行实施 PR-1 → PR-2 → PR-3（每个 PR 独立分支独立 PR）
```

PR 之间互不依赖，可并行也可串行；保守起见**串行**（避免冲突 + 便于阶段性回滚）。

## 5. 错误处理与回滚

| 风险 | 应对 |
|---|---|
| Wiki 脚本生成的链接断裂 | dry-run 模式预览；自动校验所有 Markdown 链接是否解析到存在的文件，否则报错退出 |
| Wiki push 后内容错误 | 推送前打 tag，回滚执行 `git reset --hard <tag>` + force push |
| PR-1 反推的 `pathUtils.ts` 与原意有差异 | 通过完整冒烟测试 MCP 工具；diff `dist/utils/pathUtils.js` 编译前后一致 |
| PR-2 删除的路由有外部链接引用 | 在 PR 描述中显式列出删除路径；保留 README CHANGELOG 提示 |
| PR-3 README 与代码再次脱节 | 在 README 顶部加注 `Last verified against {commit-hash} on {date}` |

## 6. 测试策略

| 工件 | 测试方式 |
|---|---|
| Wiki 同步脚本 | dry-run 输出对比；链接完整性自动校验；生成预览 sidebar 人工目检 |
| PR-1（MCP） | `npm run build` 必须无 TS error；运行 server，依次调用 3 类工具（kg / hybrid_search / log）确认返回正常 |
| PR-2（前端） | typecheck + Vitest + Playwright e2e 全绿 |
| PR-3（后端 README） | 人工 Review；与 Wiki / CLAUDE.md 三方交叉对齐 |

## 7. 交付物清单

- [x] 设计文档（本文件）
- [ ] 实施计划文档（由 writing-plans 技能生成）
- [ ] `scripts/sync-wiki.sh`
- [ ] GitHub Wiki 已发布（含 Home / Sidebar / Footer）
- [ ] PR-1 已合入 main
- [ ] PR-2 已合入 main
- [ ] PR-3 已合入 main

## 8. 时间预估

| 阶段 | 预估 |
|---|---|
| 写脚本 + dry-run | 30-45 分钟 |
| Wiki 推送 + 验证 | 10 分钟 |
| PR-1（MCP） | 30-45 分钟 |
| PR-2（前端） | 45-60 分钟（含 e2e） |
| PR-3（后端 README） | 30 分钟 |
| **合计** | **2.5-3.5 小时** |
