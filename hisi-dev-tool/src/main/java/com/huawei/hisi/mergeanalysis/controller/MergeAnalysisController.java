package com.huawei.hisi.mergeanalysis.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.mergeanalysis.model.DiffResult;
import com.huawei.hisi.mergeanalysis.service.DiffExtractService;
import com.huawei.hisi.mergeanalysis.service.MergeAnalysisService;
import com.huawei.hisi.model.ApiResponse;
import com.huawei.hisi.ram.model.AgentEvent;
import com.huawei.hisi.ram.model.AgentSession;
import com.huawei.hisi.ram.model.SessionStatus;
import com.huawei.hisi.ram.repository.AgentEventRepository;
import com.huawei.hisi.ram.repository.AgentSessionRepository;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@RestController
@RequestMapping("/api/merge-analysis")
@Slf4j
public class MergeAnalysisController {

    private static final long SSE_TIMEOUT_MS = 30 * 60 * 1000L;
    private static final long POLL_INTERVAL_MS = 500L;

    private final DiffExtractService diffExtractService;
    private final MergeAnalysisService mergeAnalysisService;
    private final AgentSessionRepository sessionRepository;
    private final AgentEventRepository eventRepository;
    private final ObjectMapper objectMapper;

    private final Map<String, Long> sessionIdMap = new ConcurrentHashMap<>();
    private final ScheduledExecutorService streamScheduler = Executors.newScheduledThreadPool(2);

    public MergeAnalysisController(DiffExtractService diffExtractService,
                                   MergeAnalysisService mergeAnalysisService,
                                   AgentSessionRepository sessionRepository,
                                   AgentEventRepository eventRepository,
                                   ObjectMapper objectMapper) {
        this.diffExtractService = diffExtractService;
        this.mergeAnalysisService = mergeAnalysisService;
        this.sessionRepository = sessionRepository;
        this.eventRepository = eventRepository;
        this.objectMapper = objectMapper;
    }

    @PreDestroy
    void shutdown() {
        streamScheduler.shutdownNow();
    }

    public record DiffRequest(String projectPath, String sourceBranch, String targetBranch) {}
    public record StartRequest(String projectPath, String sourceBranch, String targetBranch) {}
    public record StartResponse(String sessionHandle) {}
    public record SessionInfo(String status, String currentNode, long lastSeq) {}

    @GetMapping("/branches")
    public ApiResponse<List<String>> listBranches(@RequestParam String projectPath) {
        List<String> branches = diffExtractService.listBranches(projectPath);
        return ApiResponse.success(branches);
    }

    @PostMapping("/diff")
    public ApiResponse<DiffResult> getDiff(@RequestBody DiffRequest request) {
        DiffResult result = diffExtractService.extractDiff(
                request.projectPath(), request.sourceBranch(), request.targetBranch());
        return ApiResponse.success(result);
    }

    @PostMapping("/sessions")
    public ApiResponse<StartResponse> startSession(@RequestBody StartRequest request) {
        String handle = UUID.randomUUID().toString();
        long id = mergeAnalysisService.createSession(
                request.projectPath(), request.sourceBranch(), request.targetBranch());
        sessionIdMap.put(handle, id);
        mergeAnalysisService.runAnalysis(id, request.projectPath(), request.sourceBranch(), request.targetBranch());
        return ApiResponse.success(new StartResponse(handle));
    }

    @GetMapping("/sessions/{sid}")
    public ApiResponse<SessionInfo> getSession(@PathVariable("sid") String handle) {
        Long backendId = sessionIdMap.get(handle);
        if (backendId == null) {
            return ApiResponse.error(404, "Session not found");
        }
        Optional<AgentSession> session = sessionRepository.findById(backendId);
        if (session.isEmpty()) {
            return ApiResponse.error(404, "Session not found");
        }
        AgentSession s = session.get();
        long lastSeq = eventRepository.findMaxSeq(backendId);
        return ApiResponse.success(new SessionInfo(s.getStatus().name(), s.getCurrentNode(), lastSeq));
    }

    @GetMapping("/sessions/{sid}/stream")
    public SseEmitter stream(@PathVariable("sid") String handle,
                             @RequestParam(value = "afterSeq", required = false) Long afterSeq) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        AtomicLong lastSeq = new AtomicLong(afterSeq == null ? 0L : Math.max(0L, afterSeq));

        Runnable tick = () -> {
            try {
                Long backendId = sessionIdMap.get(handle);
                if (backendId == null) {
                    return;
                }

                List<AgentEvent> events = eventRepository.findBySessionId(backendId);
                for (AgentEvent ev : events) {
                    if (ev.getSeq() <= lastSeq.get()) {
                        continue;
                    }
                    sendEvent(emitter, toSseMap(ev));
                    lastSeq.set(ev.getSeq());
                }

                Optional<AgentSession> session = sessionRepository.findById(backendId);
                if (session.isPresent()) {
                    SessionStatus status = session.get().getStatus();
                    if (status == SessionStatus.DONE) {
                        sendEvent(emitter, syntheticEvent(lastSeq.incrementAndGet(), "RUN_COMPLETED", Map.of()));
                        emitter.complete();
                    } else if (status == SessionStatus.FAILED) {
                        sendEvent(emitter, syntheticEvent(lastSeq.incrementAndGet(), "RUN_FAILED", Map.of()));
                        emitter.complete();
                    }
                }
            } catch (ClientGoneException e) {
                log.debug("SSE client gone for merge-analysis handle {}", handle);
                try { emitter.complete(); } catch (Exception ignored) {}
            } catch (Exception e) {
                log.warn("SSE tick failed for merge-analysis handle {}", handle, e);
                emitter.completeWithError(e);
            }
        };

        var future = streamScheduler.scheduleAtFixedRate(tick, 0L, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
        emitter.onCompletion(() -> future.cancel(true));
        emitter.onTimeout(() -> future.cancel(true));
        emitter.onError(t -> future.cancel(true));
        return emitter;
    }

    private void sendEvent(SseEmitter emitter, Map<String, Object> payload) {
        try {
            emitter.send(SseEmitter.event().data(payload));
        } catch (IOException e) {
            throw new ClientGoneException(e);
        } catch (IllegalStateException e) {
            throw new ClientGoneException(e);
        }
    }

    private static final class ClientGoneException extends RuntimeException {
        ClientGoneException(Throwable cause) { super(cause); }
    }

    private Map<String, Object> toSseMap(AgentEvent ev) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("seq", ev.getSeq());
        out.put("type", ev.getType() == null ? null : ev.getType().name());
        out.put("payload", parseJsonPayload(ev.getPayload()));
        return out;
    }

    private Map<String, Object> syntheticEvent(long seq, String type, Map<String, Object> data) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("seq", seq);
        out.put("type", type);
        out.put("payload", data);
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonPayload(String payload) {
        if (payload == null || payload.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(payload, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of("raw", payload);
        }
    }
}
