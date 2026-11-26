package com.huawei.hisi.loganalysis.v2;

import com.huawei.hisi.agentflow.EvidenceAnchor;
import com.huawei.hisi.agentflow.EvidencePack;
import com.huawei.hisi.loganalysis.orchestrator.LogAnalysisDagNode;
import com.huawei.hisi.ram.kg.KgMcpClient;
import com.huawei.hisi.ram.kg.dto.Bridge;
import com.huawei.hisi.ram.kg.dto.CallTreeNode;
import com.huawei.hisi.ram.kg.dto.Entry;
import com.huawei.hisi.ram.kg.dto.MethodBodyInfo;
import com.huawei.hisi.ram.kg.dto.Seed;
import com.huawei.hisi.ram.kg.dto.SqlMapping;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 日志分析 v2 的图谱取证节点：精确优先，产出带编号的证据包。
 *
 * <p>与 v1 的 {@code KgSearchNode} 的区别：
 * <ul>
 *   <li>堆栈帧本身就是精确坐标，先按「类名.方法名」精确命中，未命中才回退语义检索并标记为模糊证据；</li>
 *   <li>按堆栈的先后顺序确定「抛出点」与「调用方」，不依赖调用方是否传入项目包前缀；</li>
 *   <li>除下游调用树外，还取上游调用链（谁调到了出事的方法），这是判断「症状 vs 根因」的依据；</li>
 *   <li>把抛出点的直接下游方法的源码一并取出，让模型能核对「下游返回了 null」这类说法是否属实；
 *       图谱里没有源码的方法，按堆栈的文件名与行号从本地源码读取；</li>
 *   <li>SQL 相关异常补取 Mapper 的 SQL 映射，跨服务调用补取桥接关系。</li>
 * </ul>
 *
 * <p>输出键：{@code evidencePackV2}、{@code evidenceAnchors}、{@code codeBodiesV2}、{@code upstreamChainsV2}、
 * {@code throwSiteV2}、{@code stackCallersV2}、{@code entryPoints}。
 */
@Slf4j
@Component
public class LogKgEvidenceNodeV2 implements LogAnalysisDagNode {

    static final String KEY_EVIDENCE_PACK = "evidencePackV2";
    static final String KEY_EVIDENCE_ANCHORS = "evidenceAnchors";
    static final String KEY_CODE_BODIES = "codeBodiesV2";
    static final String KEY_UPSTREAM = "upstreamChainsV2";
    /** 抛出点：最深层异常的第一帧，形如 {@code 类名.方法名}。 */
    static final String KEY_THROW_SITE = "throwSiteV2";
    /** 抛出点之后的帧，按离抛出点由近到远排列，即本次调用的上游。 */
    static final String KEY_STACK_CALLERS = "stackCallersV2";

    static final String ROLE_THROW_SITE = "抛出点";
    static final String ROLE_CALLER = "调用方";
    static final String ROLE_OUTER = "外层异常调用方";
    static final String ROLE_FRAME = "堆栈帧";

    /** v1 ParseNode 产出的几组帧，已经滤掉了框架类与生成方法。 */
    private static final List<String> PARSE_NODE_FRAME_KEYS =
            List.of("rootCauseFrames", "businessFrames", "keyFrames", "otherNonFrameworkFrames");

    private static final Pattern FRAME_LINE =
            Pattern.compile("^\\s*at\\s+([\\w.$]+)\\.([\\w$<>]+)\\(([\\w.]+):(\\d+)\\s*\\)\\s*$");
    private static final Pattern CAUSED_BY = Pattern.compile("Caused by:");

    private static final List<String> SQL_KEYWORDS = List.of(
            "SQL", "JDBC", "MYBATIS", "HIBERNATE", "JPA", "DATAACCESS", "DUPLICATEKEY",
            "LOCKWAIT", "DEADLOCK", "CONNECTION", "RESULTSET", "STATEMENT");

