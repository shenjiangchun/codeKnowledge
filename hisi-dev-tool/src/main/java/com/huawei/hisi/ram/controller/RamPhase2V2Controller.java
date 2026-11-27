package com.huawei.hisi.ram.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.agentflow.AgentLlmClient;
import com.huawei.hisi.model.ApiResponse;
import com.huawei.hisi.ram.kg.dto.Bridge;
import com.huawei.hisi.ram.kg.dto.Entry;
import com.huawei.hisi.ram.kg.dto.MethodBodyInfo;
import com.huawei.hisi.ram.kg.dto.Seed;
import com.huawei.hisi.ram.model.AgentEvent;
import com.huawei.hisi.ram.model.AgentSession;
import com.huawei.hisi.ram.model.EventType;
import com.huawei.hisi.ram.model.Phase2Context;
import com.huawei.hisi.ram.model.SessionStatus;
import com.huawei.hisi.ram.model.SessionType;
import com.huawei.hisi.ram.nodes.Phase2AnalysisNode;
import com.huawei.hisi.ram.repository.AgentEventRepository;
import com.huawei.hisi.ram.repository.AgentSessionRepository;
import com.huawei.hisi.ram.service.SessionMappingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Phase2 V2 精确位置分析：多链路编排 + 分层报告（SummaryLayer / DetailLayer）。
 *
 * <p>前端早已切到 v2（{@code /api/ram/status/phase2/v2/*}），后端此前从未实现。本控制器补齐：
 * <ul>
 *   <li>链路拆分：以图谱取证（{@link Phase2AnalysisNode}）的根入口为锚点，每条调用链一个分析单元；</li>
 *   <li>链路分析：每条链一次 pi 一次性结构化调用（{@link AgentLlmClient}），逐链产出摘要/发现/建议；</li>
 *   <li>分层合并：SummaryLayer（领域概览、关键发现、跨链影响、整体建议）+ DetailLayer（链路明细、统计）；</li>
 *   <li>进度：会话状态 RUNNING→DONE/FAILED，进度计数内存可见，报告存 CHECKPOINT 事件。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/ram/status/phase2/v2")
public class RamPhase2V2Controller {

    private static final Logger log = LoggerFactory.getLogger(RamPhase2V2Controller.class);

    /** 每条链的分析时限（分钟），整个编排还有外层 25 分钟硬超时。 */
    private static final int CHAIN_TIMEOUT_MINUTES = 6;

    private final SessionMappingService sessionMappingService;
    private final AgentSessionRepository sessionRepository;
    private final AgentEventRepository eventRepository;
    private final ObjectMapper objectMapper;
    private final Phase2AnalysisNode phase2AnalysisNode;
    private final AgentLlmClient llm;
    private final ExecutorService asyncExecutor;
    private final Map<Long, Progress> progressBySession = new ConcurrentHashMap<>();

    public RamPhase2V2Controller(SessionMappingService sessionMappingService,
                                 AgentSessionRepository sessionRepository,
                                 AgentEventRepository eventRepository,
                                 ObjectMapper objectMapper,
                                 Phase2AnalysisNode phase2AnalysisNode,
                                 AgentLlmClient llm) {
        this.sessionMappingService = sessionMappingService;
        this.sessionRepository = sessionRepository;
        this.eventRepository = eventRepository;
        this.objectMapper = objectMapper;
        this.phase2AnalysisNode = phase2AnalysisNode;
        this.llm = llm;
        this.asyncExecutor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "ram-phase2-v2-async");
            t.setDaemon(true);
            return t;
        });
    }

    public record Phase2V2StartRequest(String sessionId, String question, List<String> focusAreas) {}
    public record Phase2V2StartResponse(String sessionId, String status, int estimatedChains) {}
    public record Phase2V2StatusResponse(String status, Map<String, Object> progress) {}
    public record Phase2V2ReportResponse(String status, Map<String, Object> summaryLayer, Map<String, Object> detailLayer) {}

    private record Progress(int chainsTotal, int chainsCompleted, String currentChain) {}

    // ─────────────────────────── start ───────────────────────────

    @PostMapping("/start")
    public ApiResponse<Phase2V2StartResponse> start(@RequestBody Phase2V2StartRequest request) {
        log.info("[RAM][phase2-v2][start] request={}", request);
        if (request == null || request.sessionId() == null || request.sessionId().isBlank()) {
            return ApiResponse.error(400, "sessionId is required");
        }
        if (request.question() == null || request.question().isBlank()) {
            return ApiResponse.error(400, "question is required");
        }

        Long parentBackendId = sessionMappingService.resolveBackendId(request.sessionId());
        if (parentBackendId == null) {
            return ApiResponse.error(404, "parent session not found: " + request.sessionId());
        }
        Optional<AgentSession> parent = sessionRepository.findById(parentBackendId);
        if (parent.isEmpty()) {
            return ApiResponse.error(404, "parent session row missing: " + parentBackendId);
        }
        String projectPath = extractProjectPath(parent.get());
        if (projectPath == null || projectPath.isBlank()) {
            return ApiResponse.error(400, "parent session has no projectPath");
        }

        String handle = UUID.randomUUID().toString();
        AgentSession seeded = sessionRepository.save(AgentSession.newRunning("phase2-v2-analysis", SessionType.PHASE2));
        long backendId = seeded.getId();
        seeded.setUuid(handle);
        seeded.setIntent("Phase2 V2 精确位置分析: " + request.question());
        try {
            seeded.setProjectPaths(objectMapper.writeValueAsString(List.of(projectPath)));
        } catch (Exception ignored) {
        }
        sessionRepository.update(seeded);
        sessionMappingService.register(handle, backendId);
        log.info("[RAM][phase2-v2][start] handle={} backendId={} parent={} projectPath={}",
                handle, backendId, request.sessionId(), projectPath);

        CompletableFuture.runAsync(() -> execute(handle, backendId, projectPath, request.question(),
                        request.focusAreas() == null ? List.of() : request.focusAreas()),
                asyncExecutor);
        // estimatedChains 此时未知：先给 1，进度接口会更新真实值
        return ApiResponse.success(new Phase2V2StartResponse(handle, "RUNNING", 1));
    }

    // ─────────────────────────── status ───────────────────────────

    @GetMapping("/{sid}/status")
    public ApiResponse<Phase2V2StatusResponse> status(@PathVariable("sid") String handle) {
        Long backendId = sessionMappingService.resolveBackendId(handle);
        if (backendId == null) {
            return ApiResponse.error(404, "session not found: " + handle);
        }
        String status = sessionRepository.findById(backendId)
                .map(s -> s.getStatus() == null ? "UNKNOWN" : s.getStatus().name())
                .orElse("UNKNOWN");
        Progress p = progressBySession.get(backendId);
        Map<String, Object> progress = new LinkedHashMap<>();
        progress.put("chainsTotal", p == null ? 1 : p.chainsTotal());
        progress.put("chainsCompleted", p == null ? 0 : p.chainsCompleted());
        progress.put("currentChain", p == null ? "链路拆分中" : p.currentChain());
        int remaining = p == null ? 1 : Math.max(0, p.chainsTotal() - p.chainsCompleted());
        progress.put("estimatedTimeRemaining", remaining * 90);
        return ApiResponse.success(new Phase2V2StatusResponse(status, progress));
    }

    // ─────────────────────────── report ───────────────────────────

    @GetMapping("/{sid}/report")
    public ApiResponse<Phase2V2ReportResponse> report(@PathVariable("sid") String handle) {
        Long backendId = sessionMappingService.resolveBackendId(handle);
        if (backendId == null) {
            return ApiResponse.error(404, "session not found: " + handle);
        }
        String status = sessionRepository.findById(backendId)
                .map(s -> s.getStatus() == null ? "UNKNOWN" : s.getStatus().name())
                .orElse("UNKNOWN");
        Map<String, Object> checkpoint = findLatestCheckpoint(backendId);
        if (checkpoint == null) {
            return ApiResponse.success(new Phase2V2ReportResponse(status, Map.of(), Map.of()));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = checkpoint.get("summaryLayer") instanceof Map<?, ?> s ? (Map<String, Object>) s : Map.of();
        @SuppressWarnings("unchecked")
        Map<String, Object> detail = checkpoint.get("detailLayer") instanceof Map<?, ?> d ? (Map<String, Object>) d : Map.of();
        return ApiResponse.success(new Phase2V2ReportResponse(status, summary, detail));
    }

    // ─────────────────────────── 编排 ───────────────────────────

    private void execute(String handle, long backendId, String projectPath, String question, List<String> focusAreas) {
        try {
            // 1. 图谱取证（复用 v1 节点：核心方法、上下游链、根入口、代码片段、桥接点）
            log.info("[RAM][phase2-v2] collecting KG data backendId={}", backendId);
            Map<String, Object> kgOutput = phase2AnalysisNode.execute(Map.of(
                    "projectPath", projectPath, "question", question, "focusAreas", focusAreas));
            Object contextObj = kgOutput == null ? null : kgOutput.get("phase2_context");
            if (!(contextObj instanceof Phase2Context context)) {
                fail(backendId, "Phase2Context missing from KG output");
                return;
            }

            // 2. 链路拆分
            List<Chain> chains = splitChains(context);
            progressBySession.put(backendId, new Progress(chains.size(), 0, chains.isEmpty() ? "无链路" : chains.get(0).name()));
            log.info("[RAM][phase2-v2] split {} chains backendId={}", chains.size(), backendId);
            if (chains.isEmpty()) {
                fail(backendId, "图谱未定位到任何调用链");
                return;
            }

            // 3. 逐链分析（串行；每链一次 pi 一次性结构化调用）
            int completed = 0;
            for (Chain chain : chains) {
                progressBySession.put(backendId, new Progress(chains.size(), completed, chain.name()));
                ChainResult r = analyzeChain(chain, question, context, backendId);
                chain.result = r;
                completed++;
                progressBySession.put(backendId, new Progress(chains.size(), completed, chain.name()));
            }

            // 4. 分层合并
            Map<String, Object> merged = merge(question, chains, context);
            appendCheckpoint(backendId, merged);
            sessionRepository.updateStatus(backendId, SessionStatus.DONE);
            // 终态保留最终进度（不删）：status 接口在 DONE 后仍给出有意义的完成态
            progressBySession.put(backendId, new Progress(chains.size(), chains.size(), "全部完成"));
            log.info("[RAM][phase2-v2] done backendId={} chains={} keys={}", backendId, chains.size(), merged.keySet());
        } catch (Exception e) {
            log.error("[RAM][phase2-v2] failed backendId={} error={}", backendId, e.getMessage(), e);
            fail(backendId, e.getMessage() == null ? e.getClass().getName() : e.getMessage());
        }
    }

    /** 一条调用链：一个根入口（或核心方法）加它牵出的方法序列与代码片段。 */
    private static final class Chain {
        final String name;
        final List<Map<String, Object>> methods = new ArrayList<>();
        final Set<String> methodKeys = new LinkedHashSet<>();
        volatile ChainResult result;
        Chain(String name) {
            this.name = name;
        }
        String name() {
            return name;
        }
    }

    private record ChainResult(String summary, List<Map<String, Object>> keyFindings, List<String> recommendations) {
    }

    /** 拆分：每个根入口一条链；没有根入口（非 HTTP 项目）时退化为一条「核心方法」链。 */
    private List<Chain> splitChains(Phase2Context context) {
        List<Chain> chains = new ArrayList<>();
        List<Entry> roots = context.rootEntries() == null ? List.of() : context.rootEntries();
        for (Entry root : roots) {
            String cn = root.className();
            int dot = cn.lastIndexOf('.');
            String shortName = dot >= 0 ? cn.substring(dot + 1) : cn;
            Chain chain = new Chain(shortName + "#" + root.methodName());
            chain.methodKeys.add(root.nodeId());
            chains.add(chain);
        }
        if (chains.isEmpty()) {
            Chain fallback = new Chain("核心方法链");
            for (Seed seed : context.coreMethods() == null ? List.<Seed>of() : context.coreMethods()) {
                fallback.methodKeys.add(seed.nodeId());
            }
            chains.add(fallback);
        }
        // 核心方法并入第一条链（多链时核心方法属于被分析的焦点，挂在第一条）
        if (context.coreMethods() != null) {
            for (Seed seed : context.coreMethods()) {
                chains.get(0).methodKeys.add(seed.nodeId());
            }
        }
        // 每条链带上代码片段
        if (context.methodBodies() != null) {
            Map<String, MethodBodyInfo> byId = new LinkedHashMap<>();
            for (MethodBodyInfo m : context.methodBodies()) {
                byId.put(m.nodeId(), m);
            }
            for (Chain chain : chains) {
                for (String id : chain.methodKeys) {
                    MethodBodyInfo m = byId.get(id);
                    if (m != null) {
                        Map<String, Object> mm = new LinkedHashMap<>();
                        mm.put("className", m.className());
                        mm.put("methodName", m.methodName());
                        mm.put("body", m.methodBody());
                        chain.methods.add(mm);
                    }
                }
            }
        }
        return chains;
    }

    private static final String CHAIN_SYSTEM = """
            你是调用链分析专家。给出一条调用链的结构信息与问题，你输出这条链的分析结论。
            只输出下面结构的 JSON 对象，不要代码围栏、不要解释文字：
            {"summary":"这条链路的作用与现状（80字内）","keyFindings":[{"type":"CRITICAL|WARNING|INFO","description":"发现"}],"recommendations":["建议"]}
            要求：summary 必须基于给定证据、不编造；keyFindings 每条都是可定位到具体方法的事实；没有发现时 keyFindings 为空数组。
            """;

    private ChainResult analyzeChain(Chain chain, String question, Phase2Context context, long backendId) {
        StringBuilder user = new StringBuilder();
        user.append("问题：").append(question).append('\n');
        user.append("链路：").append(chain.name()).append("\n\n");
        for (Map<String, Object> m : chain.methods) {
            user.append("=== ").append(m.get("className")).append('#').append(m.get("methodName")).append(" ===\n");
            String body = String.valueOf(m.getOrDefault("body", ""));
            user.append(body, 0, Math.min(body.length(), 3000)).append('\n');
        }
        Map<String, Object> parsed = null;
        try {
            if (llm.isAvailable()) {
                parsed = llm.completeJson(CHAIN_SYSTEM, user.toString(), 2000);
            }
        } catch (Exception e) {
            log.warn("[RAM][phase2-v2] chain analysis failed backendId={} chain={}: {}", backendId, chain.name(), e.getMessage());
        }
        if (parsed == null) {
            return new ChainResult("链路分析不可用（模型调用失败），以下为结构信息。", List.of(), List.of());
        }
        String summary = parsed.get("summary") instanceof String s ? s : String.valueOf(parsed.getOrDefault("summary", ""));
        List<Map<String, Object>> findings = new ArrayList<>();
        if (parsed.get("keyFindings") instanceof List<?> kf) {
            for (Object o : kf) {
                if (o instanceof Map<?, ?> m) {
                    findings.add(Map.<String, Object>of("type",
                            java.util.Objects.toString(m.get("type"), "INFO"),
                            "description",
                            java.util.Objects.toString(m.get("description"), "")));
                }
            }
        }
        List<String> recs = new ArrayList<>();
        if (parsed.get("recommendations") instanceof List<?> rr) {
            for (Object o : rr) {
                recs.add(String.valueOf(o));
            }
        }
        return new ChainResult(summary, findings, recs);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> merge(String question, List<Chain> chains, Phase2Context context) {
        Map<String, Object> detail = new LinkedHashMap<>();
        List<Map<String, Object>> chainViews = new ArrayList<>();
        Set<String> allMethodKeys = new LinkedHashSet<>();
        int codeSnippets = 0;
        for (int i = 0; i < chains.size(); i++) {
            Chain c = chains.get(i);
            ChainResult r = c.result == null ? new ChainResult("", List.of(), List.of()) : c.result;
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("chainId", "chain-" + (i + 1));
            view.put("chainName", c.name());
            view.put("summary", r.summary());
            view.put("expandable", false);
            chainViews.add(view);
            allMethodKeys.addAll(c.methodKeys);
            codeSnippets += c.methods.size();
        }
        detail.put("chains", chainViews);
        detail.put("chainCount", chains.size());
        detail.put("totalMethodsAnalyzed", allMethodKeys.size());
        detail.put("totalCodeSnippets", codeSnippets);

        List<Map<String, Object>> allFindings = new ArrayList<>();
        List<String> allRecs = new ArrayList<>();
        for (Chain c : chains) {
            ChainResult r = c.result == null ? new ChainResult("", List.of(), List.of()) : c.result;
            for (Map<String, Object> f : r.keyFindings()) {
                Map<String, Object> fv = new LinkedHashMap<>(f);
                fv.put("id", "f-" + allFindings.size());
                allFindings.add(fv);
            }
            for (String rec : r.recommendations()) {
                if (!allRecs.contains(rec)) {
                    allRecs.add(rec);
                }
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        StringBuilder overview = new StringBuilder();
        overview.append("针对「").append(question).append("」定位到 ").append(chains.size())
                .append(" 条调用链，覆盖 ").append(allMethodKeys.size()).append(" 个方法。");
        if (chains.size() == 1 && !chains.get(0).result.summary().isBlank()) {
            overview.append(" ").append(chains.get(0).result.summary());
        }
        summary.put("domainOverview", overview.toString());
        summary.put("overallFlowDiagramSvg", null);
        summary.put("keyFindings", allFindings);
        // 跨链影响：来自图谱桥接点（跨服务/跨组件调用）
        List<Map<String, Object>> impacts = new ArrayList<>();
        if (context.bridgePoints() != null) {
            for (Bridge b : context.bridgePoints()) {
                impacts.add(Map.of("fromChain", "链路", "toChain", String.valueOf(b.target()),
                        "relation", String.valueOf(b.bridgeType()), "description", "桥接点：" + b.nodeId()));
            }
        }
        summary.put("crossChainImpacts", impacts);
        summary.put("overallRecommendations", allRecs);

        Map<String, Object> merged = new LinkedHashMap<>();
        merged.put("summaryLayer", summary);
        merged.put("detailLayer", detail);
        return merged;
    }

    // ─────────────────────────── 存储与工具 ───────────────────────────

    private void appendCheckpoint(long backendId, Map<String, Object> merged) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("nodeName", "phase2_v2_analysis");
        payload.put("output", merged);
        AgentEvent ev = AgentEvent.builder()
                .sessionId(backendId)
                .type(EventType.CHECKPOINT)
                .payload(toJson(payload))
                .idempotencyKey("ckpt-" + backendId + "-phase2-v2-" + System.nanoTime())
                .circuitState("OK")
                .validatorStatus("OK")
                .createdAt(System.currentTimeMillis() / 1000L)
                .build();
        eventRepository.append(ev);
    }

    private void fail(long backendId, String message) {
        // 失败也保留当时的进度（status 接口能看到卡在哪条链）
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("nodeName", "phase2_v2_analysis");
        payload.put("error", message);
        payload.put("type", "ExecutionError");
        AgentEvent ev = AgentEvent.builder()
                .sessionId(backendId)
                .type(EventType.ERROR)
                .payload(toJson(payload))
                .idempotencyKey("error-" + backendId + "-phase2-v2-" + System.nanoTime())
                .circuitState("OK")
                .validatorStatus("FAIL")
                .createdAt(System.currentTimeMillis() / 1000L)
                .build();
        try {
            eventRepository.append(ev);
        } catch (RuntimeException e) {
            log.warn("[RAM][phase2-v2] append error event failed backendId={}", backendId, e);
        }
        sessionRepository.updateStatus(backendId, SessionStatus.FAILED);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> findLatestCheckpoint(long backendId) {
        List<AgentEvent> events = eventRepository.findBySessionId(backendId);
        for (int i = events.size() - 1; i >= 0; i--) {
            AgentEvent ev = events.get(i);
            if (ev.getType() != EventType.CHECKPOINT) {
                continue;
            }
            Map<String, Object> payload = parseJsonPayload(ev.getPayload());
            if (payload == null || !"phase2_v2_analysis".equals(payload.get("nodeName"))) {
                continue;
            }
            Object out = payload.get("output");
            if (out instanceof Map<?, ?> m) {
                Map<String, Object> result = new LinkedHashMap<>();
                m.forEach((k, v) -> result.put(String.valueOf(k), v));
                return result;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonPayload(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Object parsed = objectMapper.readValue(json, Object.class);
            if (parsed instanceof Map<?, ?> m) {
                return (Map<String, Object>) m;
            }
            return Map.of("value", parsed);
        } catch (JsonProcessingException e) {
            return Map.of("raw", json);
        }
    }

    private String extractProjectPath(AgentSession session) {
        String projectPathsJson = session.getProjectPaths();
        if (projectPathsJson == null || projectPathsJson.isBlank()) {
            return null;
        }
        try {
            Object parsed = objectMapper.readValue(projectPathsJson, Object.class);
            if (parsed instanceof List<?> list && !list.isEmpty()) {
                return String.valueOf(list.get(0));
            }
            return String.valueOf(parsed);
        } catch (Exception e) {
            log.warn("[RAM] Failed to parse projectPaths from session {}: {}", session.getId(), e.getMessage());
            return null;
        }
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize payload to JSON: {}", e.getOriginalMessage());
            return "{}";
        }
    }
}
