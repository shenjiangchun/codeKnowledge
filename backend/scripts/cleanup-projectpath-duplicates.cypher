// =============================================================================
// 一次性清理脚本：规范化 / 去重 Neo4j 中的 projectPath
// =============================================================================
//
// 背景：历史上 KnowledgeGraphBuilder 入口未对 projectPath 做规范化，导致同一份
// 代码以不同路径形态（反斜杠 vs 正斜杠、含/不含父目录前缀）被多次生成，残留
// 多套重复节点。
//
// ⚠️ 执行前务必：
//   1. 备份 Neo4j（neo4j-admin database dump 或快照磁盘文件）
//   2. 停止后端服务，避免并发写入冲突
//   3. 在 Neo4j Browser 一段一段执行，每段确认结果再继续
//   4. 替换下方占位符为你环境里实际的项目路径
//
// 使用步骤：
//   Phase 1：诊断 —— 看清楚有哪些 projectPath 形态、各占多少节点
//   Phase 2：选择性删除 —— 删除明确作废的旧路径（无 v4.0 前缀的）
//   Phase 3：规范化 —— 反斜杠转正斜杠（合并同一项目的不同分隔符版本）
//   Phase 4：去重重新生成 —— 通过后端重新生成确保结构一致
// =============================================================================


// -----------------------------------------------------------------------------
// Phase 1 — 诊断：看清楚现状
// -----------------------------------------------------------------------------

// 1.1 列出所有节点上出现过的 projectPath，按节点数倒序，看哪些是重复的
MATCH (n) WHERE n.projectPath IS NOT NULL
RETURN n.projectPath AS path,
       count(n) AS nodeCount,
       collect(DISTINCT labels(n)[0]) AS nodeTypes
ORDER BY nodeCount DESC;

// 1.2 找出含反斜杠的脏节点（应该全部规范化或删除）
MATCH (n) WHERE n.projectPath CONTAINS '\\'
RETURN labels(n)[0] AS label, count(n) AS cnt
ORDER BY cnt DESC;

// 1.3 找出明显作废的历史路径（按你环境改条件，例如不含 'v4.0' 的旧路径）
MATCH (n) WHERE n.projectPath = 'C:/Users/47583/projects/hisi-dev-tool'
   OR n.projectPath = 'C:\\Users\\47583\\projects\\hisi-dev-tool'
RETURN labels(n)[0] AS label, count(n) AS cnt;


// -----------------------------------------------------------------------------
// Phase 2 — 删除明确作废的旧路径节点
// -----------------------------------------------------------------------------
// ⚠️ 执行前请先用 Phase 1.3 的查询确认这些路径确实作废，不再使用！
// DETACH DELETE 会同时删除节点和它所有的关系。

MATCH (n)
WHERE n.projectPath = 'C:/Users/47583/projects/hisi-dev-tool'
   OR n.projectPath = 'C:\\Users\\47583\\projects\\hisi-dev-tool'
DETACH DELETE n;


// -----------------------------------------------------------------------------
// Phase 3 — 规范化分隔符：反斜杠版本就地改写为正斜杠
// -----------------------------------------------------------------------------
// 假设：执行 Phase 2 后，剩下的反斜杠节点都和某个正斜杠节点对应同一项目。
// 直接 SET 字段，让它们合并到「等同」的正斜杠路径下。
//
// ⚠️ 警告：如果同一项目同时存在反斜杠版和正斜杠版的节点，规范化后两套节点
// 会共享同一 projectPath 但仍然是两组不同的节点（因为 nodeId 不同）。这种
// 情况下应当：
//   (a) 先彻底 DETACH DELETE 反斜杠版本的节点
//   (b) 然后通过后端用规范化的正斜杠路径重新生成一次
// 这样最干净。下面提供两种做法，**只能二选一**：

// 做法 A（推荐）：直接删除反斜杠版本节点，然后让后端重新生成正斜杠版本
MATCH (n) WHERE n.projectPath CONTAINS '\\'
DETACH DELETE n;

// 做法 B（不推荐，仅在确认正斜杠版本不存在时使用）：原地改字段
// MATCH (n) WHERE n.projectPath CONTAINS '\\'
// SET n.projectPath = replace(n.projectPath, '\\', '/');


// -----------------------------------------------------------------------------
// Phase 4 — 验证 & 重新生成
// -----------------------------------------------------------------------------

// 4.1 再次执行 Phase 1.1 确认现在每个项目只剩一个规范化的 projectPath

// 4.2 如果做法 A 删掉了反斜杠节点，去前端「项目管理 → 选中项目 → 重新生成 KG」
//     生成的节点会带上新的、规范化的 projectPath。

// 4.3 同步清理 SQLite 里的 generation_task 历史脏数据（可选，反斜杠版的任务记录）
//     在 ~/.hisi-devtool/devtool.db 里执行 SQL：
//     DELETE FROM generation_task WHERE project_path LIKE '%\\%' ESCAPE '\';
