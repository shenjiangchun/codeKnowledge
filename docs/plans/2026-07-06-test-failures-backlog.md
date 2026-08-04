# Pre-existing Test Failures Backlog (2026-07-06)

> **状态**：WAITING — 等用户通知后再处理。本文档只记录问题，不修复。
> **触发上下文**：2026-07-06 RAM Chat V1 cleanup 的全量 `mvn test` 暴露了 17 个失败，调查发现全部与 V1 清理无关，是先前就存在的问题。

## 调查方法

- 逐个跑失败的 test class（Surefire `-Dtest=`），不跑全量
- 复现失败的 case 再跑一次确认不是 flake
- 读测试 + 读生产代码 + 读 Javadoc 比对契约
- 必要时查 `git log` 看相关代码最近改动

## 结论一览

**0 flake** — 17 个失败全部确定可复现，没有任何 timing / ordering / 非确定性因素。

| 类别 | 数量 | 含义 |
|---|---|---|
| **BUG**（生产代码错） | 2 | 生产代码行为不符合测试期望 |
| **TEST**（测试 fixture 过期） | 7 | 测试 fixture / 期望没跟上代码改动 |
| **ENV**（环境相关） | 1 | Windows 路径分隔符 |
| **FLAKE**（flaky） | 0 | — |

总计：10 个 test class / 17 个 test method。

## 推荐处理顺序（不修，仅作参考）

### Tier 1 — Cheap / 仅测试改动 / 零生产风险（5 项）

机械改测试，无设计决策。

