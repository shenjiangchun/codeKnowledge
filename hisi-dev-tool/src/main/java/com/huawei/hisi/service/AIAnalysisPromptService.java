package com.huawei.hisi.service;

import com.huawei.hisi.neo4j.model.EntryPointNode;
import com.huawei.hisi.neo4j.model.MethodNode;
import com.huawei.hisi.neo4j.model.SqlNode;
import com.huawei.hisi.neo4j.repository.Neo4jEntryPointNodeRepository;
import com.huawei.hisi.neo4j.repository.Neo4jMethodNodeRepository;
import com.huawei.hisi.neo4j.repository.Neo4jSqlNodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * AI 分析提示词组装服务
 * 从 Neo4j 知识图谱拉取完整数据，组装为结构化的富提示词
 * 供 Claude CLI 终端使用，最大化利用知识图谱能力
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AIAnalysisPromptService {

    private final Neo4jMethodNodeRepository methodNodeRepository;
    private final Neo4jEntryPointNodeRepository entryPointNodeRepository;
    private final Neo4jSqlNodeRepository sqlNodeRepository;

    /**
     * 构建调用链分析提示词
     * 从 Neo4j 拉取完整的调用链图数据（nodes + edges + signatures + descriptions + SQL）
     *
     * @param entryKey    入口标识（如 HTTP URI: GET /api/orders）
     * @param projectPath 项目路径
     * @return 组装好的富提示词
     */
    public String buildCallChainAnalysisPrompt(String entryKey, String projectPath) {
        log.info("[AI Prompt] 构建调用链分析 prompt: entryKey={}, project={}", entryKey, projectPath);

        StringBuilder prompt = new StringBuilder();
        prompt.append("你是一个资深的 Java/Spring 架构师和代码审查专家。请基于以下知识图谱提供的**完整调用链数据**进行深度分析。\n\n");

        // 1. 入口点信息
        Optional<EntryPointNode> entryOpt = entryPointNodeRepository.findByEntryKey(entryKey);
        if (entryOpt.isPresent()) {
            EntryPointNode entry = entryOpt.get();
            prompt.append("## 入口点信息\n");
            prompt.append("- **类型**: ").append(entry.getEntryType()).append("\n");
            prompt.append("- **标识**: ").append(entry.getEntryKey()).append("\n");
            if (entry.getEntryInfo() != null) {
                prompt.append("- **详情**: ").append(entry.getEntryInfo()).append("\n");
            }
            prompt.append("- **项目**: ").append(projectPath).append("\n\n");
        }

        // 2. 调用链图结构
        List<Neo4jMethodNodeRepository.GraphTraversalResult> nodes =
                methodNodeRepository.getCallChainNodesByEntryKey(entryKey, projectPath, 20);
        List<Neo4jMethodNodeRepository.GraphEdgeResult> edges =
                methodNodeRepository.getCallChainEdgesByEntryKey(entryKey, projectPath, 20);

        if (nodes.isEmpty()) {
            prompt.append("## 注意\n未找到该入口的调用链数据，可能需要先生成知识图谱。\n\n");
            return prompt.toString();
        }

        // 收集所有 nodeId 用于后续查询详情
        Set<String> nodeIds = nodes.stream()
                .map(Neo4jMethodNodeRepository.GraphTraversalResult::nodeId)
                .collect(Collectors.toSet());

        // 查询完整的方法节点信息（含 description, methodBody, signature）
        List<MethodNode> fullNodes = methodNodeRepository.findAllByNodeIds(new ArrayList<>(nodeIds));
        Map<String, MethodNode> nodeMap = fullNodes.stream()
                .collect(Collectors.toMap(MethodNode::getNodeId, n -> n, (a, b) -> a));

        prompt.append("## 调用链概览\n");
        prompt.append("- **节点总数**: ").append(nodes.size()).append("\n");
        prompt.append("- **调用关系数**: ").append(edges.size()).append("\n");

        int maxDepth = nodes.stream()
                .map(Neo4jMethodNodeRepository.GraphTraversalResult::depth)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .max().orElse(0);
        prompt.append("- **最大深度**: ").append(maxDepth).append("\n\n");

        // 3. 按层级展示调用链节点
        prompt.append("## 调用链节点详情（按调用深度排列）\n\n");

        // 按 depth 排序分组
        Map<Integer, List<Neo4jMethodNodeRepository.GraphTraversalResult>> byDepth = nodes.stream()
                .collect(Collectors.groupingBy(
                        n -> n.depth() != null ? n.depth() : 0,
                        TreeMap::new,
                        Collectors.toList()));

        for (Map.Entry<Integer, List<Neo4jMethodNodeRepository.GraphTraversalResult>> depthEntry : byDepth.entrySet()) {
            prompt.append("### 第 ").append(depthEntry.getKey()).append(" 层\n\n");

            for (Neo4jMethodNodeRepository.GraphTraversalResult node : depthEntry.getValue()) {
                MethodNode full = nodeMap.get(node.nodeId());
                prompt.append("**").append(node.className()).append(".").append(node.methodName()).append("()**\n");
                if (node.signature() != null) {
                    prompt.append("- 签名: `").append(node.signature()).append("`\n");
                }
                if (node.filePath() != null) {
                    prompt.append("- 文件: ").append(node.filePath());
                    if (node.startLine() != null) {
                        prompt.append(":").append(node.startLine());
                    }
                    prompt.append("\n");
                }
                if (full != null) {
                    if (full.getDescription() != null && !full.getDescription().isEmpty()) {
                        prompt.append("- 描述: ").append(full.getDescription()).append("\n");
                    }
                    if (full.getMethodBody() != null && !full.getMethodBody().isEmpty()) {
                        // 限制方法体长度，防止 prompt 过大
                        String body = full.getMethodBody();
                        if (body.length() > 800) {
                            body = body.substring(0, 800) + "\n  // ... (truncated)";
                        }
                        prompt.append("- 代码:\n```java\n").append(body).append("\n```\n");
                    }
                }
                prompt.append("\n");
            }
        }

        // 4. 调用关系（边）
        prompt.append("## 调用关系\n\n");
        prompt.append("| 调用方 | → | 被调用方 | 调用类型 |\n");
        prompt.append("|--------|---|----------|----------|\n");

        // 构建 nodeId -> 简短标识的映射
        Map<String, String> nodeLabels = new HashMap<>();
        for (Neo4jMethodNodeRepository.GraphTraversalResult node : nodes) {
            nodeLabels.put(node.nodeId(), node.className() + "." + node.methodName());
        }

        for (Neo4jMethodNodeRepository.GraphEdgeResult edge : edges) {
            String source = nodeLabels.getOrDefault(edge.sourceId(), edge.sourceId());
            String target = nodeLabels.getOrDefault(edge.targetId(), edge.targetId());
            String callType = edge.callType() != null ? edge.callType() : "DIRECT";
            prompt.append("| ").append(source).append(" | → | ").append(target).append(" | ").append(callType).append(" |\n");
        }
        prompt.append("\n");

        // 5. SQL 操作信息
        appendSqlInfo(prompt, projectPath, nodeIds);

        // 6. 分析要求
        prompt.append("## 请分析以下方面\n\n");
        prompt.append("1. **业务流程梳理**: 这个入口点的完整业务逻辑是什么？用简洁的文字描述数据流转过程。\n");
        prompt.append("2. **关键路径识别**: 哪些方法是核心业务逻辑？哪些是辅助功能？\n");
        prompt.append("3. **潜在风险点**:\n");
        prompt.append("   - 异常处理是否完善？\n");
        prompt.append("   - 是否有事务一致性问题？\n");
        prompt.append("   - 是否存在性能瓶颈（如 N+1 查询、无缓存的热点方法）？\n");
        prompt.append("   - 是否有安全隐患（SQL 注入、权限检查缺失）？\n");
        prompt.append("4. **架构改进建议**: 调用链是否过深？是否有循环依赖？是否需要解耦？\n");
        prompt.append("5. **测试建议**: 需要编写哪些单元测试和集成测试来保障这条调用链的可靠性？\n\n");
        prompt.append("请用中文回答，重点关注**实际代码层面**的问题和建议，不要给出泛泛的通用建议。\n");

        log.info("[AI Prompt] 调用链分析 prompt 生成完成: {} 个节点, {} 条边, {} 字符",
                nodes.size(), edges.size(), prompt.length());

        return prompt.toString();
    }

    /**
     * 构建影响分析提示词
     * 从 Neo4j 拉取上下游调用关系、受影响入口点等数据
     */
    public String buildImpactAnalysisPrompt(String className, String methodName, String projectPath) {
        log.info("[AI Prompt] 构建影响分析 prompt: {}.{}, project={}", className, methodName, projectPath);

        StringBuilder prompt = new StringBuilder();
        prompt.append("你是一个资深的代码影响分析专家。请基于以下知识图谱提供的**上下游调用关系数据**进行精确的影响范围评估。\n\n");

        // 1. 目标方法信息
        List<MethodNode> targetNodes = methodNodeRepository.findByClassName(className);
        MethodNode targetNode = targetNodes.stream()
                .filter(n -> n.getMethodName().equals(methodName))
                .filter(n -> n.getProjectPath() != null && n.getProjectPath().equals(projectPath))
                .findFirst()
                .orElse(targetNodes.stream()
                        .filter(n -> n.getMethodName().equals(methodName))
                        .findFirst()
                        .orElse(null));

        prompt.append("## 变更目标\n");
        prompt.append("- **类**: ").append(className).append("\n");
        prompt.append("- **方法**: ").append(methodName).append("\n");
        prompt.append("- **项目**: ").append(projectPath).append("\n");

        if (targetNode != null) {
            if (targetNode.getSignature() != null) {
                prompt.append("- **签名**: `").append(targetNode.getSignature()).append("`\n");
            }
            if (targetNode.getDescription() != null) {
                prompt.append("- **描述**: ").append(targetNode.getDescription()).append("\n");
            }
            if (targetNode.getFilePath() != null) {
                prompt.append("- **文件**: ").append(targetNode.getFilePath()).append("\n");
            }
            if (targetNode.getMethodBody() != null) {
                String body = targetNode.getMethodBody();
                if (body.length() > 1000) {
                    body = body.substring(0, 1000) + "\n// ... (truncated)";
                }
                prompt.append("- **代码**:\n```java\n").append(body).append("\n```\n");
            }
            prompt.append("\n");

            // 2. 上游调用者（影响这个方法的人）
            List<MethodNode> callers = methodNodeRepository.findCallersUpToDepth(targetNode.getNodeId(), 5);
            if (!callers.isEmpty()) {
                prompt.append("## 上游调用者（修改此方法会影响以下调用方）\n\n");
                prompt.append("| # | 类名 | 方法名 | 签名 | 文件 |\n");
                prompt.append("|---|------|--------|------|------|\n");
                int idx = 1;
                for (MethodNode caller : callers) {
                    prompt.append("| ").append(idx++).append(" | ")
                            .append(caller.getClassName()).append(" | ")
                            .append(caller.getMethodName()).append(" | ")
                            .append(caller.getSignature() != null ? "`" + truncate(caller.getSignature(), 60) + "`" : "-").append(" | ")
                            .append(caller.getFilePath() != null ? caller.getFilePath() : "-").append(" |\n");
                }
                prompt.append("\n");
            }

            // 3. 下游被调用者（这个方法依赖的方法）
            List<Neo4jMethodNodeRepository.CalleeWithRelation> callees =
                    methodNodeRepository.findCalleesWithRelation(targetNode.getNodeId());
            if (callees != null && !callees.isEmpty()) {
                prompt.append("## 下游依赖（此方法调用的方法）\n\n");
                prompt.append("| # | 类名 | 方法名 | 调用类型 |\n");
                prompt.append("|---|------|--------|----------|\n");
                int idx = 1;
                for (Neo4jMethodNodeRepository.CalleeWithRelation callee : callees) {
                    prompt.append("| ").append(idx++).append(" | ")
                            .append(callee.calleeClassName()).append(" | ")
                            .append(callee.calleeMethodName()).append(" | ")
                            .append(callee.callType() != null ? callee.callType() : "DIRECT").append(" |\n");
                }
                prompt.append("\n");
            }

            // 4. 受影响的入口点
            List<Neo4jMethodNodeRepository.EntryPointInfo> affectedEntries =
                    methodNodeRepository.findEntryPointsCallingMethod(targetNode.getNodeId(), projectPath);
            if (affectedEntries != null && !affectedEntries.isEmpty()) {
                prompt.append("## 受影响的入口点（API/定时任务/MQ 监听器）\n\n");
                for (Neo4jMethodNodeRepository.EntryPointInfo ep : affectedEntries) {
                    prompt.append("- **[").append(ep.entryType()).append("]** ").append(ep.entryKey()).append("\n");
                }
                prompt.append("\n");
            }
        } else {
            prompt.append("\n> 注意：未在知识图谱中找到该方法的节点，以下分析将基于有限信息。\n\n");
        }

        // 5. 分析要求
        prompt.append("## 请分析以下方面\n\n");
        prompt.append("1. **直接影响**: 上游调用方需要做哪些适配？\n");
        prompt.append("2. **间接影响**: 受影响入口点涉及哪些业务场景？\n");
        prompt.append("3. **风险评估**: 修改风险等级（LOW/MEDIUM/HIGH/CRITICAL），理由是什么？\n");
        prompt.append("4. **测试策略**: 需要覆盖哪些测试用例？重点回归哪些接口？\n");
        prompt.append("5. **安全检查**: 修改是否涉及权限、数据校验、事务边界？\n");
        prompt.append("6. **部署建议**: 是否需要灰度发布？是否需要数据迁移？\n\n");
        prompt.append("请用中文回答，给出具体的文件和方法级别的建议，不要泛泛而谈。\n");

        return prompt.toString();
    }

    /**
     * 构建日志分析提示词
     * 从堆栈信息中提取类/方法名，关联知识图谱中的代码上下文
     */
    public String buildLogAnalysisPrompt(String errorMessage, String errorType,
                                         String stackTrace, String projectPath) {
        log.info("[AI Prompt] 构建日志分析 prompt: errorType={}, project={}", errorType, projectPath);

        StringBuilder prompt = new StringBuilder();
        prompt.append("你是一个资深的 Java 问题排查专家。请基于以下错误信息和知识图谱中的**代码上下文**进行根因分析。\n\n");

        // 1. 错误信息
        prompt.append("## 错误信息\n\n");
        if (errorType != null && !errorType.isEmpty()) {
            prompt.append("- **异常类型**: ").append(errorType).append("\n");
        }
        if (errorMessage != null && !errorMessage.isEmpty()) {
            prompt.append("- **错误消息**: ").append(errorMessage).append("\n");
        }
        prompt.append("\n");

        if (stackTrace != null && !stackTrace.isEmpty()) {
            prompt.append("## 调用栈\n```\n").append(stackTrace).append("\n```\n\n");
        }

        // 2. 从堆栈中提取类/方法名并查询知识图谱
        if (stackTrace != null && projectPath != null) {
            Set<String> classesInStack = extractClassesFromStackTrace(stackTrace);
            if (!classesInStack.isEmpty()) {
                prompt.append("## 知识图谱中的相关代码上下文\n\n");

                int foundCount = 0;
                for (String cls : classesInStack) {
                    if (foundCount >= 5) break; // 限制最多5个类的详情

                    List<MethodNode> methods = methodNodeRepository.findByClassName(cls);
                    methods = methods.stream()
                            .filter(m -> m.getProjectPath() != null && m.getProjectPath().equals(projectPath))
                            .toList();

                    if (!methods.isEmpty()) {
                        foundCount++;
                        prompt.append("### ").append(cls).append("\n\n");
                        for (MethodNode m : methods) {
                            prompt.append("**").append(m.getMethodName()).append("()**\n");
                            if (m.getSignature() != null) {
                                prompt.append("- 签名: `").append(m.getSignature()).append("`\n");
                            }
                            if (m.getDescription() != null) {
                                prompt.append("- 描述: ").append(m.getDescription()).append("\n");
                            }
                            if (m.getMethodBody() != null) {
                                String body = m.getMethodBody();
                                if (body.length() > 600) {
                                    body = body.substring(0, 600) + "\n// ... (truncated)";
                                }
                                prompt.append("- 代码:\n```java\n").append(body).append("\n```\n");
                            }
                            prompt.append("\n");
                        }

                        // 查找该类方法的上游入口点
                        for (MethodNode m : methods) {
                            List<Neo4jMethodNodeRepository.EntryPointInfo> entries =
                                    methodNodeRepository.findEntryPointsCallingMethod(m.getNodeId(), projectPath);
                            if (entries != null && !entries.isEmpty()) {
                                prompt.append("**关联入口点**: ");
                                prompt.append(entries.stream()
                                        .map(e -> "[" + e.entryType() + "] " + e.entryKey())
                                        .collect(Collectors.joining(", ")));
                                prompt.append("\n\n");
                            }
                        }
                    }
                }

                if (foundCount == 0) {
                    prompt.append("> 堆栈中的类在知识图谱中未找到对应节点。\n\n");
                }
            }
        }

        // 3. 分析要求
        prompt.append("## 请分析以下方面\n\n");
        prompt.append("1. **根本原因**: 错误的根本原因是什么？不要只描述表面现象。\n");
        prompt.append("2. **错误传播路径**: 异常是如何从源头传播到表面的？\n");
        prompt.append("3. **受影响代码**: 列出需要修改的具体文件和方法。\n");
        prompt.append("4. **修复方案**: 给出具体的代码修改建议（优先级排序）。\n");
        prompt.append("5. **预防措施**: 如何防止类似问题再次发生？\n\n");
        prompt.append("请用中文回答，重点基于上面的代码上下文给出**精确到代码行**的建议。\n");

        return prompt.toString();
    }

    /**
     * 构建方法级分析提示词（简单场景，前端可直接使用）
     */
    public String buildMethodAnalysisPrompt(String nodeId, String projectPath) {
        Optional<MethodNode> nodeOpt = methodNodeRepository.findByNodeId(nodeId);
        if (nodeOpt.isEmpty()) {
            return "未找到方法节点: " + nodeId;
        }

        MethodNode node = nodeOpt.get();
        StringBuilder prompt = new StringBuilder();
        prompt.append("请分析以下方法的代码质量和潜在问题：\n\n");
        prompt.append("**类**: ").append(node.getClassName()).append("\n");
        prompt.append("**方法**: ").append(node.getMethodName()).append("\n");
        if (node.getSignature() != null) {
            prompt.append("**签名**: `").append(node.getSignature()).append("`\n");
        }
        if (node.getDescription() != null) {
            prompt.append("**描述**: ").append(node.getDescription()).append("\n");
        }
        if (node.getMethodBody() != null) {
            prompt.append("\n```java\n").append(node.getMethodBody()).append("\n```\n");
        }

        // 查找上下游
        List<Neo4jMethodNodeRepository.CalleeWithRelation> callees =
                methodNodeRepository.findCalleesWithRelation(nodeId);
        if (callees != null && !callees.isEmpty()) {
            prompt.append("\n**该方法调用了以下方法**:\n");
            for (Neo4jMethodNodeRepository.CalleeWithRelation callee : callees) {
                prompt.append("- ").append(callee.calleeClassName()).append(".")
                        .append(callee.calleeMethodName()).append("()\n");
            }
        }

        prompt.append("\n请分析代码质量、潜在 bug、安全风险和优化建议。\n");
        return prompt.toString();
    }

    // ==================== Helper Methods ====================

    private void appendSqlInfo(StringBuilder prompt, String projectPath, Set<String> nodeIds) {
        try {
            List<SqlNode> sqlNodes = sqlNodeRepository.findByProjectPath(projectPath);
            if (sqlNodes != null && !sqlNodes.isEmpty()) {
                // 找出与调用链节点相关的 SQL
                List<SqlNode> relatedSql = sqlNodes.stream()
                        .filter(sql -> sql.getMapperInterface() != null)
                        .limit(10)
                        .toList();

                if (!relatedSql.isEmpty()) {
                    prompt.append("## 相关 SQL 操作\n\n");
                    for (SqlNode sql : relatedSql) {
                        prompt.append("**").append(sql.getMapperInterface()).append(".")
                                .append(sql.getMethodName()).append("** (")
                                .append(sql.getStatementType()).append(")\n");
                        if (sql.getSqlStatement() != null) {
                            String sqlText = sql.getSqlStatement();
                            if (sqlText.length() > 300) {
                                sqlText = sqlText.substring(0, 300) + " ...";
                            }
                            prompt.append("```sql\n").append(sqlText).append("\n```\n");
                        }
                        prompt.append("\n");
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[AI Prompt] 查询 SQL 节点失败: {}", e.getMessage());
        }
    }

    private Set<String> extractClassesFromStackTrace(String stackTrace) {
        Set<String> classes = new LinkedHashSet<>();
        if (stackTrace == null) return classes;

        String[] lines = stackTrace.split("\n");
        for (String line : lines) {
            line = line.trim();
            // 匹配 Java 堆栈格式: at com.example.ClassName.methodName(FileName.java:123)
            if (line.startsWith("at ")) {
                String fqn = line.substring(3);
                int parenIdx = fqn.indexOf('(');
                if (parenIdx > 0) {
                    fqn = fqn.substring(0, parenIdx);
                }
                // 去掉方法名，保留类名
                int lastDot = fqn.lastIndexOf('.');
                if (lastDot > 0) {
                    String className = fqn.substring(0, lastDot);
                    // 只保留项目相关的类（过滤 java.*, org.springframework.* 等）
                    if (!className.startsWith("java.") && !className.startsWith("javax.")
                            && !className.startsWith("sun.") && !className.startsWith("jdk.")
                            && !className.startsWith("org.springframework.")
                            && !className.startsWith("org.apache.")
                            && !className.startsWith("com.sun.")) {
                        // 使用简单类名
                        int lastDotCls = className.lastIndexOf('.');
                        String simpleName = lastDotCls > 0 ? className.substring(lastDotCls + 1) : className;
                        classes.add(simpleName);
                    }
                }
            }
        }
        return classes;
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }
}
