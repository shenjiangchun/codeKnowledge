package com.huawei.hisi.ram.kg.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.huawei.hisi.ram.kg.KgMcpClient;
import com.huawei.hisi.ram.kg.dto.Bridge;
import com.huawei.hisi.ram.kg.dto.CallTreeNode;
import com.huawei.hisi.ram.kg.dto.Entry;
import com.huawei.hisi.ram.kg.dto.Impl;
import com.huawei.hisi.ram.kg.dto.Seed;
import com.huawei.hisi.ram.kg.dto.SqlMapping;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * HTTP-backed implementation of {@link KgMcpClient}.
 *
 * <p>POSTs to {@code {baseUrl}/tools/call} with a JSON body of the form:
 * <pre>{@code
 *   { "name": "kg_hybrid_search", "arguments": { "query": "...", "projectPath": "...", "limit": 15 } }
 * }</pre>
 * Each response is decoded into the corresponding DTO list/tree.</p>
 */
@Primary
@Component
public class HttpKgMcpClient implements KgMcpClient {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final String baseUrl;
    private final OkHttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public HttpKgMcpClient(@Value("${ram.kg.base-url:http://localhost:8765/mcp}") String baseUrl) {
        this(baseUrl, defaultClient());
    }

    /** Constructor for tests — inject MockWebServer URL + custom client. */
    public HttpKgMcpClient(String baseUrl, OkHttpClient http) {
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.http = http;
    }