| # | 测试 | 类别 | 根因 | 建议修复 |
|---|---|---|---|---|
| 2 | `EntryPointTypeTest.testAllTypesExist` | TEST | enum 加了 `FEIGN_CLIENT` 后总数变 12，测试硬编码 11 | 改 `11` → `12` |
| 3 | `PythonKnowledgeGraphBuilderTest.parseFile_topLevelFunction` | ENV | Windows 路径 `\` 未 normalize，测试比较原始路径 | 改测试用 `PathUtils.normalize(dir.toString())` |
| 6 | `FlaskRouteScannerTest.scanModule_multipleRoutes` | TEST | scanner 正确产出 4 条 (GET + POST `/contact`)，测试期望 3 | 改 `hasSize(3)` → `hasSize(4)` |
| 7 | `KnowledgeGraphCommonUtilsTest.normalizePath_*` (2 tests) | TEST | 测试期望 null 和 `..` 解析，但 `KnowledgeGraphCommonUtils.normalizePath` 委派给 `PathUtils.normalize` 不做这两件事；测试期望与契约不符 | 改测试期望对齐 `PathUtils.normalize` 契约 |
| 9 | `ProjectServiceImplTest.testGetStatus_*` (2 tests) | TEST | 静态字段 `PROJECT_DIR` 没被 Spring 上下文初始化（测试不启 Spring），`Paths.get(null,...)` NPE | 在 `@BeforeEach` 加 `ReflectionTestUtils.setField(DataSourceConfig.class, "PROJECT_DIR", "/tmp")` |

### Tier 2 — 需要设计决策（5 项）

需要决定改生产还是改测试，PR scope 较大。

| # | 测试 | 类别 | 根因 | 设计问题 |
|---|---|---|---|---|
| 1 | `FailureLocatorE2ETest` (3 tests) | TEST | Spring Boot test 上下文启动失败，bean 冲突（`ApmClaudeLlmClient` vs `stubLlmClient` 的 `@Primary` 解析依赖 `hisi.apm.diagnose.llm.api-key` 空值属性） | 需要 debug bean 冲突的根因；可能是 `application-local.yml` 没覆盖属性；或用 `@MockBean`；或生产 bean 在 api-key 为空时不加 `@Primary` |
| 4 | `PythonCallGraphResolverTest.unresolvedCall` | BUG | 生产 resolver 显式 emit unresolved edge（line 227, 241），与设计注释 line 32 "Anything else → emitted as an unresolved edge" 一致；**生产对，测试错** | 改测试：`edges.size()==1`，`callType=="UNRESOLVED"`，`unresolved==true` |
| 5 | `FastApiRouteScannerTest.singleRouterWithPrefix_prependsToRoutes` | TEST | `buildRouterPrefixMap` 从源码行读 `varName`，测试构造 `PyCall` 没附 source content，varName 返回 null，prefix 被丢弃 | 二选一：(a) 测试写 temp `.py` 文件含 `router = APIRouter(prefix="/api/v1")`；(b) scanner 在 varName 未知时 fallback 到 `firstStringArg`（设计决策） |
| 8 | `DagExecutorTest.executor_skipsNodesWhoseInputsHashUnchanged` | BUG | `DagExecutor.run` 对非最后节点要求 HITL_RES 确认（line 140 `isNodeConfirmed` 检查），第二次 `run()` 没 HITL_RES 事件 → 卡在 `WAITING_HITL`，`skippedNodes` 为空 | 二选一：(a) 测试 seed HITL_RES 事件让循环跑完；(b) `DagExecutor` 在 cache CHECKPOINT 重放时跳过 HITL gate（设计决策） |
| 10 | `ReportExportServiceTest` (4 tests) | TEST | `ReportExportService` Markdown 输出不包含 "## 事件历史" section，也不含 USER_MSG / CHECKPOINT / ASSISTANT_DELTA 块（Grep 验证 0 匹配）。测试期望一个被移除或从未 merge 的特性 | 二选一：(a) 删 4 个测试；(b) 加回 "事件历史" section。取决于产品意图 |

### 完整对应表（10 个 test class）

| # | Test class | 失败数 | Tier | Run 1 | Run 2 | 类别 | 根因一句话 |
|---|---|---|---|---|---|---|---|
| 1 | `apm.e2e.FailureLocatorE2ETest` | 3 | T2 | 3/3 fail | n/a | TEST | Spring Boot test 上下文启动失败，bean 冲突 |
| 2 | `knowledgegraph.model.EntryPointTypeTest` | 1 | T1 | 1/16 fail | n/a | TEST | enum 加了 FEIGN_CLIENT 后总数变 12 |
| 3 | `knowledgegraph.python.PythonKnowledgeGraphBuilderTest` | 1 | T1 | 1/10 fail | n/a | ENV | Windows 反斜杠未 normalize |
| 4 | `knowledgegraph.python.call.PythonCallGraphResolverTest` | 1 | T2 | 1/11 fail | Re-run 仍 fail | BUG | 生产对、测试错 |
| 5 | `knowledgegraph.python.scanner.FastApiRouteScannerTest` | 1 | T2 | 1/11 fail | Re-run 仍 fail | TEST | varName 来源缺失 |
| 6 | `knowledgegraph.python.scanner.FlaskRouteScannerTest` | 1 | T1 | 1/7 fail | n/a | TEST | scanner 4 条正确，测试期望 3 |
| 7 | `knowledgegraph.util.KnowledgeGraphCommonUtilsTest` | 2 | T1 | 2/19 fail | Re-run 仍 fail | TEST | 测试期望与 PathUtils.normalize 契约不符 |
| 8 | `ram.orchestrator.DagExecutorTest` | 1 | T2 | 1/3 fail | n/a | BUG | cache-skip 被 HITL gate 卡住 |
| 9 | `service.ProjectServiceImplTest` | 2 | T1 | 2/4 error | n/a | TEST | 静态 PROJECT_DIR 未初始化 |
| 10 | `service.ReportExportServiceTest` | 4 | T2 | 4/10 fail | n/a | TEST | "事件历史" section 不存在 |

## 关联工作

- 触发本调查的 task：2026-07-06 RAM Chat V1 cleanup（`docs/plans/2026-07-06-ram-chat-v1-cleanup-design.md` + `-plan.md`）
- V1 cleanup 自身验证：**目标区域全部 PASS**
  - `RamChat*Test,RamClaude*Test,Claude*Test`：17/17 PASS
  - `RamChatInTurnInjectionIT`：2/2 PASS
  - 工作树状态：Task 1 + Task 2 已落地（`RamClaudeJsonClient.java` M, `RamClaudeJsonClientCancellationTest.java` D），**未提交、未推送**
- V1 cleanup 推进决策：**等用户通知**（不在本 backlog scope 内）
- 后续 follow-up（不在本 backlog scope）：补 V2 版 cancellation test（V1 的 cancellation test 删了，V2 同样有 `disposableSink` 但没单测）—— 见 `docs/plans/2026-07-06-ram-chat-v1-cleanup-design.md` Follow-up 章节

## 行动项（用户决策）

- [ ] **Tier 1（5 项）**：是否修？批量改测试 fixture，零生产风险。
- [ ] **Tier 2（5 项）**：每个需要单独设计决策。是否拆成独立 task？
- [ ] **V1 cleanup 推送**：当前工作树状态完整，commit + push 待通知。
- [ ] **V2 cancellation test follow-up**：是否建独立 backlog？