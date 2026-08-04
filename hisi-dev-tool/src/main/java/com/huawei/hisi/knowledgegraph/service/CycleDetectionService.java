package com.huawei.hisi.knowledgegraph.service;

import com.huawei.hisi.knowledgegraph.model.CallCycleInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 环检测服务
 * 实现基于 DFS 的环检测算法
 *
 * 核心算法：使用 DFS + recursionStack 检测环
 * - 当发现节点已在 recursionStack 中时，提取环路径
 * - 环路径从该节点开始，到当前路径结束，再加回该节点形成闭环
 */
@Service
@Slf4j
public class CycleDetectionService {

    private static final String CYCLE_ID_PREFIX = "cycle-";

    /**
     * 检测调用图中的所有环
     *
     * 使用 DFS + recursionStack 算法检测环：
     * 1. 维护 recursionStack 记录当前 DFS 路径上的所有节点
     * 2. 当访问一个节点时，检查它是否已在 recursionStack 中
     * 3. 如果在，说明发现环，提取环路径
     *
     * @param entryNodeId 入口节点ID
     * @param callerIndex 调用关系索引：callerId -> List<calleeId>
     * @return 检测到的所有环信息列表
     */
    public List<CallCycleInfo> detectCycles(String entryNodeId, Map<String, List<String>> callerIndex) {
        log.debug("开始检测环，入口节点: {}", entryNodeId);

        List<CallCycleInfo> cycles = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Set<String> recursionStack = new LinkedHashSet<>(); // 使用 LinkedHashSet 保持插入顺序
        List<String> currentPath = new ArrayList<>();
        int[] cycleCounter = {0}; // 使用数组来在递归中修改计数器

        // 从入口节点开始 DFS
        dfs(entryNodeId, callerIndex, visited, recursionStack, currentPath, cycles, cycleCounter);

        log.debug("环检测完成，发现 {} 个环", cycles.size());
        return cycles;
    }

    /**
     * DFS 遍历检测环
     *
     * @param nodeId 当前节点ID
     * @param callerIndex 调用关系索引
     * @param visited 已访问节点集合（全局）
     * @param recursionStack 当前递归栈（当前路径上的节点）
     * @param currentPath 当前路径
     * @param cycles 检测到的环列表
     * @param cycleCounter 环计数器
     */
    private void dfs(String nodeId,
                     Map<String, List<String>> callerIndex,
                     Set<String> visited,
                     Set<String> recursionStack,
                     List<String> currentPath,
                     List<CallCycleInfo> cycles,
                     int[] cycleCounter) {

        // 将当前节点加入递归栈和路径
        recursionStack.add(nodeId);
        currentPath.add(nodeId);

        // 获取当前节点的所有调用
        List<String> callees = callerIndex.getOrDefault(nodeId, Collections.emptyList());

        for (String calleeId : callees) {
            if (recursionStack.contains(calleeId)) {
                // 发现环！calleeId 已在当前递归栈中
                CallCycleInfo cycleInfo = extractCycle(currentPath, calleeId, cycleCounter);
                if (cycleInfo != null) {
                    cycles.add(cycleInfo);
                    log.debug("发现环: {}", cycleInfo.getCyclePath());
                }
            } else if (!visited.contains(calleeId)) {
                // 继续深度遍历
                dfs(calleeId, callerIndex, visited, recursionStack, currentPath, cycles, cycleCounter);
            }
        }

        // 回溯：从递归栈和路径中移除当前节点
        recursionStack.remove(nodeId);
        currentPath.remove(currentPath.size() - 1);

        // 标记为已访问（全局）
        visited.add(nodeId);
    }

    /**
     * 从当前路径中提取环
     *
     * @param currentPath 当前路径
     * @param cycleStartNodeId 环起始节点ID（即环结束节点）
     * @param cycleCounter 环计数器
     * @return 环信息，如果无法提取则返回 null
     */
    private CallCycleInfo extractCycle(List<String> currentPath,
                                       String cycleStartNodeId,
                                       int[] cycleCounter) {
        // 从路径中找到环起始节点的位置
        int startIndex = -1;
        for (int i = 0; i < currentPath.size(); i++) {
            if (currentPath.get(i).equals(cycleStartNodeId)) {
                startIndex = i;
                break;
            }
        }

        if (startIndex == -1) {
            return null;
        }

        // 提取环路径：从 cycleStartNodeId 到当前路径末尾，再加回 cycleStartNodeId
        List<String> cyclePath = new ArrayList<>(currentPath.subList(startIndex, currentPath.size()));
        cyclePath.add(cycleStartNodeId); // 添加闭环节点

        cycleCounter[0]++;
        String cycleId = CYCLE_ID_PREFIX + cycleCounter[0];

        return CallCycleInfo.builder()
            .cycleId(cycleId)
            .cyclePath(cyclePath)
            .startNodeId(cycleStartNodeId)
            .cycleLength(cyclePath.size())
            .build();
    }

    /**
     * 获取所有在环中的节点
     *
     * @param cycles 环信息列表
     * @return 所有在环中的节点ID集合（去重）
     */
    public Set<String> getNodesInCycles(List<CallCycleInfo> cycles) {
        if (cycles == null || cycles.isEmpty()) {
            return Collections.emptySet();
        }

        return cycles.stream()
            .flatMap(cycle -> cycle.getCyclePath().stream())
            .collect(Collectors.toSet());
    }

    /**
     * 判断边是否是环边
     *
     * 环边定义：边的起点和终点在环路径中相邻出现
     *
     * @param sourceId 边的起点节点ID
     * @param targetId 边的终点节点ID
     * @param cycles 环信息列表
     * @return 如果是环边返回 true，否则返回 false
     */
    public boolean isCycleEdge(String sourceId, String targetId, List<CallCycleInfo> cycles) {
        if (cycles == null || cycles.isEmpty()) {
            return false;
        }

        for (CallCycleInfo cycle : cycles) {
            List<String> path = cycle.getCyclePath();
            if (path == null || path.size() < 2) {
                continue;
            }

            // 检查边是否在环路径中相邻出现
            // 注意：环路径首尾相同，所以需要检查所有相邻对
            for (int i = 0; i < path.size() - 1; i++) {
                if (path.get(i).equals(sourceId) && path.get(i + 1).equals(targetId)) {
                    return true;
                }
            }
        }

        return false;
    }
}
