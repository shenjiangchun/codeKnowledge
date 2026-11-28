package com.huawei.hisi.workbench.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.model.ApiResponse;
import com.huawei.hisi.ram.model.AgentEvent;
import com.huawei.hisi.ram.model.EventType;
import com.huawei.hisi.ram.repository.AgentEventRepository;
import com.huawei.hisi.workbench.model.WorkbenchFixRun;
import com.huawei.hisi.workbench.model.WorkbenchItem;
import com.huawei.hisi.workbench.model.WorkbenchStages;
import com.huawei.hisi.workbench.repository.WorkbenchFixRunRepository;
import com.huawei.hisi.workbench.repository.WorkbenchRepository;
import com.huawei.hisi.workbench.service.DebtItemSyncService;
import com.huawei.hisi.workbench.service.FixRunService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作台接口（聚合层）。读接口需要登录（跨项目聚合，见 AdminOnlyInterceptor），写接口需要管理员。
 * 自动修复与运行管理接口随 SDD 运行框架在后续步骤加入。
 */
@Slf4j
@RestController
@RequestMapping("/api/workbench")
@RequiredArgsConstructor
public class WorkbenchController {

    private final WorkbenchRepository workbenchRepository;
    private final WorkbenchFixRunRepository runRepository;
    private final AgentEventRepository eventRepository;
    private final DebtItemSyncService syncService;
    private final FixRunService fixRunService;
    private final ObjectMapper objectMapper;

    /** 清零看板顶部的总览数字与按项目的分布。 */
    @GetMapping("/summary")
    public ApiResponse<Map<String, Object>> summary() {
        try {
            List<WorkbenchItem> items = workbenchRepository.listItems();
            Map<String, Object> summary = new LinkedHashMap<>();
            long open = 0, running = 0, waiting = 0, done = 0, failed = 0, ignored = 0;
            long p0 = 0, p1 = 0, p2 = 0, cleared7d = 0;
            long weekAgo = System.currentTimeMillis() / 1000L - 7L * 24 * 3600;

            Map<String, Map<String, Long>> byProject = new LinkedHashMap<>();
            for (WorkbenchItem item : items) {
                switch (item.getStatus()) {
                    case WorkbenchItem.STATUS_NEW -> { open++; }
                    // 在途修复 = 修复中 + 方案确认中：流程进了确认门待办状态是 WAITING，
                    // 但修复流水线仍在途，看板大数必须仍把它计为在途
                    case WorkbenchItem.STATUS_RUNNING -> { running++; open++; }
                    case WorkbenchItem.STATUS_WAITING -> { running++; waiting++; open++; }
                    case WorkbenchItem.STATUS_DONE -> {
                        done++;
                        if (item.getUpdatedAt() != null && item.getUpdatedAt() >= weekAgo) {
                            cleared7d++;
                        }
                    }
                    case WorkbenchItem.STATUS_FAILED -> failed++;
                    case WorkbenchItem.STATUS_IGNORED -> ignored++;
                    default -> { }
                }
                if (!WorkbenchItem.STATUS_DONE.equals(item.getStatus())
                        && !WorkbenchItem.STATUS_IGNORED.equals(item.getStatus())
                        && !WorkbenchItem.STATUS_FAILED.equals(item.getStatus())) {
                    switch (item.getSeverity() == null ? "P2" : item.getSeverity()) {
                        case "P0" -> p0++;
                        case "P1" -> p1++;
                        default -> p2++;
                    }
                }
                byProject.computeIfAbsent(item.getProjectPath(), k -> new LinkedHashMap<>()).merge("total", 1L, Long::sum);
                if (WorkbenchItem.STATUS_DONE.equals(item.getStatus())) {
                    byProject.get(item.getProjectPath()).merge("done", 1L, Long::sum);
                }
                if (WorkbenchItem.STATUS_NEW.equals(item.getStatus()) || WorkbenchItem.STATUS_RUNNING.equals(item.getStatus())
                        || WorkbenchItem.STATUS_WAITING.equals(item.getStatus())) {
                    byProject.get(item.getProjectPath()).merge("open", 1L, Long::sum);
                }
            }

            summary.put("total", items.size());
            summary.put("open", open);
            summary.put("running", running);
            summary.put("waiting", waiting);
            summary.put("done", done);
            summary.put("failed", failed);
            summary.put("ignored", ignored);
            summary.put("p0", p0);
            summary.put("p1", p1);
            summary.put("p2", p2);
            summary.put("cleared7d", cleared7d);
            summary.put("byProject", byProject);
            return ApiResponse.success(summary);
        } catch (Exception e) {
            log.error("查询工作台总览失败", e);
            return ApiResponse.error("查询工作台总览失败：" + e.getMessage());
        }
    }

