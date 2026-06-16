package com.huawei.hisi.scheduler;

import com.huawei.hisi.model.AppLogConfig;
import com.huawei.hisi.model.LogEntry;
import com.huawei.hisi.model.LogQueryDto;
import com.huawei.hisi.repository.AppLogConfigRepository;
import com.huawei.hisi.service.LogAnalysisExecutor;
import com.huawei.hisi.service.LogCloudService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 日志拉取定时任务调度器
 * 每10分钟检查启用的应用配置并拉取错误日志
 *
 * Task 6: Scheduled task for periodic log pulling
 *
 * 启用条件：配置 log.pull.enabled=true
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "log.pull.enabled", havingValue = "true", matchIfMissing = false)
public class LogPullScheduler {

    private final AppLogConfigRepository configRepository;
    private final LogAnalysisExecutor logAnalysisExecutor;
    private final LogCloudService logCloudService;

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

        int totalLogs = 0;
        for (AppLogConfig config : configs) {
            try {
                int pulledCount = pullLogsForApp(config);
                totalLogs += pulledCount;
                configRepository.updateLastPullAt(config.getAppId());
                log.info("[LogPullScheduler] 应用 {} 拉取完成，入库 {} 条", config.getAppId(), pulledCount);
            } catch (Exception e) {
                log.error("[LogPullScheduler] 应用 {} 日志拉取失败: {}", config.getAppId(), e.getMessage());
            }
        }

        log.info("[LogPullScheduler] 定时拉取完成，共入库 {} 条日志", totalLogs);
    }

    /**
     * 为单个应用拉取日志并入库
     *
     * 流程：
     * 1. 构建查询参数（使用配置的 DSL 或默认错误日志查询）
     * 2. 调用 LogCloudService 查询日志
     * 3. 对每条日志调用 submitForAnalysis 入库（带指纹去重）
     *
     * @param config 应用日志配置
     * @return 入库日志数量
     */
    public int pullLogsForApp(AppLogConfig config) {
        log.debug("[LogPullScheduler] 拉取应用 {} 日志 (dslQuery={})", config.getAppId(), config.getDslQuery());

        // 1. 构建查询参数
        LogQueryDto query = buildQueryFromConfig(config);

        // 2. 查询日志
        List<LogEntry> logs = logCloudService.queryLogs(query);
        log.debug("[LogPullScheduler] 查询到 {} 条日志 (appId={})", logs.size(), config.getAppId());

        // 3. 入库（带指纹去重）
        int storedCount = 0;
        for (LogEntry entry : logs) {
            if (entry.getMessage() == null || entry.getMessage().isEmpty()) {
                continue;
            }

            // 构建查询参数元数据
            Map<String, Object> queryParams = new HashMap<>();
            queryParams.put("appId", config.getAppId());
            queryParams.put("projectPath", config.getProjectPath());
            queryParams.put("dslQuery", config.getDslQuery());
            queryParams.put("pullTime", LocalDateTime.now().toString());

            // 调用 submitForAnalysis（带指纹去重）
            Long reportId = logAnalysisExecutor.submitForAnalysis(
                entry.getMessage(),
                entry.getStackTrace() != null ? entry.getStackTrace() : "",
                config.getAppId(),
                queryParams
            );

            if (reportId != null) {
                storedCount++;
                // 触发异步分析流水线
                logAnalysisExecutor.executeAnalysis(reportId);
            }
        }

        return storedCount;
    }

    /**
     * 从配置构建查询参数
     */
    private LogQueryDto buildQueryFromConfig(AppLogConfig config) {
        LogQueryDto query = new LogQueryDto();

        // 使用配置的 DSL 查询
        if (config.getDslQuery() != null && !config.getDslQuery().isEmpty()) {
            query.setDslQuery(config.getDslQuery());
        } else {
            // 默认：查询最近 15 分钟的 ERROR 日志
            query.setErrorOnly(true);
            query.setStartTime(LocalDateTime.now().minusMinutes(15));
            query.setEndTime(LocalDateTime.now());
            query.setSize(100);
        }

        return query;
    }
}