    /** 一条堆栈帧及其角色。 */
    record FrameRef(String className, String methodName, String lineNumber,
                    String fileName, String simpleClassName, String role) {
        String key() {
            return className + "#" + methodName;
        }

        String methodRef() {
            return className + "." + methodName;
        }

        FrameRef withRole(String newRole) {
            return new FrameRef(className, methodName, lineNumber, fileName, simpleClassName, newRole);
        }
    }

    private final ObjectProvider<KgMcpClient> kgProvider;
    private final int maxFrames;
    private final int maxBodies;
    private final int bodyChars;
    private final int upstreamDepth;

    public LogKgEvidenceNodeV2(ObjectProvider<KgMcpClient> kgProvider,
                               @Value("${loganalysis.v2.max-frames:8}") int maxFrames,
                               @Value("${loganalysis.v2.max-bodies:6}") int maxBodies,
                               @Value("${loganalysis.v2.body-chars:1500}") int bodyChars,
                               @Value("${loganalysis.v2.upstream-depth:4}") int upstreamDepth) {
        this.kgProvider = kgProvider;
        this.maxFrames = maxFrames;
        this.maxBodies = maxBodies;
        this.bodyChars = bodyChars;
        this.upstreamDepth = upstreamDepth;
    }

    @Override
    public String name() {
        return "KgEvidenceV2";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input) {
        log.info("[KgEvidenceV2] 开始精确优先的图谱取证");

        EvidencePack pack = new EvidencePack();
        List<MethodBodyInfo> bodies = new ArrayList<>();
        Map<String, List<String>> upstreamChains = new LinkedHashMap<>();
        List<Entry> entryPoints = new ArrayList<>();
        // 已经取到源码的方法（类名.方法名），本地源码兜底时据此判断还缺谁
        Set<String> sourcedRefs = new HashSet<>();

        // 1. 堆栈帧本身是确定性证据，先登记
        List<FrameRef> frames = collectFrames(input);
        for (FrameRef f : frames) {
            pack.add("frame", String.format("%s() 第 %s 行（%s）[%s]",
                    f.methodRef(), f.lineNumber(), f.fileName(), f.role()),
                    null, f.className(), f.methodName(), null, true);
        }

        // 2. 图谱取证
        List<String> paths = parseProjectPaths((String) input.get("projectPath"));
        KgMcpClient kg = kgProvider.getIfAvailable();
        if (kg == null || paths.isEmpty()) {
            log.info("[KgEvidenceV2] 图谱不可用或未提供 projectPath，跳过图谱取证 (kg={}, paths={})",
                    kg != null, paths.size());
        } else {
            collectFromGraph(kg, paths, frames, input, pack, bodies, upstreamChains, entryPoints, sourcedRefs);
        }

        // 3. 图谱里没有源码的方法，按堆栈的行号从本地文件读
        if (!paths.isEmpty()) {
            addLocalSource(paths, frames, pack, bodies, sourcedRefs);
        }

        log.info("[KgEvidenceV2] 取证完成: frames={}, throwSite={}, evidence={}, bodies={}, upstreamChains={}, entryPoints={}",
                frames.size(), frames.isEmpty() ? null : frames.get(0).methodRef(),
                pack.size(), bodies.size(), upstreamChains.size(), entryPoints.size());

        Map<String, Object> output = new LinkedHashMap<>(input);
        output.put(KEY_EVIDENCE_PACK, pack);
        output.put(KEY_EVIDENCE_ANCHORS, pack.toMaps());
        output.put(KEY_CODE_BODIES, bodies);
        output.put(KEY_UPSTREAM, upstreamChains);
        output.put(KEY_THROW_SITE, frames.isEmpty() ? null : frames.get(0).methodRef());
        output.put(KEY_STACK_CALLERS, frames.stream().skip(1).map(FrameRef::methodRef).toList());
        if (!entryPoints.isEmpty()) {
            output.put("entryPoints", entryPoints);
        }
        return output;
    }