    /** 待办列表，含近 7 日出现趋势。 */
    @GetMapping("/items")
    public ApiResponse<List<Map<String, Object>>> items() {
        try {
            List<String> days = lastDays(7);
            List<Map<String, Object>> out = new ArrayList<>();
            for (WorkbenchItem item : workbenchRepository.listItems()) {
                Map<String, Object> view = toView(item);
                view.put("trend", trendOf(item.getItemKey(), days));
                out.add(view);
            }
            return ApiResponse.success(out);
        } catch (Exception e) {
            log.error("查询工作台待办失败", e);
            return ApiResponse.error("查询工作台待办失败：" + e.getMessage());
        }
    }

    /** 手动触发一次聚合（定时同步之外）。 */
    @PostMapping("/items/sync")
    public ApiResponse<Map<String, Object>> sync() {
        try {
            DebtItemSyncService.SyncStats stats = syncService.sync();
            return ApiResponse.success(Map.of(
                    "logItems", stats.logItems(),
                    "archItems", stats.archItems(),
                    "created", stats.changed()));
        } catch (Exception e) {
            log.error("工作台聚合失败", e);
            return ApiResponse.error("工作台聚合失败：" + e.getMessage());
        }
    }

    /** itemKey 含冒号/斜杠/空格，不能放 URL 路径段：操作接口统一用数字 id 定位。 */
    @PostMapping("/items/id/{id}/ignore")
    public ApiResponse<Void> ignore(@PathVariable long id) {
        WorkbenchItem item = workbenchRepository.findById(id);
        if (item == null) {
            return ApiResponse.error(404, "待办不存在");
        }
        return setStatus(item, WorkbenchItem.STATUS_IGNORED);
    }

    @PostMapping("/items/id/{id}/restore")
    public ApiResponse<Void> restore(@PathVariable long id) {
        WorkbenchItem item = workbenchRepository.findById(id);
        if (item == null) {
            return ApiResponse.error(404, "待办不存在");
        }
        return setStatus(item, WorkbenchItem.STATUS_NEW);
    }

    // ──────────────────────────── 自动修复运行 ────────────────────────────

    public record AutofixRequest(List<Long> itemIds, List<String> itemKeys, boolean pushRequested) {}

    @PostMapping("/items/id/{id}/autofix")
    public ApiResponse<Map<String, Object>> autofix(@PathVariable long id,
                                                    @RequestBody(required = false) AutofixRequest request) {
        WorkbenchItem item = workbenchRepository.findById(id);
        if (item == null) {
            return ApiResponse.error(404, "待办不存在");
        }
        boolean push = request != null && request.pushRequested();
        FixRunService.StartResult result = fixRunService.start(item.getItemKey(), push);
        if (!result.ok()) {
            return ApiResponse.error(409, result.error());
        }
        return ApiResponse.success(runView(result.run()));
    }

