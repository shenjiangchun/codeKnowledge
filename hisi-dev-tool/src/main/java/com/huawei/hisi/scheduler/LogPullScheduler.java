package com.huawei.hisi.scheduler;

import com.huawei.hisi.model.AppLogConfig;
import com.huawei.hisi.repository.AppLogConfigRepository;
import com.huawei.hisi.service.LogAnalysisExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 日志拉取定时任务调度器
 * 每10分钟检查启用的应用配置并拉取错误日志
 *
 * Task 6: Scheduled task for periodic log pulling
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LogPullScheduler {

    private final AppLogConfigRepository configRepository;
    private final LogAnalysisExecutor logAnalysisExecutor;

    // Note: Actual log cloud service integration would require external API
    // This is a placeholder for the scheduler structure

    /**
     * 定时拉取所有启用的应用日志
     * 每10分钟执行一次
     */
    @Scheduled(fixedRate = 600000) // 10 minutes
    public void pullLogsForAllApps() {
        log.info("[LogPullScheduler] 开始定时日志拉取...");

        List<AppLogConfig> configs = configRepository.findAllActive();
        if (configs.isEmpty()) {
            log.debug("[LogPullScheduler] 无启用的配置");
            return;
        }

        for (AppLogConfig config : configs) {
            try {
                pullLogsForApp(config);
                configRepository.updateLastPullAt(config.getAppId());
                log.info("[LogPullScheduler] 应用 {} 日志拉取完成", config.getAppId());
            } catch (Exception e) {
                log.error("[LogPullScheduler] 应用 {} 日志拉取失败: {}", config.getAppId(), e.getMessage());
            }
        }
    }

    /**
     * 为单个应用拉取日志
     *
     * Note: 实际实现需要集成ES/日志云API
     * 当前为骨架实现，后续通过MCP log_query工具实现
     */
    public void pullLogsForApp(AppLogConfig config) {
        log.debug("[LogPullScheduler] 拉取应用 {} 日志 (dslQuery={})", config.getAppId(), config.getDslQuery());

        // Placeholder: 实际日志拉取通过MCP log_query工具或外部API
        // 拉取后调用 logAnalysisExecutor.submitForAnalysis() 入库

        // Example flow:
        // 1. Query logs via external API using DSL
        // 2. For each log entry:
        //    logAnalysisExecutor.submitForAnalysis(message, stackTrace, appId, null);

        log.info("[LogPullScheduler] 骨架实现 - 需要集成外部日志API (appId={})", config.getAppId());
    }
}