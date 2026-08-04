package com.huawei.hisi.ram.kg;

import com.huawei.hisi.knowledgegraph.model.BridgeStats;
import com.huawei.hisi.ram.kg.dto.Bridge;
import com.huawei.hisi.ram.kg.dto.CallTreeNode;
import com.huawei.hisi.ram.kg.dto.Entry;
import com.huawei.hisi.ram.kg.dto.Impl;
import com.huawei.hisi.ram.kg.dto.MethodBodyInfo;
import com.huawei.hisi.ram.kg.dto.Seed;
import com.huawei.hisi.ram.kg.dto.SqlMapping;

import java.util.List;

public interface KgMcpClient {

    BridgeStats bridgeStats(String projectPath);

    BridgeStats bridgeStats(List<String> projectPaths);

    List<Seed> hybridSearch(String query, String projectPath, int limit);

    List<Seed> hybridSearch(String query, List<String> projectPaths, int limit);

    List<Entry> entryPoints(String projectPath, String entryType);

    /** Multi-project overload: entry points across all given project paths. */
    List<Entry> entryPoints(List<String> projectPaths, String entryType);

    List<Impl> implementations(String interfaceName, String projectPath);

    /** Multi-project overload: implementations across all given project paths. */
    List<Impl> implementations(String interfaceName, List<String> projectPaths);

    CallTreeNode calleesTree(String className, String methodName, String projectPath, int maxDepth);

    /** Multi-project overload: callees tree resolved against all given project paths. */
    CallTreeNode calleesTree(String className, String methodName, List<String> projectPaths, int maxDepth);

    List<Entry> rootEntries(String className, String methodName, String projectPath);

    /** Multi-project overload: root entries across all given project paths. */
    List<Entry> rootEntries(String className, String methodName, List<String> projectPaths);

    List<Entry> affecting(String className, String methodName, String projectPath, int maxDepth);

    /** Multi-project overload: upstream callers across all given project paths. */
    List<Entry> affecting(String className, String methodName, List<String> projectPaths, int maxDepth);

    List<Entry> downstream(String nodeId, String projectPath, int maxDepth);

    /** Multi-project overload: downstream callees across all given project paths. */
    List<Entry> downstream(String nodeId, List<String> projectPaths, int maxDepth);

    List<Bridge> feignChain(String serviceName, String projectPath);

    /** Multi-project overload: Feign bridges across all given project paths. */
    List<Bridge> feignChain(String serviceName, List<String> projectPaths);

    List<Bridge> mqChain(String topic, String projectPath);

    /** Multi-project overload: MQ bridges across all given project paths. */
    List<Bridge> mqChain(String topic, List<String> projectPaths);

    List<Bridge> bridges(String nodeId, String projectPath);

    /** Multi-project overload: bridges reachable from nodeId across all given project paths. */
    List<Bridge> bridges(String nodeId, List<String> projectPaths);

    List<SqlMapping> mybatisSql(String mapperInterface, String projectPath);

    /** Multi-project overload: MyBatis SQL mappings across all given project paths. */
    List<SqlMapping> mybatisSql(String mapperInterface, List<String> projectPaths);

    List<MethodBodyInfo> loadMethodBodies(List<String> nodeIds, String projectPath);

    /** Multi-project overload: load method bodies, scoping to any of the given project paths. */
    List<MethodBodyInfo> loadMethodBodies(List<String> nodeIds, List<String> projectPaths);

    List<Entry> rootEntryAncestors(List<String> nodeIds, String projectPath, int maxDepth);

    /** Multi-project overload: trace root entry ancestors across all given project paths. */
    List<Entry> rootEntryAncestors(List<String> nodeIds, List<String> projectPaths, int maxDepth);

    /**
     * Resolve LLM-provided path hints (file paths or class names) into actual
     * Neo4j projectPaths. Returns the set of unique projectPaths found.
     */
    default List<String> resolveProjectPaths(List<String> pathHints, List<String> classNames) {
        return pathHints;
    }
}
