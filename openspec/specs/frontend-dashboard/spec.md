# frontend-dashboard Specification

## Purpose
TBD - created by archiving change multi-perspective-platform. Update Purpose after archive.
## Requirements
### Requirement: 首页 5 秒回答"架构有没有问题"
系统 MUST在用户打开仪表盘首页后，5 秒内展示架构健康摘要 + Top 3 风险项。

#### Scenario: 首次打开已有 KG 数据的仪表盘
- 前提：项目已完成构建 + 聚合
- 当：用户导航到 /knowledge-graph（默认 Tab 为仪表盘）
- 则：页面顶部展示一句话状态：如"架构评分 78/100 ↗ +3 本月"
- 并且：4 个 KPI 卡片（严重违规数、耦合评分、热点模块数、趋势方向）
- 并且："立即关注"列表展示 Top 3 风险（每条含具体类名 + 问题描述 + 动作按钮）
- 并且：所有数据在页面加载后 2 秒内渲染完成（不含网络请求时间）

#### Scenario: 失败处理
- 当：项目未构建 KG
- 则：展示空状态引导页："尚未构建知识图谱" + [选择项目] + [开始生成] 按钮 + 预计耗时说明

### Requirement: DSM 矩阵展示模块依赖
系统 MUST以热力图展示 N×N 模块依赖矩阵，高亮循环依赖和分层违规。

#### Scenario: 打开 DSM 矩阵切面
- 前提：项目有 12 个模块
- 当：用户点击 [DSM] Tab
- 则：ECharts heatmap 渲染 12×12 矩阵
- 并且：单元格颜色强度 = 依赖数量（白=0，深蓝=高）
- 并且：循环依赖的对称单元格用红色边框标记
- 并且：分层违规单元格（controller→mapper）用红色背景标记
- 并且：点击单元格展示"谁依赖了谁"的具体方法列表

### Requirement: 热点 Treemap 展示文件级风险
系统 MUST以 Treemap 展示文件级热点，面积 = 文件代码行数，颜色 = 风险分（红=高/绿=低）。

#### Scenario: 打开热点分析切面
- 前提：项目有 500 个文件，Top 热点为 OrderService.java（riskScore=0.92）
- 当：用户点击 [热点] Tab
- 则：ECharts treemap 渲染文件热力图，OrderService 方块最大且最红
- 并且：点击方块展示详情面板（复杂度/变更次数/入度/出度/风险分解）
- 并且：右上角可切换视图：Treemap / 散点图（复杂度 vs 变更频率）/ 列表

### Requirement: 领域边界视图展示检测结果
系统 MUST以力导向图展示自动检测的领域边界，每个领域用不同颜色分组。

#### Scenario: 打开领域切面
- 前提：Louvain 检测到 5 个领域
- 当：用户点击 [领域] Tab
- 则：G6 v5 force layout 渲染领域图，5 个领域用 5 种颜色区分
- 并且：组间连线粗细 = 跨域调用强度
- 并且：置信度标记（>0.8 用实线边框，<0.8 用虚线边框）
- 并且：点击领域展开内部类的详细列表
- 并且：左上角显示 LLM 命名的领域名（"订单域""支付域"）

### Requirement: 生成中心面板上下文感知
系统 MUST根据用户当前选中的上下文（方法 vs 模块）自动切换建议内容。

#### Scenario: 选中方法后打开生成中心
- 前提：用户在调用链切面选中了 OrderService.placeOrder
- 当：用户点击 [生成中心] Tab
- 则：面板展示爆炸半径概要 + LLM 生成的测试建议列表（每条含场景描述 + 优先级）
- 并且：[复制为 prompt] 和 [导出] 按钮可用

