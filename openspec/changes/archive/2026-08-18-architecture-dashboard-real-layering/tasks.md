# 任务：架构仪表盘切换到真实分层

## 1. 前端 DashboardPanel 切换数据源

- [x] 1.1 循环依赖卡片改用 `getBuildModuleCycles`（构建模块级环路径），移除旧的领域级 `getDashboard.risks` cyclic 判定
- [x] 1.2 分层违规卡片改用 `getBuildModuleLayerViolations`（module 级职责违规），移除旧的包级 layered 判定
- [x] 1.3 下钻图改用 `getBuildModules`（构建模块依赖图），移除旧的领域级下钻
- [x] 1.4 KPI 卡片切换：构建模块数 / 构建环数 / 分层违规数（替代旧的领域数/循环依赖/分层违规）

## 2. LLM 领域降级为参考

- [x] 2.1 新增「LLM 领域参考」卡片，读 `getDashboard` 的 domains，展示领域名 + 方法数，标注"仅供参考，不作坏味道判定依据"
- [x] 2.2 参考卡片用弱化样式（灰色/plain tag），与坏味道卡片视觉区分

## 3. 后端 getDashboard 语义调整

- [x] 3.1 确认 `getDashboard` 仍返回 domains（供参考卡片），`risks` 字段不再被前端用作坏味道判定依据（前端已切换，后端可保持或标注废弃）

## 4. 验证

- [x] 4.1 前端 `vue-tsc -b` 无新增类型错误
- [ ] 4.2 实测：多模块项目下架构仪表盘展示构建模块环 + module 分层违规 + LLM 领域参考；单模块项目下展示空态提示