    /**
     * 取 v1 ParseNode 产出的帧（已滤掉框架帧），再按堆栈里的先后顺序排列：
     * 最深层异常的帧排在最前，其中第一帧是抛出点，其后是本次调用的上游。
     */
    @SuppressWarnings("unchecked")
    List<FrameRef> collectFrames(Map<String, Object> input) {
        Set<String> kept = new HashSet<>();
        List<FrameRef> parseNodeFrames = new ArrayList<>();
        Set<String> seenInParseNode = new HashSet<>();
        for (String listKey : PARSE_NODE_FRAME_KEYS) {
            if (!(input.get(listKey) instanceof List<?> frames)) {
                continue;
            }
            for (Object o : frames) {
                if (!(o instanceof Map<?, ?> m) || m.get("className") == null || m.get("methodName") == null) {
                    continue;
                }
                Map<String, Object> f = (Map<String, Object>) m;
                kept.add(f.get("className") + "#" + f.get("methodName") + ":" + f.get("lineNumber"));
                FrameRef ref = new FrameRef(String.valueOf(f.get("className")), String.valueOf(f.get("methodName")),
                        f.get("lineNumber") == null ? null : String.valueOf(f.get("lineNumber")),
                        f.get("fileName") == null ? null : String.valueOf(f.get("fileName")),
                        f.get("simpleClassName") == null ? null : String.valueOf(f.get("simpleClassName")), ROLE_FRAME);
                if (seenInParseNode.add(ref.key())) {
                    parseNodeFrames.add(ref);
                }
            }
        }

        String stackTrace = input.get("stackTrace") instanceof String s ? s : null;
        List<FrameRef> ordered = stackTrace == null || stackTrace.isBlank()
                ? List.of() : orderFrames(stackTrace, kept);
        if (ordered.isEmpty()) {
            // 从堆栈里排不出顺序时（例如只有 capture 载荷），退回 ParseNode 的分组顺序
            ordered = parseNodeFrames;
        }
        return ordered.size() > maxFrames ? new ArrayList<>(ordered.subList(0, maxFrames)) : ordered;
    }

