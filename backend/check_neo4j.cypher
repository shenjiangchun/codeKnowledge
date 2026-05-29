// 检查所有不同的 projectPath 值
MATCH (m:Method)
RETURN DISTINCT m.projectPath as projectPath, count(m) as count
ORDER BY count DESC
LIMIT 10;
