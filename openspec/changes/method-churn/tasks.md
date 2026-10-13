## 1. 数据模型

- [ ] 1.1 `MethodNode` 实体新增 `@Property("churnCount90d") Integer churnCount90d` 字段（含 Javadoc：null=未计算/不可计算，与 `ChurnNode.commitCount90d` 对齐）

## 2. 方法级 churn 计算器

- [ ] 2.1 新增 `MethodChurnCollector`（复用 `ChurnCollector` 的仓库打开 / `toRepoRelativePath` 归一逻辑），实现 JGit diff 区间重叠判定：单方法 `[startLine, endLine]` 与近 90 天提交链 diff `Edit` 区间重叠即计 1 次
- [ ] 2.2 实现历史行号漂移正向滚动 + 内容锚点（签名/首行）兜底；无法锚定则降级为文件级近似（读 `ChurnNode.commitCount90d`）或 null
- [ ] 2.3 缺失 `startLine`/`endLine` 或非 Git 项目 → 保持 null，跳过不报错（规格 Scenario 1.3）

## 3. 落库

- [ ] 3.1 用 `neo4jDriver` + `UNWIND $batch` 批量 `SET m.churnCount90d`，仅更新该字段不触碰 `riskScore`/`communityId` 等聚合字段（规格 Requirement 3）

## 4. 查询端点

- [ ] 4.1 新增 `GET /api/v2/knowledge-graph/method-churn` 端点（只读 GET，受 `AdminOnlyInterceptor` 放行），参数 `projectPaths` + 可选 `limit`，返回逐方法 `nodeId/className/methodName/filePath/startLine/endLine/churnCount90d` 降序
- [ ] 4.2 未落库时返回空列表（`success=true`），不报错

## 5. 测试

- [ ] 5.1 `MethodChurnCollectorTest`：行号区间重叠判定、漂移滚动、缺失行号降级
- [ ] 5.2 端点集成测试：落库后查询降序、未落库返回空
- [ ] 5.3 回归：`mvn -pl hisi-dev-tool test` 全绿（确认不改动文件级 Churn/Hotspot Stage 既有测试）