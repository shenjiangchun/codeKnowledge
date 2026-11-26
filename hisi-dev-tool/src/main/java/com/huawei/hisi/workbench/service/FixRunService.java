package com.huawei.hisi.workbench.service;

import com.huawei.hisi.agentflow.AgentFlowCancelRegistry;
import com.huawei.hisi.ram.model.AgentSession;
import com.huawei.hisi.ram.model.SessionStatus;
import com.huawei.hisi.ram.model.SessionType;
import com.huawei.hisi.ram.repository.AgentSessionRepository;
import com.huawei.hisi.ram.service.SessionMappingService;
import com.huawei.hisi.workbench.model.WorkbenchFixRun;
import com.huawei.hisi.workbench.model.WorkbenchItem;
import com.huawei.hisi.workbench.repository.WorkbenchFixRunRepository;
import com.huawei.hisi.workbench.repository.WorkbenchRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 修复运行的状态机服务。运行由 {@link FixRunRunner} 在线程池上推进；
 * 本类负责创建会话与运行、确认/驳回/中止/继续的入口校验，以及后端重启后的状态恢复。
 *
 * <p>确认等待用 {@code CompletableFuture} 传递：runner 停在「方案确认」后
 * {@code awaitConfirm}，确认或驳回时由这里完成它；不存在未来时（例如重启后）
 * 直接把运行标回 RUNNING 并重新提交 runner。
 */
@Slf4j
@Service
public class FixRunService {

    private final WorkbenchRepository workbenchRepository;
    private final WorkbenchFixRunRepository runRepository;
    private final AgentSessionRepository sessionRepository;
    private final SessionMappingService sessionMappingService;
    private final AgentFlowCancelRegistry cancelRegistry;
    private final FixRunRunner runner;
    private final ConfirmGate confirmGate;

    private final int maxConcurrentRuns;
    private final boolean pushEnabled;

    public record ConfirmResult(String optionId, String feedback) {}

    public FixRunService(WorkbenchRepository workbenchRepository,
                         WorkbenchFixRunRepository runRepository,
                         AgentSessionRepository sessionRepository,
                         SessionMappingService sessionMappingService,
                         AgentFlowCancelRegistry cancelRegistry,
                         FixRunRunner runner,
                         ConfirmGate confirmGate,
                         @Value("${workbench.fix.max-concurrent-runs:2}") int maxConcurrentRuns,
                         @Value("${workbench.fix.push-enabled:true}") boolean pushEnabled) {
        this.workbenchRepository = workbenchRepository;
        this.runRepository = runRepository;
        this.sessionRepository = sessionRepository;
        this.sessionMappingService = sessionMappingService;
        this.cancelRegistry = cancelRegistry;
        this.runner = runner;
        this.confirmGate = confirmGate;
        this.maxConcurrentRuns = maxConcurrentRuns;
        this.pushEnabled = pushEnabled;
    }

    /** 后端重启：RUNNING 的运行不再有执行线程，标成 INTERRUPTED 等手动继续。 */
    @PostConstruct
    public void recoverAfterRestart() {
        int interrupted = 0;
        for (WorkbenchFixRun run : runRepository.listActive()) {
            if (WorkbenchFixRun.STATUS_RUNNING.equals(run.getStatus())) {
                run.setStatus(WorkbenchFixRun.STATUS_INTERRUPTED);
                runRepository.save(run);
                interrupted++;
            }
        }
        if (interrupted > 0) {
            log.info("[WorkbenchFix] 重启恢复：{} 个运行标为 INTERRUPTED，等待手动继续", interrupted);
        }
    }

    /** 启动一次自动修复。返回 null 表示校验失败（原因通过 resultCode 表达）。 */
    public StartResult start(String itemKey, boolean pushRequested) {
        WorkbenchItem item = workbenchRepository.findByKey(itemKey);
        if (item == null) {
            return StartResult.error("待办不存在");
        }
        if (runRepository.findActiveByItemKey(itemKey).isPresent()) {
            return StartResult.error("该待办已有正在进行的修复");
        }
        if (runRepository.countActive() >= maxConcurrentRuns) {
            return StartResult.error("同时运行的修复已达上限（" + maxConcurrentRuns + "），请稍后再试");
        }

        AgentSession session = sessionRepository.save(
                AgentSession.newRunning("workbench", SessionType.WORKFLOW));
        long sessionId = session.getId();
        String uuid = UUID.randomUUID().toString();
        session.setUuid(uuid);
        session.setProjectPaths(item.getProjectPath());
        sessionRepository.update(session);
        sessionMappingService.register(uuid, sessionId);

        WorkbenchFixRun run = WorkbenchFixRun.builder()
                .itemKey(itemKey)
                .sessionUuid(uuid)
                .sessionId(sessionId)
                .projectPath(item.getProjectPath())
                .status(WorkbenchFixRun.STATUS_RUNNING)
                .currentStage(null)
                .stageIndex(0)
                .iteration(1)
                .pushRequested(pushRequested && pushEnabled)
                .build();
        runRepository.save(run);

        item.setStatus(WorkbenchItem.STATUS_RUNNING);
        item.setCurrentRunId(run.getId());
        workbenchRepository.updateStatus(item.getId(), item.getStatus(), run.getId());

        log.info("[WorkbenchFix] 启动自动修复 runId={} item={} uuid={}", run.getId(), itemKey, uuid);
        runner.submit(run);
        return StartResult.ok(run);
    }

