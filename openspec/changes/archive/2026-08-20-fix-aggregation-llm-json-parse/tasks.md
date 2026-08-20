## 1. 新增鲁棒 JSON 提取工具

- [ ] 1.1 新建 `RobustJsonExtractor`（`com.huawei.hisi.knowledgegraph.aggregation.llm`），实现泛型 `public static <T> T extract(String raw, Class<T> type)`
- [ ] 1.2 移植 `RamClaudeJsonClient` 的 7 策略为独立方法：fence 剥离、平衡括号提取、strict+lenient 分级解析、尾部散文剥离、截断修复
- [ ] 1.3 实现候选评分：按「解析成功 + 目标类型非 null」取首个成功候选，失败返回 null 并记 length + 前 N 字符日志（不打印完整 raw）

## 2. 接入两个调用点

- [ ] 2.1 `MultiDimensionCommunityDetector.callLlm` 改为 `.content()` 拿原始文本 + `RobustJsonExtractor.extract(raw, DomainGrouping.class)`
- [ ] 2.2 `LayerRoleLlmService.resolveRoles` 改为 `.content()` 拿原始文本 + `RobustJsonExtractor.extract(raw, RoleGrouping.class)`
- [ ] 2.3 保持既有降级路径不变：提取返回 null 时领域归纳降级、层级补全记 `批量补全失败`

## 3. 测试与验证

- [ ] 3.1 为 `RobustJsonExtractor` 写单元测试，覆盖：前导散文+JSON、markdown fence、尾部散文、JSON 截断、宽松语法（尾逗号/单引号/注释）
- [ ] 3.2 更新 `MultiDimensionCommunityDetectorTest` / 相关测试，验证 `.content()` + 鲁棒提取路径
- [ ] 3.3 `mvn test` 全绿（含既有 aggregation 测试零回归）