    private static OkHttpClient defaultClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(30))
                .build();
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    @Override
    public List<Seed> hybridSearch(String query, String projectPath, int limit) {
        JsonNode root = call("kg_hybrid_search", Map.of(
                "query", query,
                "projectPath", projectPath,
                "limit", limit));
        return mapList(arrayField(root, "results"), n -> new Seed(
                text(n, "nodeId"),
                n.path("score").asDouble(0.0),
                text(n, "summary")));
    }

    @Override
    public List<Entry> entryPoints(String projectPath, String entryType) {
        JsonNode root = call("kg_entry_points", Map.of(
                "projectPath", projectPath,
                "entryType", entryType == null ? "ALL" : entryType));
        return mapList(arrayField(root, "entries"), HttpKgMcpClient::toEntry);
    }

    @Override
    public List<Impl> implementations(String interfaceName, String projectPath) {
        JsonNode root = call("kg_implementations", Map.of(
                "interfaceName", interfaceName,
                "projectPath", projectPath));
        return mapList(arrayField(root, "implementations"), n -> new Impl(
                text(n, "nodeId"),
                text(n, "className"),
                text(n, "interfaceName")));
    }

    @Override
    public CallTreeNode calleesTree(String className, String methodName, String projectPath, int maxDepth) {
        JsonNode root = call("kg_callees_tree", Map.of(
                "className", className,
                "methodName", methodName,
                "projectPath", projectPath,
                "maxDepth", maxDepth));
        JsonNode tree = root.has("tree") ? root.get("tree") : root;
        return toCallTree(tree, 0);
    }

    @Override
    public List<Entry> rootEntries(String className, String methodName, String projectPath) {
        JsonNode root = call("kg_root_entries", Map.of(
                "className", className,
                "methodName", methodName,
                "projectPath", projectPath));
        return mapList(arrayField(root, "rootEntries"), HttpKgMcpClient::toEntry);
    }

    @Override
    public List<Entry> affecting(String className, String methodName, String projectPath) {
        JsonNode root = call("kg_affecting", Map.of(
                "className", className,
                "methodName", methodName,
                "projectPath", projectPath));
        return mapList(arrayField(root, "callers"), HttpKgMcpClient::toEntry);
    }

    @Override
    public List<Entry> downstream(String nodeId, String projectPath, int maxDepth) {
        JsonNode root = call("kg_downstream", Map.of(
                "nodeId", nodeId,
                "projectPath", projectPath,
                "maxDepth", maxDepth));
        return mapList(arrayField(root, "nodes"), HttpKgMcpClient::toEntry);
    }

    @Override
    public List<Bridge> feignChain(String serviceName, String projectPath) {
        JsonNode root = call("kg_feign_chain", Map.of(
                "serviceName", serviceName,
                "projectPath", projectPath));
        return mapList(arrayField(root, "bridges"), HttpKgMcpClient::toBridge);
    }

    @Override
    public List<Bridge> mqChain(String topic, String projectPath) {
        JsonNode root = call("kg_mq_chain", Map.of(
                "topic", topic,
                "projectPath", projectPath));
        return mapList(arrayField(root, "bridges"), HttpKgMcpClient::toBridge);
    }

    @Override
    public List<Bridge> bridges(String nodeId, String projectPath) {
        JsonNode root = call("kg_bridges", Map.of(
                "nodeId", nodeId,
                "projectPath", projectPath));
        return mapList(arrayField(root, "bridges"), HttpKgMcpClient::toBridge);
    }

    @Override
    public List<SqlMapping> mybatisSql(String mapperInterface, String projectPath) {
        ObjectNode args = mapper.createObjectNode().put("projectPath", projectPath);
        if (mapperInterface != null) {
            args.put("mapperInterface", mapperInterface);
        }
        JsonNode root = callRaw("kg_mybatis_sql", args);
        return mapList(arrayField(root, "mappings"), n -> {
            List<String> fields = new ArrayList<>();
            JsonNode fieldNode = n.path("tableFields");
            if (fieldNode.isArray()) {
                fieldNode.forEach(f -> fields.add(f.asText()));
            }
            return new SqlMapping(text(n, "mapperInterface"), text(n, "statementType"), fields);
        });
    }

    // ---------- helpers ----------

    private JsonNode call(String toolName, Map<String, Object> arguments) {
        ObjectNode args = mapper.valueToTree(arguments);
        return callRaw(toolName, args);
    }

    private JsonNode callRaw(String toolName, ObjectNode args) {
        ObjectNode body = mapper.createObjectNode();
        body.put("name", toolName);
        body.set("arguments", args);
        Request req = new Request.Builder()
                .url(baseUrl + "/tools/call")
                .post(RequestBody.create(body.toString(), JSON))
                .build();
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                throw new IllegalStateException(
                        "MCP call " + toolName + " failed: HTTP " + resp.code());
            }
            ResponseBody rb = resp.body();
            String text = rb == null ? "{}" : rb.string();
            return mapper.readTree(text);
        } catch (IOException e) {
            throw new UncheckedIOException("MCP call " + toolName + " failed", e);
        }
    }

    private static JsonNode arrayField(JsonNode root, String preferred) {
        if (root == null || root.isMissingNode() || root.isNull()) {
            return null;
        }
        if (root.isArray()) {
            return root;
        }
        JsonNode n = root.get(preferred);
        if (n != null && n.isArray()) {
            return n;
        }
        // Fallback to first array field if shape differs
        Iterator<Map.Entry<String, JsonNode>> it = root.fields();
        while (it.hasNext()) {
            JsonNode v = it.next().getValue();
            if (v.isArray()) {
                return v;
            }
        }
        return null;
    }

    private static <T> List<T> mapList(JsonNode arr, java.util.function.Function<JsonNode, T> fn) {
        List<T> out = new ArrayList<>();
        if (arr == null || !arr.isArray()) {
            return out;
        }
        for (JsonNode n : arr) {
            out.add(fn.apply(n));
        }
        return out;
    }

    private static Entry toEntry(JsonNode n) {
        return new Entry(
                text(n, "nodeId"),
                text(n, "className"),
                text(n, "methodName"),
                text(n, "type"));
    }

    private static Bridge toBridge(JsonNode n) {
        return new Bridge(
                text(n, "nodeId"),
                text(n, "bridgeType"),
                text(n, "target"));
    }

    private CallTreeNode toCallTree(JsonNode n, int depth) {
        if (n == null || n.isMissingNode() || n.isNull()) {
            return new CallTreeNode(null, null, null, depth, List.of());
        }
        int nodeDepth = n.has("depth") ? n.get("depth").asInt(depth) : depth;
        List<CallTreeNode> kids = new ArrayList<>();
        JsonNode children = n.path("children");
        if (children.isArray()) {
            for (JsonNode c : children) {
                kids.add(toCallTree(c, nodeDepth + 1));
            }
        }
        return new CallTreeNode(
                text(n, "nodeId"),
                text(n, "className"),
                text(n, "methodName"),
                nodeDepth,
                kids);
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }
}