    public record StartResult(boolean ok, String error, WorkbenchFixRun run) {
        public static StartResult ok(WorkbenchFixRun run) { return new StartResult(true, null, run); }
        public static StartResult error(String error) { return new StartResult(false, error, null); }
    }

    /** 确认方案并继续。 */
    public ConfirmOutcome confirm(long runId, String optionId, boolean pushRequested) {
        WorkbenchFixRun run = runRepository.findById(runId).orElse(null);
        if (run == null) {
            return ConfirmOutcome.error("运行不存在");
        }
        if (!WorkbenchFixRun.STATUS_WAITING_CONFIRM.equals(run.getStatus())) {
            return ConfirmOutcome.error("运行不在等待确认状态（当前：" + run.getStatus() + "）");
        }
        if (optionId == null || optionId.isBlank()) {
            return ConfirmOutcome.error("请选择方案");
        }
        run.setChosenOption(optionId);
        run.setPushRequested(pushRequested && pushEnabled);
        run.setStatus(WorkbenchFixRun.STATUS_RUNNING);
        runRepository.save(run);
        syncItemStatus(run.getItemKey(), WorkbenchItem.STATUS_RUNNING, run.getId());

        if (!confirmGate.complete(runId, new ConfirmResult(optionId, null))) {
            // 重启后恢复：runner 线程不在等待，重新提交（从 stageIndex 继续）
            log.info("[WorkbenchFix] 重启后确认 runId={}，重新提交 runner", runId);
            runner.submit(run);
        }
        return ConfirmOutcome.ok(run);
    }

    /** 驳回方案：带上反馈重新生成方案，之后再次停下等确认。 */
    public ConfirmOutcome reject(long runId, String feedback) {
        WorkbenchFixRun run = runRepository.findById(runId).orElse(null);
        if (run == null) {
            return ConfirmOutcome.error("运行不存在");
        }
        if (!WorkbenchFixRun.STATUS_WAITING_CONFIRM.equals(run.getStatus())) {
            return ConfirmOutcome.error("运行不在等待确认状态（当前：" + run.getStatus() + "）");
        }
        run.setStatus(WorkbenchFixRun.STATUS_RUNNING);
        runRepository.save(run);
        syncItemStatus(run.getItemKey(), WorkbenchItem.STATUS_RUNNING, run.getId());

        if (!confirmGate.complete(runId, new ConfirmResult(null, feedback == null ? "" : feedback))) {
            runner.submit(run);
        }
        return ConfirmOutcome.ok(run);
    }

    /** 中止运行：登记取消标记并结束已登记的子进程；最终状态由 runner 落成 ABORTED。 */
    public boolean abort(long runId) {
        WorkbenchFixRun run = runRepository.findById(runId).orElse(null);
        if (run == null || !run.isActive()) {
            return false;
        }
        cancelRegistry.requestCancel(runId);
        runner.killProcesses(runId);
        // 停在等待确认时没有执行线程在跑：完成等待让 runner 感知取消后收尾
        confirmGate.complete(runId, new ConfirmResult(null, null));
        log.info("[WorkbenchFix] 已请求中止 runId={}", runId);
        return true;
    }

    /** 重启后（INTERRUPTED）或失败后（FAILED 且同迭代可重试）继续。 */
    public ConfirmOutcome resume(long runId) {
        WorkbenchFixRun run = runRepository.findById(runId).orElse(null);
        if (run == null) {
            return ConfirmOutcome.error("运行不存在");
        }
        if (!WorkbenchFixRun.STATUS_INTERRUPTED.equals(run.getStatus())
                && !WorkbenchFixRun.STATUS_FAILED.equals(run.getStatus())) {
            return ConfirmOutcome.error("运行不在可继续的状态（当前：" + run.getStatus() + "）");
        }
        run.setStatus(WorkbenchFixRun.STATUS_RUNNING);
        run.setErrorMessage(null);
        runRepository.save(run);
        syncItemStatus(run.getItemKey(), WorkbenchItem.STATUS_RUNNING, run.getId());
        runner.submit(run);
        return ConfirmOutcome.ok(run);
    }

    public record ConfirmOutcome(boolean ok, String error, WorkbenchFixRun run) {
        public static ConfirmOutcome ok(WorkbenchFixRun run) { return new ConfirmOutcome(true, null, run); }
        public static ConfirmOutcome error(String error) { return new ConfirmOutcome(false, error, null); }
    }

    // ──────────────────────────── 给 runner 用的通道 ────────────────────────────

    /** runner 停在方案确认时调用：阻塞直到确认（返回选项）或驳回（返回反馈）。 */
    FixRunService.ConfirmResult awaitConfirm(long runId, long timeout, TimeUnit unit) {
        return confirmGate.await(runId, timeout, unit);
    }

    /** runner 结束时把待办状态同步回来。 */
    void syncItemStatus(String itemKey, String status, Long runId) {
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
}