    @PostMapping("/items/autofix")
    public ApiResponse<Map<String, Object>> autofixBatch(@RequestBody(required = false) AutofixRequest request) {
        if (request == null
                || ((request.itemIds() == null || request.itemIds().isEmpty())
                    && (request.itemKeys() == null || request.itemKeys().isEmpty()))) {
            return ApiResponse.error(400, "itemIds 或 itemKeys 不能为空");
        }
        List<Long> ids = new ArrayList<>();
        if (request.itemIds() != null && !request.itemIds().isEmpty()) {
            ids.addAll(request.itemIds());
        } else {
            for (String itemKey : request.itemKeys()) {
                WorkbenchItem item = workbenchRepository.findByKey(itemKey);
                if (item != null) {
                    ids.add(item.getId());
                } else {
                    ids.add(null);
                }
            }
        }
        List<String> started = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        int index = 0;
        List<String> keys = request.itemKeys();
        for (Long id : ids) {
            String label = keys != null && index < keys.size() ? keys.get(index) : String.valueOf(id);
            index++;
            if (id == null) {
                failed.add(label + ": 待办不存在");
                continue;
            }
            WorkbenchItem item = workbenchRepository.findById(id);
            if (item == null) {
                failed.add(label + ": 待办不存在");
                continue;
            }
            FixRunService.StartResult result = fixRunService.start(item.getItemKey(), request.pushRequested());
            if (result.ok()) {
                started.add(item.getItemKey());
            } else {
                failed.add(label + ": " + result.error());
            }
        }
        return ApiResponse.success(Map.of("started", started, "failed", failed));
    }

    /** 运行列表：进行中的在前，最近 20 条。 */
    @GetMapping("/runs")
    public ApiResponse<List<Map<String, Object>>> runs() {
        try {
            List<Map<String, Object>> out = new ArrayList<>();
            for (WorkbenchFixRun run : runRepository.listAll()) {
                out.add(runView(run));
                if (out.size() >= 20) {
                    break;
                }
            }
            return ApiResponse.success(out);
        } catch (Exception e) {
            log.error("查询修复运行失败", e);
            return ApiResponse.error("查询修复运行失败：" + e.getMessage());
        }
    }

    /** 运行详情：运行行 + 各阶段产物（CHECKPOINT）+ 最近日志（NODE_DELTA 尾部）。 */
    @GetMapping("/runs/{id}")
    public ApiResponse<Map<String, Object>> runDetail(@PathVariable long id) {
        try {
            WorkbenchFixRun run = runRepository.findById(id).orElse(null);
            if (run == null) {
                return ApiResponse.error(404, "运行不存在");
            }
            Map<String, Object> detail = new LinkedHashMap<>(runView(run));
            detail.put("stages", stageDefinitions());
            if (run.getSessionId() != null) {
                List<AgentEvent> events = eventRepository.findBySessionId(run.getSessionId());
                Map<String, Object> checkpoints = new LinkedHashMap<>();
                List<String> logTail = new ArrayList<>();
                for (AgentEvent event : events) {
                    if (event.getType() == EventType.CHECKPOINT) {
                        Map<String, Object> payload = parse(event.getPayload());
                        if (payload != null && payload.get("nodeName") instanceof String node) {
                            checkpoints.put(node, payload.get("output"));
                        }
                    } else if (event.getType() == EventType.NODE_DELTA) {
                        Map<String, Object> payload = parse(event.getPayload());
                        if (payload != null && payload.get("message") instanceof String message) {
                            for (String line : message.split("\n")) {
                                logTail.add(line);
                            }
                        }
                    }
                }
                detail.put("checkpoints", checkpoints);
                detail.put("logTail", logTail.subList(Math.max(0, logTail.size() - 60), logTail.size()));
            }
            return ApiResponse.success(detail);
        } catch (Exception e) {
            log.error("查询修复运行详情失败 (id={})", id, e);
            return ApiResponse.error("查询修复运行详情失败：" + e.getMessage());
        }
    }

    public record ConfirmRequest(String optionId, boolean pushRequested) {}
    public record RejectRequest(String feedback) {}

    @PostMapping("/runs/{id}/confirm")
    public ApiResponse<Map<String, Object>> confirm(@PathVariable long id, @RequestBody ConfirmRequest request) {
        FixRunService.ConfirmOutcome outcome = fixRunService.confirm(id,
                request == null ? null : request.optionId(), request != null && request.pushRequested());
        if (!outcome.ok()) {
            return ApiResponse.error(409, outcome.error());
        }
        return ApiResponse.success(runView(outcome.run()));
    }

