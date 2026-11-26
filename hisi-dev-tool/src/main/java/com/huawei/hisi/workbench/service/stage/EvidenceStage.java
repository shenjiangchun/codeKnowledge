package com.huawei.hisi.workbench.service.stage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.knowledgegraph.controller.KnowledgeGraphController;
import com.huawei.hisi.model.ApiResponse;
import com.huawei.hisi.ram.model.AgentEvent;
import com.huawei.hisi.ram.model.EventType;
import com.huawei.hisi.ram.repository.AgentEventRepository;
import com.huawei.hisi.workbench.model.WorkbenchItem;
import com.huawei.hisi.workbench.service.StageContext;
import com.huawei.hisi.workbench.service.exec.ArchEvidenceResolver;
import com.huawei.hisi.workbench.service.exec.HttpMappingScanner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
/**
 * 阶段 1 · 代码实证：结合图谱把待办钉到具体代码上，产出带引用的证据包。
 *
 * <p>位置信息能解析出「类#方法」时查方法节点、上游调用方与入口点，再读本地源码取方法片段和
 * 该类的 HTTP 接口映射；查不到图谱时保留待办自带的证据，并明确标注置信度，不让流程在证据不足时装懂。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EvidenceStage implements StageHandler {

    private final KnowledgeGraphController knowledgeGraphController;
    private final AgentEventRepository eventRepository;
    private final ObjectMapper objectMapper;

    @Override
    public String key() {
        return "evidence";
    }

    @Override
    @SuppressWarnings("unchecked")
    public StageOutcome execute(StageContext ctx) {
        WorkbenchItem item = ctx.item();
        ctx.log("开始代码实证：" + item.getLocation());
        List<String> projectPaths = List.of(item.getProjectPath());

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("location", item.getLocation());
        List<Map<String, Object>> anchors = new ArrayList<>();
        String graphStatus = "not_found";

        String className = null;
        String methodName = null;
        if (item.getLocation() != null && item.getLocation().contains("#")) {
            String[] parts = item.getLocation().split("#", 2);
            className = resolveClassName(item.getProjectPath(), parts[0].trim(), item.getEvidenceJson());
            methodName = parts[1].replaceAll(":\\d+$", "").trim();
        }

        if (className == null && methodName == null && WorkbenchItem.SOURCE_ARCH_AI.equals(item.getSource())) {
            // 架构类待办没有「类#方法」位置：证据来自产生它的那次深度分析
            collectArchEvidence(ctx, item, anchors);
        } else if (className != null && methodName != null) {
            graphStatus = collectGraphEvidence(ctx, className, methodName, projectPaths, anchors);
        }

        // 本地源码：目标方法的源码片段 + 该类的真实 HTTP 接口映射（Spec 里的 api 用例以此为准，不靠模型猜路径）
        if (className != null) {
            String source = readSource(item.getProjectPath(), className);
            if (source != null) {
                List<String> mappings = HttpMappingScanner.scanFile(source);
                output.put("httpMappings", mappings);
                for (String mapping : mappings) {
                    addAnchor(anchors, "http", "HTTP 入口：" + mapping);
                }
                if (methodName != null) {
                    String snippet = methodSnippet(source, methodName);
                    if (snippet != null) {
                        addAnchor(anchors, "source", className + "#" + methodName + " 的源码：\n" + snippet);
                    }
                }
                // 堆栈给了行号时，把那一行前后单独拿出来：方法很长时，按签名截取的片段覆盖不到抛出点
                int line = locationLine(item.getLocation());
                String around = line > 0 ? aroundLine(source, line, 12, 8) : null;
                if (around != null) {
                    addAnchor(anchors, "source", className + " 第 " + line + " 行附近（堆栈给出的抛出点，竖线左侧是文件里的真实行号）：\n" + around);
                }
            } else {
                ctx.log("未在本地找到 " + className + " 的源码文件");
            }
        }
        // 架构类待办没有单一入口类：给出项目全部接口映射（截断），供 Spec 挑选回归用的 api 用例
        if (className == null && WorkbenchItem.SOURCE_ARCH_AI.equals(item.getSource())) {
            List<String> all = HttpMappingScanner.scan(Path.of(item.getProjectPath()));
            if (!all.isEmpty()) {
                output.put("httpMappings", new ArrayList<>(all.subList(0, Math.min(all.size(), 40))));
            }
        }

        // 待办自带的证据同样入包（报告里的堆栈帧、检测来源等）
        for (String line : parseList(item.getEvidenceJson())) {
            addAnchor(anchors, "item", line);
        }
        if (anchors.isEmpty()) {
            addAnchor(anchors, "item", "待办未携带证据，位置：" + item.getLocation());
        }
        // 锚点编号最后统一分配：保证唯一且连续（报告自带的证据行里还有它自己的 [E1] 标签，不要混用）
        for (int i = 0; i < anchors.size(); i++) {
            Map<String, Object> numbered = new LinkedHashMap<>();
            numbered.put("id", "E" + (i + 1));
            numbered.putAll(anchors.get(i));
            anchors.set(i, numbered);
        }

        output.put("className", className);
        output.put("methodName", methodName);
        output.put("graphStatus", graphStatus);
        output.put("anchors", anchors);
        output.put("confidence", confidenceOf(graphStatus, anchors.size()));

        ctx.publish("evidence", output);
        ctx.log("证据包完成：锚点 " + anchors.size() + " 条，图谱 " + graphStatus);
        return StageOutcome.done(output);
    }

    private static void addAnchor(List<Map<String, Object>> anchors, String source, String text) {
        anchors.add(Map.of("source", source, "text", text));
    }

    private static final String ARCH_EVIDENCE_PREFIX = "架构证据：";

    /**
     * 架构类待办的实证：回到产生它的那次深度分析，取三样东西——
     * 问题条目本身、证据引用在证据包里对应的具体条目（类、方法、文件路径、包依赖边）、治理路线图里对应的动作。
     */
    private void collectArchEvidence(StageContext ctx, WorkbenchItem item, List<Map<String, Object>> anchors) {
        Map<String, Object> sourceRef = parseMap(item.getSourceRefJson());
        long sessionId = asLong(sourceRef == null ? null : sourceRef.get("sessionId"));
        if (sessionId <= 0) {
            ctx.log("待办没有记录来源分析会话，无法回查架构证据包（在工作台点一次「立即聚合」可补全）");
            return;
        }
        String ref = sourceRef.get("ref") == null ? "" : String.valueOf(sourceRef.get("ref"));
        Map<String, Object> pack = null;
        Map<String, Object> result = null;
        for (AgentEvent event : eventRepository.findBySessionId(sessionId)) {
            if (event.getType() != EventType.CHECKPOINT || event.getPayload() == null) {
                continue;
            }
            Map<String, Object> payload = parseMap(event.getPayload());
            Map<String, Object> output = payload == null ? null : asMap(payload.get("output"));
            String node = payload == null ? "" : String.valueOf(payload.get("nodeName"));
            if ("arch_evidence_collect".equals(node) && output != null) {
                pack = asMap(output.get("evidencePack"));
            } else if ("arch_react_agent".equals(node) && output != null) {
                result = asMap(output.get("rootCauseResult"));
            }
        }
        if (pack != null) {
            ctx.log("已取回架构证据包（会话 " + sessionId + "）：健康度 " + pack.get("healthScore") + " 分 / " + pack.get("grade") + " 级");
        } else {
            ctx.log("会话 " + sessionId + " 里没有找到架构证据包，只能使用待办自带的证据行");
        }

        // 1. 问题条目与治理路线图
        List<Map<String, Object>> issues = result == null ? List.of() : asMapList(result.get("issues"));
        Map<String, Object> issue = null;
        for (Map<String, Object> candidate : issues) {
            if (ref.equals(String.valueOf(candidate.get("ref")))) {
                issue = candidate;
            }
        }
        if (issue != null) {
            addAnchor(anchors, "arch_issue", "架构深度分析的问题条目：\n" + limit(toJson(issue), 1600));
        }
        Map<String, Object> report = result == null ? null : asMap(result.get("report"));
        Map<String, Object> roadmap = report == null ? null : asMap(report.get("roadmap"));
        String level = "P0".equals(item.getSeverity()) ? "p0" : "P1".equals(item.getSeverity()) ? "p1" : "p2";
        if (roadmap != null && roadmap.get(level) instanceof List<?> actions && !actions.isEmpty()) {
            addAnchor(anchors, "arch_roadmap", "治理路线图（" + item.getSeverity() + "）：\n"
                    + limit(toJson(actions), 1400));
        }

        // 2. 证据引用 → 证据包里的具体条目
        int resolved = 0;
        for (String line : parseList(item.getEvidenceJson())) {
            if (!line.startsWith(ARCH_EVIDENCE_PREFIX)) {
                continue;
            }
            String evidenceRef = line.substring(ARCH_EVIDENCE_PREFIX.length()).trim();
            List<ArchEvidenceResolver.Hit> hits = ArchEvidenceResolver.resolve(pack, evidenceRef, 4, 2000);
            if (hits.isEmpty()) {
                continue;
            }
            resolved++;
            StringBuilder text = new StringBuilder("证据「").append(evidenceRef).append("」在证据包里的对应条目：\n");
            for (ArchEvidenceResolver.Hit hit : hits) {
                text.append("  ").append(hit.where()).append(" = ").append(hit.json()).append('\n');
            }
            addAnchor(anchors, "arch_evidence", limit(text.toString(), 2400));
        }
        ctx.log("架构证据引用已解析 " + resolved + " 条");
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private Map<String, Object> parseMap(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return null;
        }
    }

    private static long asLong(Object value) {
        try {
            return value == null ? 0 : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> casted = (Map<String, Object>) map;
            return casted;
        }
        return null;
    }

    private static List<Map<String, Object>> asMapList(Object value) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                Map<String, Object> map = asMap(item);
                if (map != null) {
                    out.add(map);
                }
            }
        }
        return out;
    }

    private static String limit(String text, int max) {
        return text == null ? "" : (text.length() <= max ? text : text.substring(0, max) + "…（截断）");
    }

    private static final Pattern LINE_SUFFIX = Pattern.compile(":(\\d+)$");

    /** 位置里的行号：{@code UserController#getUser:20} → 20；没有行号返回 0。 */
    static int locationLine(String location) {
        if (location == null) {
            return 0;
        }
        Matcher line = LINE_SUFFIX.matcher(location.trim());
        try {
            return line.find() ? Integer.parseInt(line.group(1)) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 文件第 line 行前后的源码，带文件里的真实行号；行号超出文件返回 null。 */
    static String aroundLine(String source, int line, int before, int after) {
        String[] lines = source.replace("\r\n", "\n").split("\n", -1);
        if (line < 1 || line > lines.length) {
            return null;
        }
        int from = Math.max(1, line - before);
        int to = Math.min(lines.length, line + after);
        int width = Math.max(3, String.valueOf(lines.length).length());
        StringBuilder out = new StringBuilder();
        for (int i = from; i <= to; i++) {
            out.append(String.format("%" + width + "d | %s\n", i, lines[i - 1]));
        }
        return out.toString();
    }

    private static final Pattern STACK_FRAME = Pattern.compile(
            "(?<![\\w.])((?:[a-z_][\\w]*\\.)+[A-Z]\\w*)\\.[A-Za-z_]\\w*\\(");

    /**
     * 待办里的位置常常只有简单类名（{@code UserController#getUser:20}），而图谱检索和源码读取都要全限定名：
     * 先在项目源码里按文件名找，找不到再从待办自带的证据（堆栈帧）里取。
     */
    static String resolveClassName(String projectRoot, String className, String evidenceJson) {
        if (className == null || className.contains(".")) {
            return className;
        }
        Path root = Path.of(projectRoot);
        for (Path sourceRoot : sourceRoots(root)) {
            try (var files = Files.walk(sourceRoot)) {
                Path found = files.filter(p -> p.getFileName() != null
                        && p.getFileName().toString().equals(className + ".java")).findFirst().orElse(null);
                if (found != null) {
                    return sourceRoot.relativize(found).toString().replace('\\', '/')
                            .replaceAll("\\.java$", "").replace('/', '.');
                }
            } catch (Exception ignored) {
                // 这个源码根读不了：看下一个
            }
        }
        Matcher frame = STACK_FRAME.matcher(evidenceJson == null ? "" : evidenceJson);
        return frame.find() ? frame.group(1) : className;
    }

    /** 项目根与一层子模块的 src/main/java（存在的那些）。 */
    private static List<Path> sourceRoots(Path root) {
        List<Path> roots = new ArrayList<>();
        roots.add(root.resolve("src/main/java"));
        try (var modules = Files.list(root)) {
            modules.filter(Files::isDirectory).map(module -> module.resolve("src/main/java"))
                    .filter(Files::isDirectory).forEach(roots::add);
        } catch (Exception ignored) {
            // 项目根列不出来：只看默认位置
        }
        return roots;
    }

    /** 按全限定名在项目根或一层子模块的 src/main/java 下找源码文件。 */
    static String readSource(String projectRoot, String className) {
        Path relative = Path.of("src", "main", "java", className.replace('.', '/') + ".java");
        Path root = Path.of(projectRoot);
        List<Path> candidates = new ArrayList<>();
        candidates.add(root.resolve(relative));
        try (var modules = Files.list(root)) {
            modules.filter(Files::isDirectory).map(module -> module.resolve(relative)).forEach(candidates::add);
        } catch (Exception ignored) {
            // 项目根列不出来：只试默认位置
        }
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                try {
                    return Files.readString(candidate, StandardCharsets.UTF_8);
                } catch (Exception ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    /** 取一个方法的源码：从方法签名前 3 行（带注解）到方法体结束，最多 60 行、2400 字符。 */
    static String methodSnippet(String source, String methodName) {
        String[] lines = source.replace("\r\n", "\n").split("\n");
        Pattern signature = Pattern.compile("^[^/]*\\b" + Pattern.quote(methodName) + "\\s*\\(");
        int start = -1;
        for (int i = 0; i < lines.length; i++) {
            if (signature.matcher(lines[i]).find() && !lines[i].trim().startsWith("//") && !lines[i].contains("." + methodName + "(")) {
                start = i;
                break;
            }
        }
        if (start < 0) {
            return null;
        }
        int from = Math.max(0, start - 3);
        int depth = 0;
        boolean opened = false;
        StringBuilder snippet = new StringBuilder();
        for (int i = from; i < lines.length && i < from + 60; i++) {
            snippet.append(lines[i]).append('\n');
            // 前面带上的几行（注解、上一个方法的收尾括号）不属于这个方法，不参与括号配对
            if (i < start) {
                continue;
            }
            for (char ch : lines[i].toCharArray()) {
                if (ch == '{') {
                    depth++;
                    opened = true;
                } else if (ch == '}') {
                    depth--;
                }
            }
            if (opened && depth <= 0) {
                break;
            }
        }
        return snippet.length() > 2400 ? snippet.substring(0, 2400) + "…（截断）" : snippet.toString();
    }

    /** 查方法节点、调用方、入口点；返回图谱命中状态并填充锚点。 */
    private String collectGraphEvidence(StageContext ctx, String className, String methodName,
                                        List<String> projectPaths, List<Map<String, Object>> anchors) {
        String status = "not_found";
        try {
            ApiResponse<List<Map<String, Object>>> methods = knowledgeGraphController
                    .searchMethods(className + " " + methodName, null, projectPaths, 3);
            if (methods.isSuccess() && methods.getData() != null && !methods.getData().isEmpty()) {
                status = "found";
                Map<String, Object> node = methods.getData().get(0);
                anchors.add(Map.of("source", "graph_method", "text",
                        "图谱方法：" + node.getOrDefault("className", className) + "#" + node.getOrDefault("methodName", methodName),
                        "nodeId", String.valueOf(node.getOrDefault("nodeId", ""))));
            }
        } catch (Exception e) {
            ctx.log("方法检索失败：" + e.getMessage());
        }
        try {
            ApiResponse<List<Map<String, Object>>> callers = knowledgeGraphController
                    .getCallChainsAffecting(className, methodName, null, projectPaths);
            if (callers.isSuccess() && callers.getData() != null) {
                int shown = Math.min(callers.getData().size(), 5);
                for (int i = 0; i < shown; i++) {
                    anchors.add(Map.of("source", "caller",
                            "text", "上游调用方：" + callerLabel(callers.getData().get(i))));
                }
                if (!callers.getData().isEmpty()) {
                    status = "found";
                }
            }
        } catch (Exception e) {
            ctx.log("调用链检索失败：" + e.getMessage());
        }
        try {
            ApiResponse<Map<String, Object>> entries = knowledgeGraphController
                    .getRootEntries(className, methodName, null, projectPaths);
            if (entries.isSuccess() && entries.getData() != null && !entries.getData().isEmpty()) {
                anchors.add(Map.of("source", "entry",
                        "text", "可达入口：" + entries.getData().keySet()));
                status = "found";
            }
        } catch (Exception e) {
            ctx.log("入口检索失败：" + e.getMessage());
        }
        return status;
    }

    private static String callerLabel(Map<String, Object> caller) {
        Object cls = caller.get("className");
        Object method = caller.get("methodName");
        if (cls == null) {
            return String.valueOf(caller);
        }
        return cls + (method == null ? "" : "#" + method);
    }

    private static String confidenceOf(String graphStatus, int anchorCount) {
        if ("found".equals(graphStatus) && anchorCount >= 2) {
            return "high";
        }
        return anchorCount >= 2 ? "medium" : "low";
    }

    private List<String> parseList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }
}
