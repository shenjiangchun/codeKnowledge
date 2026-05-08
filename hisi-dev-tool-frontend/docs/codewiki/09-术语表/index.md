# 术语表

> 项目内反复出现的专有名词与缩写。

| 术语 | 英文 | 释义 |
|------|------|------|
| **PROJECT_DIR** | Project Root Directory | 后端扫描的根目录,所有 KG/向量/调用链均基于此目录下的子项目;前端通过 `configApi` 读写 |
| **Selected Projects** | — | 用户在"项目管理"页勾选作为分析目标的项目集合;`useAppStore.selectedProjects` |
| **availableMenus** | — | 计算属性:决定菜单项是否可点;部分菜单(知识图谱/语义检索/日志分析)需 `projectDirConfigured && projectSelected` |
| **KG / Knowledge Graph** | Knowledge Graph | 后端用 Neo4j 构建的"方法节点 + 调用关系 + 桥接关系"图谱 |
| **Bridge** | Bridge Relation | 跨方法的特殊调用关系;`BridgeType` 含 `MAPPER`/`JPA`/`MQ`/`FEIGN`/`HTTP`/`ASPECT`/`DIRECT` |
| **MyBatis SQL Bridge** | — | Mapper 接口方法 ↔ XML 中 `<select/insert/update/delete>` 的关联 |
| **Feign Chain** | — | 跨服务 Feign 调用链 |
| **MQ Chain** | — | 消息生产者 ↔ 消费者的链路 |
| **Vector Generation** | — | 调用智谱 Embedding 把方法描述向量化的过程 |
| **Vector Search** | — | 自然语言 query → 向量召回 + 图遍历扩展 |
| **graphDepth** | — | `vectorSearchApi.search` 中,从向量命中出发额外向上下游遍历的深度 |
| **Intent** | — | 自然语言对话识别出的用户意图;`IntentType` 6 种 |
| **Agent(诊断)** | — | 多 Agent 诊断中的角色:`STACK_TRACE` / `CODE_CONTEXT` / `GIT_HISTORY` / `CONSENSUS` |
| **Workspace Session** | Claude Workspace Session | 终端的一段工作上下文,可绑定 `claudeSessionId`;`useWorkspaceStore` |
| **Claude Session Code** | — | 后端结束 Claude 会话后返回的恢复凭证;`/claude/end-session` 返回,`/claude/resume-session` 恢复 |
| **Skill** | — | 可安装到项目 `.claude/skills/` 的 AI 能力包;有 5 个 `SkillCategory` |
| **MCP** | Model Context Protocol | Claude 的工具协议;前端通过 `mcpApi.install` 走 SSE 安装 |
| **Prompt 模板** | — | 含 `#{var}` 变量的 Markdown 文本,可渲染为最终提示词 |
| **Scene** | — | Claude 通用对话场景:`log-analysis`/`code-analysis`/`trace-analysis`/`impact-analysis`/`free-chat` |
| **ApiResponse** | — | 后端统一响应包装:`{code, message, data}`;`code === 200` 才是成功 |
| **BusinessError** | — | 拦截器封装:`code !== 200` 时 reject 的错误类型 |
| **ValidationError** | — | 由 `parseValidationErrors` 从 400 响应中解析出的字段级校验错误 |
| **SSE** | Server-Sent Events | 服务端推送协议,前端用 `EventSource`(GET)或 `fetch + ReadableStream`(POST) |
| **`/api` 代理** | — | Vite dev 与 Nginx prod 都把 `/api` 转发到后端 `:8080` |
| **`/ws` 代理** | — | 同上,WebSocket 协议(`/ws/terminal`、`/ws/dialog`、`/ws/diagnosis`) |
| **C4 Model** | — | 架构图分级模型(Context / Container / Component / Code);本 Wiki 02 章绘制 L1+L2 |
| **ADR** | Architecture Decision Record | 架构决策记录;本 Wiki 08 章 9 条 |
| **历史残留** | Legacy | `views/search/SemanticSearchView.vue` + `api/search.ts`(`/api/search/semantic` 后端无路由),待清理 |
| **公共知识图谱** | Public KG | 后端规划中的全公司共享 KG(`/public/scan`、`/public/generate`);前端按钮待补 |