    @PostMapping("/runs/{id}/reject")
    public ApiResponse<Map<String, Object>> reject(@PathVariable long id, @RequestBody RejectRequest request) {
        FixRunService.ConfirmOutcome outcome = fixRunService.reject(id, request == null ? "" : request.feedback());
        if (!outcome.ok()) {
            return ApiResponse.error(409, outcome.error());
        }
        return ApiResponse.success(runView(outcome.run()));
    }

    @PostMapping("/runs/{id}/abort")
    public ApiResponse<Void> abort(@PathVariable long id) {
        if (!fixRunService.abort(id)) {
            return ApiResponse.error(409, "运行不在可中止状态");
        }
        return ApiResponse.success(null);
    }

    @PostMapping("/runs/{id}/resume")
    public ApiResponse<Map<String, Object>> resume(@PathVariable long id) {
        FixRunService.ConfirmOutcome outcome = fixRunService.resume(id);
        if (!outcome.ok()) {
            return ApiResponse.error(409, outcome.error());
        }
        return ApiResponse.success(runView(outcome.run()));
    }

    private ApiResponse<Void> setStatus(WorkbenchItem item, String status) {
        item.setStatus(status);
        workbenchRepository.updateStatus(item.getId(), status, item.getCurrentRunId());
        return ApiResponse.success(null);
    }

    // ──────────────────────────── 视图构建 ────────────────────────────

    private Map<String, Object> toView(WorkbenchItem item) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", item.getId());
        view.put("itemKey", item.getItemKey());
        view.put("source", item.getSource());
        view.put("projectPath", item.getProjectPath());
        view.put("title", item.getTitle());
        view.put("location", item.getLocation());
        view.put("severity", item.getSeverity());
        view.put("status", item.getStatus());
        view.put("confidence", item.getConfidence());
        view.put("chainStatus", item.getChainStatus());
        view.put("why", item.getWhy());
        view.put("suggestion", item.getSuggestion());
        view.put("evidenceJson", item.getEvidenceJson());
        view.put("sourceRefJson", item.getSourceRefJson());
        view.put("occurrenceCount", item.getOccurrenceCount());
        view.put("firstSeenAt", item.getFirstSeenAt());
        view.put("lastSeenAt", item.getLastSeenAt());
        view.put("currentRunId", item.getCurrentRunId());
        return view;
    }

    private List<String> lastDays(int n) {
        List<String> days = new ArrayList<>();
        for (int i = n - 1; i >= 0; i--) {
            days.add(LocalDate.now().minusDays(i).toString());
        }
        return days;
    }

    private List<Integer> trendOf(String itemKey, List<String> days) {
        java.util.Map<String, Integer> counts = workbenchRepository.listDayCounts(itemKey, days);
        List<Integer> trend = new ArrayList<>();
        for (String day : days) {
            trend.add(counts.getOrDefault(day, 0));
        }
        return trend;
    }

    private Map<String, Object> runView(WorkbenchFixRun run) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", run.getId());
        view.put("itemKey", run.getItemKey());
        view.put("sessionUuid", run.getSessionUuid());
        view.put("projectPath", run.getProjectPath());
        view.put("status", run.getStatus());
        view.put("currentStage", run.getCurrentStage());
        view.put("stageIndex", run.getStageIndex());
        view.put("iteration", run.getIteration());
        view.put("chosenOption", run.getChosenOption());
        view.put("pushRequested", run.isPushRequested());
        view.put("worktreePath", run.getWorktreePath());
        view.put("branchName", run.getBranchName());
        view.put("commitHash", run.getCommitHash());
        view.put("pushed", run.isPushed());
        view.put("errorMessage", run.getErrorMessage());
        view.put("createdAt", run.getCreatedAt());
        view.put("updatedAt", run.getUpdatedAt());
        return view;
    }

    private List<Map<String, Object>> stageDefinitions() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < WorkbenchStages.ALL.size(); i++) {
            WorkbenchStages.Stage stage = WorkbenchStages.of(i);
            out.add(Map.of("index", i, "key", stage.key(), "label", stage.label(), "hint", stage.hint()));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return null;
        }
    }
}
