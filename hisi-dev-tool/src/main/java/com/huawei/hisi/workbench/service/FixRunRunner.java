package com.huawei.hisi.workbench.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.agentflow.AgentFlowCancelRegistry;
import com.huawei.hisi.agentflow.AgentFlowCancelledException;
import com.huawei.hisi.ram.model.AgentEvent;
import com.huawei.hisi.ram.model.EventType;
import com.huawei.hisi.ram.model.SessionStatus;
import com.huawei.hisi.ram.repository.AgentEventRepository;
import com.huawei.hisi.ram.repository.AgentSessionRepository;
import com.huawei.hisi.workbench.model.WorkbenchFixRun;
import com.huawei.hisi.workbench.model.WorkbenchItem;
import com.huawei.hisi.workbench.model.WorkbenchStages;
import com.huawei.hisi.workbench.repository.WorkbenchFixRunRepository;
import com.huawei.hisi.workbench.repository.WorkbenchRepository;
import com.huawei.hisi.workbench.service.stage.StageHandler;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 修复运行的执行循环：按 {@code WorkbenchStages} 顺序推进，处理方案确认的等待/驳回、
 * 回归与 E2E 失败回到开发的迭代回路、取消与失败收尾。
 *
 * <p>每次运行在专用线程池上执行，并用 {@link AgentFlowCancelRegistry#callBound} 绑定，
 * 阶段之间的取消检查与中止接口共享同一个标记。
 *
 * <p>执行线程里维护一份「阶段键 → 产物」的全表：阶段按名取输入，不依赖上一个阶段是什么。
 * 重新进入运行（重启后继续、失败后重试）时，这份表从已落库的 CHECKPOINT 事件恢复。
 */
@Slf4j
@Component
public class FixRunRunner {

    private static final int CONFIRM_WAIT_MINUTES = 60;

    private final WorkbenchRepository workbenchRepository;
    private final WorkbenchFixRunRepository runRepository;
    private final AgentSessionRepository sessionRepository;
    private final AgentEventRepository eventRepository;
    private final AgentFlowCancelRegistry cancelRegistry;
    private final WorkbenchEventSink events;
    private final ConfirmGate confirmGate;
    private final Map<String, StageHandler> handlers;
    private final int maxIterations;
    private final ObjectMapper objectMapper;

    /** runId → 阶段登记的子进程（中止时整棵树结束） */
    private final ConcurrentHashMap<Long, List<ProcessHandle>> processes = new ConcurrentHashMap<>();

    private final ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
        Thread thread = new Thread(r, "fix-run");
        thread.setDaemon(true);
        return thread;
    });

    public FixRunRunner(WorkbenchRepository workbenchRepository,
                        WorkbenchFixRunRepository runRepository,
                        AgentSessionRepository sessionRepository,
                        AgentEventRepository eventRepository,
                        AgentFlowCancelRegistry cancelRegistry,
                        WorkbenchEventSink events,
                        ConfirmGate confirmGate,
                        List<StageHandler> stageHandlers,
                        ObjectMapper objectMapper,
                        @Value("${workbench.fix.max-iterations:3}") int maxIterations) {
        this.workbenchRepository = workbenchRepository;
        this.runRepository = runRepository;
        this.sessionRepository = sessionRepository;
        this.eventRepository = eventRepository;
        this.cancelRegistry = cancelRegistry;
        this.events = events;
        this.confirmGate = confirmGate;
        this.objectMapper = objectMapper;
        this.maxIterations = maxIterations;
        this.handlers = new HashMap<>();
        for (StageHandler handler : stageHandlers) {
            this.handlers.put(handler.key(), handler);
        }
    }

    public void submit(WorkbenchFixRun run) {
        pool.submit(() -> {
            try {
                cancelRegistry.callBound(run.getId(), () -> {
                    execute(run);
                    return null;
                });
            } catch (Exception e) {
                log.error("[WorkbenchFix] runId={} 执行异常", run.getId(), e);
            }
        });
    }

    public void killProcesses(long runId) {
        List<ProcessHandle> handles = processes.remove(runId);
        if (handles == null) {
            return;
        }
        for (ProcessHandle handle : handles) {
            handle.descendants().forEach(ProcessHandle::destroyForcibly);
            handle.destroyForcibly();
        }
    }

    /** 阶段登记子进程，中止时由 {@link #killProcesses} 结束。 */
    public void registerProcess(long runId, Process process) {
        processes.computeIfAbsent(runId, k -> new java.util.concurrent.CopyOnWriteArrayList<>())
                .add(process.toHandle());
    }

    private void execute(WorkbenchFixRun run) {
        WorkbenchItem item = workbenchRepository.findByKey(run.getItemKey());
        if (item == null) {
            finish(run, WorkbenchFixRun.STATUS_FAILED, "待办不存在");
            return;
        }
        // 全部阶段产物：运行中途接手时从事件表恢复，否则是空表
        Map<String, Object> artifacts = run.getSessionId() == null ? new HashMap<>()
                : artifactsOf(eventRepository.findBySessionId(run.getSessionId()), objectMapper);
        try {
            int index = run.getStageIndex();
            String rejectFeedback = null;
            while (index < WorkbenchStages.ALL.size()) {
                if (cancelRegistry.isCurrentCancelled()) {
                    throw new AgentFlowCancelledException();
                }
                WorkbenchStages.Stage stage = WorkbenchStages.of(index);
                StageHandler handler = handlers.get(stage.key());
                if (handler == null) {
                    finish(run, WorkbenchFixRun.STATUS_FAILED, "阶段未实现：" + stage.key());
                    return;
                }
                run.setCurrentStage(stage.key());
                run.setStageIndex(index);
                runRepository.save(run);

                events.nodeStart(run.getSessionId(), stage.key());
                StageContext ctx = new StageContext(run, item, events, artifacts, rejectFeedback);
                StageHandler.StageOutcome outcome;
                try {
                    outcome = handler.execute(ctx);
                } catch (AgentFlowCancelledException cancelled) {
                    throw cancelled;
                } catch (Exception e) {
                    log.error("[WorkbenchFix] runId={} 阶段 {} 异常", run.getId(), stage.key(), e);
                    outcome = StageHandler.StageOutcome.failed("阶段异常：" + e.getMessage());
                }
                events.flush(run.getSessionId());

                if (outcome == null) {
                    finish(run, WorkbenchFixRun.STATUS_FAILED, "阶段无结果：" + stage.key());
                    return;
                }
                switch (outcome.kind()) {
                    case StageHandler.StageOutcome.FAILED -> {
                        events.error(run.getSessionId(), stage.key(), outcome.message());
                        finish(run, WorkbenchFixRun.STATUS_FAILED, outcome.message());
                        return;
                    }
                    case StageHandler.StageOutcome.WAIT -> {
                        events.checkpoint(run.getSessionId(), stage.key(), outcome.output() == null ? Map.of() : outcome.output());
                        events.nodeComplete(run.getSessionId(), stage.key(), Map.of());
                        FixRunService.ConfirmResult result = pauseForConfirm(run, item);
                        if (result == null) {
                            // 等待超时：保持 WAITING_CONFIRM，线程退出；用户确认时会重新提交 runner
                            return;
                        }
                        if (cancelRegistry.isCurrentCancelled()) {
                            throw new AgentFlowCancelledException();
                        }
                        if (result.optionId() != null) {
                            // 确认：带着产物进入下一阶段；重读 DB 行拿到 confirm 写入的选项与推送选择
                            run = runRepository.findById(run.getId()).orElse(run);
                            rejectFeedback = null;
                            remember(artifacts, stage.key(), outcome.output());
                            index++;
                        } else {
                            // 驳回：带反馈重跑本阶段，之后会再次停下
                            run = runRepository.findById(run.getId()).orElse(run);
                            rejectFeedback = result.feedback() == null ? "" : result.feedback();
                            run.setStatus(WorkbenchFixRun.STATUS_RUNNING);
                        }
                        continue;
                    }
                    case StageHandler.StageOutcome.RETRY -> {
                        events.checkpoint(run.getSessionId(), stage.key(), outcome.output() == null ? Map.of() : outcome.output());
                        remember(artifacts, stage.key(), outcome.output());
                        if (run.getIteration() >= maxIterations) {
                            events.error(run.getSessionId(), stage.key(),
                                    "已达迭代上限（" + maxIterations + "）：" + outcome.message());
                            finish(run, WorkbenchFixRun.STATUS_FAILED, outcome.message());
                            return;
                        }
                        run.setIteration(run.getIteration() + 1);
                        log.info("[WorkbenchFix] runId={} 第 {} 轮：{} 未通过，回到开发（{}）",
                                run.getId(), run.getIteration(), stage.key(), outcome.message());
                        events.log(run.getSessionId(), stage.key(),
                                "未通过（" + outcome.message() + "），回到迭代开发（第 " + run.getIteration() + " 轮）");
                        events.flush(run.getSessionId());
                        // 回到开发时把失败详情带上：开发阶段据此知道上一轮哪里没过
                        artifacts.put("lastFailure", Map.of("stage", stage.key(), "message", outcome.message()));
                        index = WorkbenchStages.indexOf("develop");
                        continue;
                    }
                    default -> {
                        events.checkpoint(run.getSessionId(), stage.key(), outcome.output() == null ? Map.of() : outcome.output());
                        events.nodeComplete(run.getSessionId(), stage.key(), Map.of());
                        remember(artifacts, stage.key(), outcome.output());
                        index++;
                    }
                }
                run.setStageIndex(index);
                runRepository.save(run);
            }
            finish(run, WorkbenchFixRun.STATUS_DONE, null);
        } catch (AgentFlowCancelledException cancelled) {
            log.info("[WorkbenchFix] runId={} CANCELLED by user", run.getId());
            events.flush(run.getSessionId());
            sessionRepository.updateStatus(run.getSessionId(), SessionStatus.ABORTED);
            finish(run, WorkbenchFixRun.STATUS_ABORTED, null);
        } catch (Exception e) {
            log.error("[WorkbenchFix] runId={} 运行异常", run.getId(), e);
            events.flush(run.getSessionId());
            finish(run, WorkbenchFixRun.STATUS_FAILED, "运行异常：" + e.getMessage());
        } finally {
            cancelRegistry.clear(run.getId());
            processes.remove(run.getId());
        }
    }

    /** 停在方案确认：阻塞等确认/驳回。返回 null 表示等待超时（保持 WAITING_CONFIRM，线程退出）。 */
    private FixRunService.ConfirmResult pauseForConfirm(WorkbenchFixRun run, WorkbenchItem item) {
        run.setStatus(WorkbenchFixRun.STATUS_WAITING_CONFIRM);
        runRepository.save(run);
        syncItemStatus(run.getItemKey(), WorkbenchItem.STATUS_WAITING, run.getId());
        sessionRepository.updateStatus(run.getSessionId(), SessionStatus.WAITING_HITL);
        log.info("[WorkbenchFix] runId={} 方案就绪，等待确认", run.getId());
        return confirmGate.await(run.getId(), CONFIRM_WAIT_MINUTES, TimeUnit.MINUTES);
    }

    private static void remember(Map<String, Object> artifacts, String stageKey, Map<String, Object> output) {
        if (output != null) {
            artifacts.put(stageKey, output);
        }
    }

    /**
     * 从会话事件里恢复各阶段产物：同一阶段多次产出时取最后一次（驳回重出、回到开发之后的那版）。
     * 事件按 seq 升序返回，后面的覆盖前面的即可。
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> artifactsOf(List<AgentEvent> events, ObjectMapper objectMapper) {
        Map<String, Object> artifacts = new HashMap<>();
        for (AgentEvent event : events) {
            if (event.getType() != EventType.CHECKPOINT || event.getPayload() == null) {
                continue;
            }
            try {
                Map<?, ?> payload = objectMapper.readValue(event.getPayload(), Map.class);
                if (payload.get("nodeName") instanceof String node && payload.get("output") instanceof Map<?, ?> output) {
                    artifacts.put(node, new LinkedHashMap<>((Map<String, Object>) output));
                }
            } catch (Exception e) {
                log.debug("[WorkbenchFix] 无法解析的 CHECKPOINT 事件 id={}: {}", event.getId(), e.getMessage());
            }
        }
        return artifacts;
    }

    private void syncItemStatus(String itemKey, String status, Long runId) {
        WorkbenchItem item = workbenchRepository.findByKey(itemKey);
        if (item == null) {
            return;
        }
        item.setStatus(status);
        if (runId != null) {
            item.setCurrentRunId(runId);
        }
        workbenchRepository.updateStatus(item.getId(), status, item.getCurrentRunId());
    }

    private void finish(WorkbenchFixRun run, String status, String errorMessage) {
        run.setStatus(status);
        run.setErrorMessage(errorMessage);
        runRepository.save(run);
        String itemStatus = switch (status) {
            case WorkbenchFixRun.STATUS_DONE -> WorkbenchItem.STATUS_DONE;
            case WorkbenchFixRun.STATUS_ABORTED -> WorkbenchItem.STATUS_NEW;
            default -> WorkbenchItem.STATUS_FAILED;
        };
        fixRunServiceSyncItem(run.getItemKey(), itemStatus, run.getId());
        if (run.getSessionId() != null) {
            sessionRepository.updateStatus(run.getSessionId(), switch (status) {
                case WorkbenchFixRun.STATUS_DONE -> SessionStatus.DONE;
                case WorkbenchFixRun.STATUS_ABORTED -> SessionStatus.ABORTED;
                default -> SessionStatus.FAILED;
            });
        }
        log.info("[WorkbenchFix] runId={} 结束: status={} error={}", run.getId(), status, errorMessage);
    }

    private void fixRunServiceSyncItem(String itemKey, String status, Long runId) {
        syncItemStatus(itemKey, status, runId);
    }

    @PreDestroy
    public void shutdown() {
        pool.shutdownNow();
    }
}
