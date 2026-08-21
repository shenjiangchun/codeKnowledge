package com.huawei.hisi.scheduler.controller;

import com.huawei.hisi.model.ApiResponse;
import com.huawei.hisi.scheduler.model.KgSchedule;
import com.huawei.hisi.scheduler.repository.KgScheduleRepository;
import com.huawei.hisi.scheduler.service.KgSchedulerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/kg-schedules")
@RequiredArgsConstructor
@Slf4j
public class KgScheduleController {

    private final KgScheduleRepository repository;
    private final KgSchedulerService schedulerService;

    public record CreateRequest(String projectPath, String cronExpression, String buildMode,
                                Boolean gitPullEnabled, String branch,
                                Boolean refreshDescription, Boolean refreshArchitecture) {}
    public record UpdateRequest(String projectPath, String cronExpression, String buildMode, Boolean enabled,
                                Boolean gitPullEnabled, String branch,
                                Boolean refreshDescription, Boolean refreshArchitecture) {}
    public record ScheduleResponse(Long id, String projectPath, String cronExpression, String buildMode,
                                   boolean enabled, boolean gitPullEnabled, String branch,
                                   boolean refreshDescription, boolean refreshArchitecture,
                                   Long lastRunAt, Long nextRunAt) {}

    @GetMapping
    public ApiResponse<List<ScheduleResponse>> list() {
        try {
            List<ScheduleResponse> schedules = repository.findAll().stream()
                .map(this::toResponse)
                .toList();
            return ApiResponse.success(schedules);
        } catch (Exception e) {
            log.error("[KgSchedule] Failed to list schedules", e);
            return ApiResponse.error("Failed to list schedules");
        }
    }

    @PostMapping
    public ApiResponse<ScheduleResponse> create(@RequestBody CreateRequest request) {
        try {
            validateCreate(request);
            KgSchedule schedule = KgSchedule.builder()
                .projectPath(request.projectPath())
                .cronExpression(request.cronExpression())
                .buildMode(request.buildMode())
                .enabled(true)
                .gitPullEnabled(Boolean.TRUE.equals(request.gitPullEnabled()))
                .branch(request.branch() != null ? request.branch() : "")
                .refreshDescription(Boolean.TRUE.equals(request.refreshDescription()))
                .refreshArchitecture(Boolean.TRUE.equals(request.refreshArchitecture()))
                .build();
            KgSchedule saved = repository.insert(schedule);
            schedulerService.registerTask(saved);
            log.info("[KgSchedule] Created schedule id={} for project {}", saved.getId(), saved.getProjectPath());
            return ApiResponse.success(toResponse(saved));
        } catch (IllegalArgumentException e) {
            log.warn("[KgSchedule] Create rejected: {}", e.getMessage());
            return ApiResponse.error(400, e.getMessage());
        } catch (Exception e) {
            log.error("[KgSchedule] Failed to create schedule", e);
            return ApiResponse.error("Failed to create schedule");
        }
    }

    @PutMapping("/{id}")
    public ApiResponse<ScheduleResponse> update(@PathVariable Long id, @RequestBody UpdateRequest request) {
        try {
            KgSchedule existing = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Schedule not found: id=" + id));

            if (request.projectPath() != null) existing.setProjectPath(request.projectPath());
            if (request.cronExpression() != null) existing.setCronExpression(request.cronExpression());
            if (request.buildMode() != null) existing.setBuildMode(request.buildMode());
            if (request.enabled() != null) existing.setEnabled(request.enabled());
            if (request.gitPullEnabled() != null) existing.setGitPullEnabled(request.gitPullEnabled());
            if (request.branch() != null) existing.setBranch(request.branch());
            if (request.refreshDescription() != null) existing.setRefreshDescription(request.refreshDescription());
            if (request.refreshArchitecture() != null) existing.setRefreshArchitecture(request.refreshArchitecture());

            repository.update(existing);
            schedulerService.reRegisterTask(existing);
            log.info("[KgSchedule] Updated schedule id={}", id);
            return ApiResponse.success(toResponse(existing));
        } catch (IllegalArgumentException e) {
            log.warn("[KgSchedule] Update rejected: {}", e.getMessage());
            return ApiResponse.error(400, e.getMessage());
        } catch (Exception e) {
            log.error("[KgSchedule] Failed to update schedule {}", id, e);
            return ApiResponse.error("Failed to update schedule");
        }
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Map<String, Object>> delete(@PathVariable Long id) {
        try {
            schedulerService.cancelTask(id);
            int rows = repository.deleteById(id);
            if (rows == 0) {
                return ApiResponse.error(404, "Schedule not found: id=" + id);
            }
            log.info("[KgSchedule] Deleted schedule id={}", id);
            return ApiResponse.success(Map.of("id", id, "deleted", true));
        } catch (Exception e) {
            log.error("[KgSchedule] Failed to delete schedule {}", id, e);
            return ApiResponse.error("Failed to delete schedule");
        }
    }

    private ScheduleResponse toResponse(KgSchedule s) {
        return new ScheduleResponse(s.getId(), s.getProjectPath(), s.getCronExpression(),
            s.getBuildMode(), s.isEnabled(), s.isGitPullEnabled(), s.getBranch(),
            s.isRefreshDescription(), s.isRefreshArchitecture(), s.getLastRunAt(), s.getNextRunAt());
    }

    private void validateCreate(CreateRequest request) {
        if (request.projectPath() == null || request.projectPath().isBlank()) {
            throw new IllegalArgumentException("Project path is required");
        }
        if (request.cronExpression() == null || request.cronExpression().isBlank()) {
            throw new IllegalArgumentException("Cron expression is required");
        }
        if (request.buildMode() == null || request.buildMode().isBlank()) {
            throw new IllegalArgumentException("Build mode is required");
        }
        if (!"INCREMENTAL".equals(request.buildMode()) && !"REUSE".equals(request.buildMode())
                && !"WIPE".equals(request.buildMode())) {
            throw new IllegalArgumentException("Build mode must be INCREMENTAL, REUSE or WIPE");
        }
    }
}
