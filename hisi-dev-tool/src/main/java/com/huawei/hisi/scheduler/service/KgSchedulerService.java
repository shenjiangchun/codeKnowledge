package com.huawei.hisi.scheduler.service;

import com.huawei.hisi.knowledgegraph.service.IncrementalRefreshService;
import com.huawei.hisi.scheduler.model.KgSchedule;
import com.huawei.hisi.scheduler.repository.KgScheduleRepository;
import com.huawei.hisi.service.KnowledgeGraphTaskService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

@Service
@Slf4j
public class KgSchedulerService {

    private final KgScheduleRepository repository;
    private final ThreadPoolTaskScheduler taskScheduler;
    private final KnowledgeGraphTaskService knowledgeGraphTaskService;
    private final IncrementalRefreshService incrementalRefreshService;
    private final Map<Long, ScheduledFuture<?>> activeTasks = new ConcurrentHashMap<>();

    public KgSchedulerService(KgScheduleRepository repository,
                              KnowledgeGraphTaskService knowledgeGraphTaskService,
                              IncrementalRefreshService incrementalRefreshService) {
        this.repository = repository;
        this.knowledgeGraphTaskService = knowledgeGraphTaskService;
        this.incrementalRefreshService = incrementalRefreshService;
        this.taskScheduler = new ThreadPoolTaskScheduler();
        this.taskScheduler.setPoolSize(2);
        this.taskScheduler.setThreadNamePrefix("kg-scheduler-");
        this.taskScheduler.initialize();
    }

    @PostConstruct
    public void initSchedules() {
        List<KgSchedule> enabled = repository.findEnabled();
        for (KgSchedule schedule : enabled) {
            registerTask(schedule);
        }
        log.info("[KgScheduler] Initialized {} scheduled tasks", enabled.size());
    }

    public void registerTask(KgSchedule schedule) {
        try {
            CronTrigger trigger = new CronTrigger(schedule.getCronExpression());
            ScheduledFuture<?> future = taskScheduler.schedule(() -> executeTask(schedule), trigger);
            activeTasks.put(schedule.getId(), future);
            log.info("[KgScheduler] Registered task {} for project {} with cron {}",
                schedule.getId(), schedule.getProjectPath(), schedule.getCronExpression());
        } catch (Exception e) {
            log.error("[KgScheduler] Failed to register task {}", schedule.getId(), e);
        }
    }

    public void cancelTask(long scheduleId) {
        ScheduledFuture<?> future = activeTasks.remove(scheduleId);
        if (future != null) {
            future.cancel(false);
            log.info("[KgScheduler] Cancelled task {}", scheduleId);
        }
    }

    public void reRegisterTask(KgSchedule schedule) {
        cancelTask(schedule.getId());
        if (schedule.isEnabled()) {
            registerTask(schedule);
        }
    }

    private void executeTask(KgSchedule schedule) {
        log.info("[KgScheduler] Executing {} KG task for {}", schedule.getTaskType(), schedule.getProjectPath());
        try {
            if ("FULL".equals(schedule.getTaskType())) {
                knowledgeGraphTaskService.startTask(schedule.getProjectPath(), Collections.emptyList());
            } else if ("INCREMENTAL".equals(schedule.getTaskType())) {
                incrementalRefreshService.refresh(schedule.getProjectPath());
            } else {
                log.warn("[KgScheduler] Unknown task type: {}", schedule.getTaskType());
            }
            long now = System.currentTimeMillis() / 1000;
            repository.updateLastRunAt(schedule.getId(), now);
            log.info("[KgScheduler] Completed {} KG task for {}", schedule.getTaskType(), schedule.getProjectPath());
        } catch (Exception e) {
            log.error("[KgScheduler] Failed {} KG task for {}", schedule.getTaskType(), schedule.getProjectPath(), e);
        }
    }
}
