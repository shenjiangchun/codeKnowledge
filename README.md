# codeKnowlage

> 面向"代码知识"的研发工具一体化方案 —— 用知识图谱、向量检索与日志诊断把存量代码盘清楚、讲明白、用得起来。

本仓库是 **HiSi DevTool** 的 monorepo,聚合了三个相互协作的子项目:后端服务、前端控制台、MCP 服务器。

## 子项目

| 路径 | 角色 | 技术栈 | 主要职责 |
|---|---|---|---|
| [`hisi-dev-tool/`](./hisi-dev-tool) | 后端服务 (v4.4) | Java 17 · Spring Boot 3.2 · Neo4j 5.11+ · SQLite · ANTLR4 · Playwright · 智谱 AI | 项目扫描、知识图谱构建、语义检索、调用链分析、日志诊断、Skill 市场 |
| [`hisi-dev-tool-frontend/`](./hisi-dev-tool-frontend) | 前端控制台 (v4.3) | Vue 3.5 · TypeScript 5 · Element Plus · Pinia · ECharts · Vite | 知识图谱可视化、增强检索面板、Claude 会话/终端、日志查询、项目管理、提示词配置 |
| [`hisi-mcp-server/`](./hisi-mcp-server) | MCP 服务器 | Node.js 18+ · TypeScript · `@modelcontextprotocol/sdk` | 把后端能力暴露给 Claude / Codex 等 AI Agent 作为工具调用 (KG / 混合检索 / 日志分析) |

## 📚 完整设计文档(Wiki)

**[👉 打开 GitHub Wiki](https://github.com/shenjiangchun/codeKnowlage/wiki)**

Wiki 收录了三套子项目的 CodeWiki 设计手册,共 **51 篇**,统一带 `Backend-` / `Frontend-` / `MCP-` 前缀:

- **项目概览 / 架构设计 / 模块说明 / 数据流程**
- **接口文档 / 数据模型 / 部署运维**
- **技术决策 / 术语表**

侧边栏 (`_Sidebar`) 已按"角色 → 章节"分组导航,推荐入口:

- 后端开发者 → [Backend-README](https://github.com/shenjiangchun/codeKnowlage/wiki/Backend-README)
- 前端开发者 → [Frontend-README](https://github.com/shenjiangchun/codeKnowlage/wiki/Frontend-README)
- AI/Agent 集成者 → [MCP-README](https://github.com/shenjiangchun/codeKnowlage/wiki/MCP-README)

## 快速上手

```bash
# 后端
cd hisi-dev-tool
./mvnw spring-boot:run        # 默认端口 8080,需先启动 Neo4j

# 前端
cd hisi-dev-tool-frontend
npm install && npm run dev    # http://localhost:5173,代理至后端 8080

# MCP 服务器(给 Claude / Codex 用)
cd hisi-mcp-server
npm install && npm run build && npm start
```

详细的环境依赖、Neo4j 配置、智谱 AI Key 申请、Playwright 浏览器安装等,请见 Wiki 的[**部署运维**](https://github.com/shenjiangchun/codeKnowlage/wiki/Backend-07-%E9%83%A8%E7%BD%B2%E8%BF%90%E7%BB%B4)章节。

## 目录速览

```
codeKnowlage/
├── hisi-dev-tool/              # 后端 (Spring Boot)
│   └── docs/codewiki/          # 后端 CodeWiki 源 (= Wiki 的 Backend-* 页面)
├── hisi-dev-tool-frontend/     # 前端 (Vue 3)
│   └── docs/codewiki/          # 前端 CodeWiki 源 (= Wiki 的 Frontend-* 页面)
├── hisi-mcp-server/            # MCP 服务器
│   └── docs/codewiki/          # MCP CodeWiki 源 (= Wiki 的 MCP-* 页面)
├── docs/plans/                 # 设计文档 / 实施计划
└── scripts/sync-wiki.sh        # 把三套 codewiki 一键同步到 GitHub Wiki
```

## 同步 Wiki

```bash
bash scripts/sync-wiki.sh --dry-run   # 本地预览,不推送
bash scripts/sync-wiki.sh --push      # 推送至 codeKnowlage.wiki.git,自动打 tag
```

脚本会:克隆 wiki 仓 → 平铺 + 重命名加前缀 → 重写相对链接 → 校验断链 → 生成 `Home` / `_Sidebar` / `_Footer` → commit & push。

## License

仅供学习与内部研发使用,详见各子项目目录下 LICENSE(如有)。
