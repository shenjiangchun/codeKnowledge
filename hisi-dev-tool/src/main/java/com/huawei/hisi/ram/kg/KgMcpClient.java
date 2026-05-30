package com.huawei.hisi.ram.kg;

import com.huawei.hisi.ram.kg.dto.Bridge;
import com.huawei.hisi.ram.kg.dto.CallTreeNode;
import com.huawei.hisi.ram.kg.dto.Entry;
import com.huawei.hisi.ram.kg.dto.Impl;
import com.huawei.hisi.ram.kg.dto.MethodBodyInfo;
import com.huawei.hisi.ram.kg.dto.Seed;
import com.huawei.hisi.ram.kg.dto.SqlMapping;

import java.util.List;

/**
 * Thin client for the hisi-mcp-server {@code kg_*} tool set.
 *
 * <p>Each method maps 1:1 to a tool exposed by the MCP server. Implementations
 * must translate calls to the server's JSON-RPC style {@code tools/call} endpoint
 * and parse the response into the matching DTO list / tree.</p>
 */
public interface KgMcpClient {

    List<Seed> hybridSearch(String query, String projectPath, int limit);

    /** Multi-project-path overload: searches across all given project directories. */
    default List<Seed> hybridSearch(String query, List<String> projectPaths, int limit) {
        // Default: use first path (backward compat)
        if (projectPaths == null || projectPaths.isEmpty()) return List.of();
        return hybridSearch(query, projectPaths.get(0), limit);
    }

    List<Entry> entryPoints(String projectPath, String entryType);

    List<Impl> implementations(String interfaceName, String projectPath);

    CallTreeNode calleesTree(String className, String methodName, String projectPath, int maxDepth);

    List<Entry> rootEntries(String className, String methodName, String projectPath);

    List<Entry> affecting(String className, String methodName, String projectPath, int maxDepth);

    List<Entry> downstream(String nodeId, String projectPath, int maxDepth);

    List<Bridge> feignChain(String serviceName, String projectPath);

    List<Bridge> mqChain(String topic, String projectPath);

    List<Bridge> bridges(String nodeId, String projectPath);

    List<SqlMapping> mybatisSql(String mapperInterface, String projectPath);

    /** Batch-load method bodies and metadata for AI relevance analysis. */
    List<MethodBodyInfo> loadMethodBodies(List<String> nodeIds, String projectPath);

    /**
     * Given a set of method nodeIds, trace the caller chain upward and return
     * the {@link Entry entry points} (Controller, Scheduled, MQ consumer, etc.)
     * that can reach them — i.e. the root entries, not intermediate callers.
     *
     * @param nodeIds     method nodeIds to trace upward from
     * @param projectPath Neo4j projectPath
     * @param maxDepth    maximum caller traversal depth
     * @return root entry-point nodes reachable via callers of the given nodeIds
     */
    List<Entry> rootEntryAncestors(List<String> nodeIds, String projectPath, int maxDepth);

    /**
     * Resolve LLM-provided path hints (file paths or class names) into actual
     * Neo4j projectPaths. Returns the set of unique projectPaths found.
     *
     * @param pathHints  file paths, class names, or partial paths from clarify LLM
     * @param classNames fully-qualified class names from target_modules
     * @return resolved projectPaths that exist in Neo4j
     */
    default List<String> resolveProjectPaths(List<String> pathHints, List<String> classNames) {
        // Default: return pathHints as-is (backward compat)
        return pathHints;
    }
}
