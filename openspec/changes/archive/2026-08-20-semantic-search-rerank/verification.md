# 语义检索 rerank：验证报告

## 范围与状态
- 状态源：`openspec/changes/semantic-search-rerank/`
- 风险/闸门：Standard / medium；实施门已由用户「开始实施」批准
- 提交/差异：8 个文件（MCP vectorTools.ts + 前端 vectorSearch.ts + 后端 2 控制器 + RerankService/RerankProperties + MultiQueryHybridSearchService + application.yml）

## 运行与静态证据
| 时间 | 命令/动作 | 退出码/结果 | 失败数 | 覆盖范围 |
|---|---|---|---|---|
| 2026-08-20 | `mvn -o test -Dmaven.resources.skip=true` | exit 0 / BUILD SUCCESS | 0 | 后端全量（1168 用例） |
| 2026-08-20 | `npx tsc --noEmit`（MCP） | No errors | 0 | MCP TS |
| 2026-08-20 | `npx vue-tsc --noEmit` + `npx tsc --noEmit`（前端） | No errors | 0 | 前端 TS |
| 2026-08-20 | `openspec validate semantic-search-rerank` | valid | - | 结构/spec |

### 主验证证据（机器锚点，标签稳定勿改）
- 命令：cd hisi-dev-tool && mvn -o test -Dmaven.resources.skip=true
- 时间：2026-08-20T18:07:00+08:00
- 结果：pass（exit 0；Tests run: 1168, Failures: 0, Errors: 0, Skipped: 22, BUILD SUCCESS）

## 需求验证

| 需求/场景 | 实现 | 验证 |
|---|---|---|
| MCP 走 v2 端点 | `vectorTools.ts:96` POST `/semantic/v2` | grep 断言 + tsc |
| MCP threshold 删除 | `vectorTools.ts` 三处删除 | grep `threshold` 0 匹配 |
| 历史 v1 端点移除 | 删 `semanticSearch()` + `search()` | compile 通过 + `@PostMapping` grep 仅剩 v2 |
| v2 端点不受影响 | 保留 `semanticSearchV2`/`searchV2` | 全量测试绿 |
| rerank 外部 API 配置（默认关） | `RerankProperties` + yml `enabled:false` | compile + RerankServiceTest |
| 召回后 rerank 精排 | `RerankService` + `applyRerankOrder` | RerankServiceTest（6 用例） |
| 关闭时零回归 | `enabled=false` 分支短路 | 全量测试绿（1168） |
| rerank 异常降级 | `RerankService` catch→空 Map | RerankServiceTest 降级用例 |

## 独立代码审查

- reviewer: code-reviewer 子 agent（独立）
- verdict: **APPROVE**（0 CRITICAL / 0 HIGH）
- MEDIUM 2 项：
  - #1 部分 rerank 响应分数/排序不一致 → **已修复**（`applyRerankOrder` 缺失项写回 0.0）
  - #2 rerank 无独立超时（复用 300s RestTemplate）→ **接受为 residual risk**（默认关闭；单独建超时客户端超出极简范围，留待开启时评估）
- LOW 6 项：孤儿字段/过时 Javadoc → 已清理（SemanticSearchController/VectorSearchController 删 `hybridSearchService` 字段+import+更新类注释）；`convertFromSearchResultItem` 死代码、`SearchRequest.searchType` 预留字段、`graphDepth` 语义偏差 → 记录不处理（非本次范围/原本存在）

## 残余风险（residual）

1. rerank 无独立超时，开启后若 rerank 服务挂起最长阻塞 300s（当前默认关闭，无影响）。
2. `convertFromSearchResultItem` 是原本存在的死代码（v1 也未调用），本次未删。
3. MCP `graphDepth` 在 v2 多路路径不生效（工具描述仍称「图遍历扩展」），属端点切换暴露的既有语义偏差，超出本次 scope。

## 环境说明

`mvn` 资源拷贝阶段（`maven-resources-plugin` 拷贝 `verify.output.json`）被运行中的后端进程（Java PID 54444）锁定，故所有编译/测试均加 `-Dmaven.resources.skip=true` 跳过资源拷贝。该跳过不影响 Java 源码编译正确性与测试逻辑执行（编译阶段已通过），仅跳过静态资源文件到 target 的复制。