    /**
     * 按堆栈原文排列帧。{@code Caused by} 把堆栈分成若干层，最后一层是最深层异常；
     * 先排它的帧（第一帧是抛出点），再由内向外排其它层的帧。
     */
    static List<FrameRef> orderFrames(String stackTrace, Set<String> kept) {
        List<Object[]> parsed = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int block = 0;
        int deepest = -1;
        for (String line : stackTrace.split("\n")) {
            if (CAUSED_BY.matcher(line).find()) {
                block++;
                continue;
            }
            Matcher m = FRAME_LINE.matcher(line);
            if (!m.matches()) {
                continue;
            }
            String className = m.group(1);
            String methodName = m.group(2);
            if (!kept.contains(className + "#" + methodName + ":" + m.group(4)) || !seen.add(className + "#" + methodName)) {
                continue;
            }
            int dot = className.lastIndexOf('.');
            parsed.add(new Object[]{block, new FrameRef(className, methodName, m.group(4), m.group(3),
                    dot < 0 ? className : className.substring(dot + 1), ROLE_FRAME)});
            deepest = Math.max(deepest, block);
        }

        List<FrameRef> out = new ArrayList<>();
        for (int b = deepest; b >= 0; b--) {
            boolean first = true;
            for (Object[] p : parsed) {
                if ((Integer) p[0] != b) {
                    continue;
                }
                FrameRef ref = (FrameRef) p[1];
                out.add(ref.withRole(b == deepest ? (first ? ROLE_THROW_SITE : ROLE_CALLER) : ROLE_OUTER));
                first = false;
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private void collectFromGraph(KgMcpClient kg, List<String> paths, List<FrameRef> frames,
                                  Map<String, Object> input, EvidencePack pack,
                                  List<MethodBodyInfo> bodies, Map<String, List<String>> upstreamChains,
                                  List<Entry> entryPoints, Set<String> sourcedRefs) {
        // 1. 逐帧定位：精确优先，未命中才回退语义检索
        List<String> matchedNodeIds = new ArrayList<>();
        List<String> exactRefs = new ArrayList<>();
        for (FrameRef f : frames) {
            List<Seed> seeds = safe(() -> kg.representativeMethod(f.className(), paths, 30), "representativeMethod");
            List<Seed> exact = seeds.stream().filter(s -> f.methodName().equals(methodOf(s.nodeId()))).toList();
            if (!exact.isEmpty()) {
                matchedNodeIds.add(exact.get(0).nodeId());
                exactRefs.add(f.methodRef());
                pack.add("method", "精确命中 " + f.methodRef() + "（" + exact.size() + " 个重载）",
                        exact.get(0).nodeId(), f.className(), f.methodName(), null, true);
            } else {
                String query = (f.simpleClassName() != null ? f.simpleClassName() : f.className()) + " " + f.methodName();
                List<Seed> fuzzy = safe(() -> kg.hybridSearch(query, paths, 3), "hybridSearch");
                Seed first = fuzzy.stream().filter(s -> methodOf(s.nodeId()) != null).findFirst().orElse(null);
                if (first != null) {
                    pack.add("semantic", "未精确命中，语义相近：" + methodRef(first.nodeId())
                                    + (first.summary() != null ? " — " + first.summary() : ""),
                            first.nodeId(), classOf(first.nodeId()), methodOf(first.nodeId()), null, false);
                }
            }
        }

        // 2. 抛出点的下游调用树：既是证据，也用来取直接下游方法的源码，供模型核对
        FrameRef throwSite = frames.get(0);
        List<String> calleeNodeIds = new ArrayList<>();
        if (exactRefs.contains(throwSite.methodRef())) {
            CallTreeNode tree = safeCall(() -> kg.calleesTree(throwSite.className(), throwSite.methodName(), paths, 2), "calleesTree");
            List<String> callees = new ArrayList<>();
            flatten(tree, 12, callees);
            if (!callees.isEmpty()) {
                pack.add("downstream", throwSite.methodRef() + "（抛出点）的下游调用：" + callees,
                        null, throwSite.className(), throwSite.methodName(), null, true);
            }
            if (tree != null) {
                for (CallTreeNode child : tree.children()) {
                    if (child.nodeId() != null && !calleeNodeIds.contains(child.nodeId()) && calleeNodeIds.size() < 3) {
                        calleeNodeIds.add(child.nodeId());
                    }
                }
            }
        }

        // 3. 源码：精确命中的方法，加上抛出点的直接下游方法
        if (!matchedNodeIds.isEmpty()) {
            List<String> uniqueIds = new ArrayList<>(new LinkedHashSet<>(matchedNodeIds));
            List<String> ids = uniqueIds.subList(0, Math.min(maxBodies, uniqueIds.size()));
            addBodies(safe(() -> kg.loadMethodBodies(ids, paths), "loadMethodBodies"), pack, bodies, false, sourcedRefs);
        }
        if (!calleeNodeIds.isEmpty()) {
            addBodies(safe(() -> kg.loadMethodBodies(calleeNodeIds, paths), "loadMethodBodies"), pack, bodies, true, sourcedRefs);
        }

        // 4. 上游调用链：抛出点优先，再看一个调用方
        for (FrameRef f : frames.stream().limit(2).filter(f -> exactRefs.contains(f.methodRef())).toList()) {
            List<Entry> callers = safe(() -> kg.affecting(f.className(), f.methodName(), paths, upstreamDepth), "affecting");
            List<String> chain = callers.stream()
                    .map(e -> methodRef(e.nodeId()))
                    .filter(r -> r != null)
                    .distinct()
                    .limit(12)
                    .toList();
            if (!chain.isEmpty()) {
                upstreamChains.put(f.key(), chain);
                pack.add("upstream", "谁调用了 " + f.methodRef() + "（深度≤" + upstreamDepth + "）："
                                + String.join(" ← ", chain),
                        null, f.className(), f.methodName(), null, true);
            }
        }

        // 5. 入口点：调用方帧优先（没有则用抛出点）
        List<FrameRef> entryTargets = frames.stream().skip(1).limit(3).toList();
        if (entryTargets.isEmpty()) {
            entryTargets = frames.stream().limit(1).toList();
        }
        for (FrameRef f : entryTargets) {
            List<Entry> entries = safe(() -> kg.rootEntries(f.className(), f.methodName(), paths), "rootEntries");
            entryPoints.addAll(entries);
            if (!entries.isEmpty()) {
                pack.add("entry", "可达 " + f.methodRef() + " 的入口点：" + entries.stream()
                                .map(e -> methodRef(e.nodeId()) + "(" + e.type() + ")").limit(6).toList(),
                        null, f.className(), f.methodName(), null, true);
            }
        }

        // 6. SQL 映射：仅当异常与数据库相关且堆栈里有 Mapper / DAO 帧时
        if (isSqlRelated(input)) {
            frames.stream()
                    .filter(f -> f.className().endsWith("Mapper") || f.className().endsWith("Dao"))
                    .limit(2)
                    .forEach(f -> {
                        for (SqlMapping sql : safe(() -> kg.mybatisSql(f.className(), paths), "mybatisSql")) {
                            pack.add("sql", "Mapper " + sql.mapperInterface() + "." + f.methodName()
                                            + " → " + sql.statementType() + "，涉及字段 " + sql.tableFields(),
                                    null, f.className(), f.methodName(), null, true);
                        }
                    });
        }

        // 7. 跨服务调用：抛出点在图谱中的节点
        if (!matchedNodeIds.isEmpty()) {
            String nodeId = matchedNodeIds.get(0);
            for (Bridge b : safe(() -> kg.bridges(nodeId, paths), "bridges")) {
                pack.add("bridge", "跨服务调用：" + b.bridgeType() + " → " + b.target(),
                        b.nodeId(), null, null, null, true);
            }
        }
    }

    /** 把方法体登记为源码证据；{@code callee} 为真时注明它是抛出点的直接下游方法。 */
    private void addBodies(List<MethodBodyInfo> loaded, EvidencePack pack, List<MethodBodyInfo> bodies,
                           boolean callee, Set<String> sourcedRefs) {
        for (MethodBodyInfo body : loaded) {
            if (body == null || body.methodBody() == null || body.methodBody().isBlank()) {
                continue;
            }
            String trimmed = body.methodBody().length() > bodyChars
                    ? body.methodBody().substring(0, bodyChars) + "\n// …（已截断）" : body.methodBody();
            bodies.add(new MethodBodyInfo(body.nodeId(), body.className(), body.methodName(),
                    body.description(), trimmed, body.filePath()));
            pack.add("source", "源码 " + body.className() + "." + body.methodName() + "()"
                            + (body.filePath() != null ? "（" + body.filePath() + "）" : "")
                            + (callee ? "，抛出点的直接下游方法" : ""),
                    body.nodeId(), body.className(), body.methodName(), body.filePath(), true);
            sourcedRefs.add(body.className() + "." + body.methodName());
        }
    }

    /**
     * 图谱里没有源码的方法，按堆栈的文件名与行号从本地源码读取。
     * 只处理最前面的帧（抛出点与第一个调用方）——它们是判断根因最需要的两段代码。
     */
    private void addLocalSource(List<String> paths, List<FrameRef> frames, EvidencePack pack,
                                List<MethodBodyInfo> bodies, Set<String> sourcedRefs) {
        for (FrameRef f : frames.stream().limit(2).toList()) {
            if (sourcedRefs.contains(f.methodRef()) || f.lineNumber() == null) {
                continue;
            }
            int line;
            try {
                line = Integer.parseInt(f.lineNumber().trim());
            } catch (NumberFormatException e) {
                continue;
            }
            boolean throwSite = ROLE_THROW_SITE.equals(f.role());
            SourceSnippetReader.Snippet snippet = SourceSnippetReader.read(paths, f.className(), f.methodName(), line,
                    throwSite ? "异常在这里抛出" : "堆栈指向这一行");
            if (snippet == null) {
                continue;
            }
            bodies.add(new MethodBodyInfo(null, f.className(), f.methodName(), null, snippet.text(), snippet.filePath()));
            // 写清楚「省略的是无关上下文、抛出行本身就在片段里」：模型曾把片段开头的「上方省略 N 行」误读成抛出点被截掉了
            pack.add("source", "源码 " + f.methodRef() + "()（" + snippet.filePath() + "），按堆栈行号从本地文件截取的片段：第 "
                            + line + " 行是" + (throwSite ? "抛出点" : "堆栈指向的行")
                            + "，这一行就在下面的「源码片段」里并用 <-- 注释标出；片段首尾省略的只是与它无关的上下文",
                    null, f.className(), f.methodName(), snippet.filePath(), true);
            sourcedRefs.add(f.methodRef());
        }
    }

    @SuppressWarnings("unchecked")
    private boolean isSqlRelated(Map<String, Object> input) {
        Map<String, Object> parsed = (Map<String, Object>) input.get("parsedError");
        String text = "";
        if (parsed != null) {
            text += " " + str(parsed.get("rootCauseException")) + " " + str(parsed.get("errorType")) + " "
                    + str(parsed.get("fullMessage"));
        }
        text += " " + str(input.get("message"));
        String upper = text.toUpperCase(Locale.ROOT);
        return SQL_KEYWORDS.stream().anyMatch(upper::contains);
    }

    private void flatten(CallTreeNode node, int limit, List<String> out) {
        if (node == null || out.size() >= limit) {
            return;
        }
        for (CallTreeNode child : node.children()) {
            if (out.size() >= limit) {
                return;
            }
            String ref = child.className() + "." + child.methodName();
            if (!out.contains(ref)) {
                out.add(ref);
            }
            flatten(child, limit, out);
        }
    }

    /** 图谱节点 ID 形如 {@code projectPath:className.methodName.sigHash}，拆出方法名；解析不了返回 null。 */
    static String methodOf(String nodeId) {
        int dot = nodeId == null ? -1 : nodeId.lastIndexOf('.');
        if (dot <= 0) {
            return null;
        }
        String head = nodeId.substring(0, dot);
        int sep = head.lastIndexOf('.');
        return sep < 0 ? null : head.substring(sep + 1);
    }

    /** 拆出节点 ID 里的类名；解析不了返回 null。 */
    static String classOf(String nodeId) {
        int dot = nodeId == null ? -1 : nodeId.lastIndexOf('.');
        if (dot <= 0) {
            return null;
        }
        String head = nodeId.substring(0, dot);
        int sep = head.lastIndexOf('.');
        if (sep < 0) {
            return null;
        }
        return head.substring(head.lastIndexOf(':') + 1, sep);
    }

    static String methodRef(String nodeId) {
        String method = methodOf(nodeId);
        String className = classOf(nodeId);
        return method != null && className != null ? className + "." + method : null;
    }

    /** projectPath 支持逗号分隔的多路径。 */
    static List<String> parseProjectPaths(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        for (String p : raw.split("[,;]")) {
            if (!p.isBlank() && !out.contains(p.trim())) {
                out.add(p.trim());
            }
        }
        return out;
    }

    /** 图谱查询失败不应中断分析流程：记日志并返回空结果。 */
    private static <T> List<T> safe(Supplier<List<T>> call, String op) {
        try {
            List<T> result = call.get();
            return result == null ? List.of() : result;
        } catch (Exception e) {
            log.warn("[KgEvidenceV2] 图谱查询 {} 失败，按空结果继续: {}", op, e.getMessage());
            return List.of();
        }
    }

    private static <T> T safeCall(Supplier<T> call, String op) {
        try {
            return call.get();
        } catch (Exception e) {
            log.warn("[KgEvidenceV2] 图谱查询 {} 失败，按空结果继续: {}", op, e.getMessage());
            return null;
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
