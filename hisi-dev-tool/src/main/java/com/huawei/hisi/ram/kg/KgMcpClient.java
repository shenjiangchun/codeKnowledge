package com.huawei.hisi.ram.kg;

import com.huawei.hisi.ram.kg.dto.Bridge;
import com.huawei.hisi.ram.kg.dto.CallTreeNode;
import com.huawei.hisi.ram.kg.dto.Entry;
import com.huawei.hisi.ram.kg.dto.Impl;
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

    List<Entry> entryPoints(String projectPath, String entryType);

    List<Impl> implementations(String interfaceName, String projectPath);

    CallTreeNode calleesTree(String className, String methodName, String projectPath, int maxDepth);

    List<Entry> rootEntries(String className, String methodName, String projectPath);

    List<Entry> affecting(String className, String methodName, String projectPath);

    List<Entry> downstream(String nodeId, String projectPath, int maxDepth);

    List<Bridge> feignChain(String serviceName, String projectPath);

    List<Bridge> mqChain(String topic, String projectPath);

    List<Bridge> bridges(String nodeId, String projectPath);

    List<SqlMapping> mybatisSql(String mapperInterface, String projectPath);
}
