package com.huawei.hisi.scheduler.service;

import com.huawei.hisi.knowledgegraph.service.BuildMode;
import com.huawei.hisi.knowledgegraph.service.IncrementalKnowledgeGraphBuilder;
import com.huawei.hisi.scheduler.model.KgSchedule;
import com.huawei.hisi.scheduler.repository.KgScheduleRepository;
import com.huawei.hisi.service.KnowledgeGraphTaskService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.ArrayList;
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
    private final IncrementalKnowledgeGraphBuilder incrementalBuilder;
    private final Map<Long, ScheduledFuture<?>> activeTasks = new ConcurrentHashMap<>();

    public KgSchedulerService(KgScheduleRepository repository,
                              KnowledgeGraphTaskService knowledgeGraphTaskService,
                              IncrementalKnowledgeGraphBuilder incrementalBuilder) {
        this.repository = repository;
        this.knowledgeGraphTaskService = knowledgeGraphTaskService;
        this.incrementalBuilder = incrementalBuilder;
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
        log.info("[KgScheduler] Executing {} KG task for {}", schedule.getBuildMode(), schedule.getProjectPath());
        try {
            if (schedule.isGitPullEnabled()) {
                gitPull(schedule);
            }
            BuildMode buildMode = BuildMode.fromString(schedule.getBuildMode());
            if (BuildMode.INCREMENTAL.equals(buildMode)) {
                incrementalBuilder.incrementalRefresh(schedule.getProjectPath(),
                        schedule.isRefreshDescription(), schedule.isRefreshArchitecture());
            } else {
                // REUSE / WIPE：走全量生成，refreshDescription→generateVector，refreshArchitecture→generateArchitecture
                knowledgeGraphTaskService.startTask(schedule.getProjectPath(), Collections.emptyList(),
                        schedule.isRefreshDescription(), schedule.isRefreshArchitecture(), buildMode);
            }
            long now = System.currentTimeMillis() / 1000;
            repository.updateLastRunAt(schedule.getId(), now);
            log.info("[KgScheduler] Completed {} KG task for {}", schedule.getBuildMode(), schedule.getProjectPath());
        } catch (Exception e) {
            log.error("[KgScheduler] Failed {} KG task for {}", schedule.getBuildMode(), schedule.getProjectPath(), e);
        }
    }

    /** git pull 拉取最新代码（branch 非空用 git pull origin <branch>，否则跟随当前分支） */
    private void gitPull(KgSchedule schedule) {
        File dir = new File(schedule.getProjectPath());
        if (!new File(dir, ".git").exists()) {
            log.warn("[KgScheduler] 非 Git 仓库，跳过 git pull: {}", schedule.getProjectPath());
            return;
        }
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.add("pull");
        if (schedule.getBranch() != null && !schedule.getBranch().isBlank()) {
            cmd.add("origin");
            cmd.add(schedule.getBranch());
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(dir);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            int exit = p.waitFor();
            if (exit == 0) {
                log.info("[KgScheduler] git pull 成功: {}", schedule.getProjectPath());
            } else {
                log.warn("[KgScheduler] git pull 失败（exit={}）: {}", exit, schedule.getProjectPath());
            }
        } catch (Exception e) {
            log.warn("[KgScheduler] git pull 异常（跳过，不中断增量）: {}", e.getMessage());
        }
    }
}
