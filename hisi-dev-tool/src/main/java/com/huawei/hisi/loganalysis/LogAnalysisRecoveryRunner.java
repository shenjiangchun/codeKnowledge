package com.huawei.hisi.loganalysis;

import com.huawei.hisi.repository.LogAnalysisRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

/**
 * 日志分析启动恢复：分析任务队列是线程池内存态（{@code AsyncConfig.analysisTaskExecutor}），
 * 服务重启即丢——遗留的 pending/processing 行不会再有执行线程认领，永久僵尸。
 * 启动时把它们批量标 failed，前端重试按钮即刻可用。
 *
 * <p>误伤面为零：重启瞬间不存在任何活着的分析线程。不自动重跑——重放参数（查询上下文、
 * 拉取来源）不可靠，让用户显式重试更安全。模式参照 {@code KgGenerationQueue.recoverOrphanTasks}；
 * {@code @DependsOn} 保证全新库首启时建表 initializer 先行（否则查表报 no such table）。
 */
@Slf4j
@Component
@DependsOn("SQLiteSchemaInitializer")
@RequiredArgsConstructor
public class LogAnalysisRecoveryRunner {

    private final LogAnalysisRepository repository;

    @PostConstruct
    public void recoverInterruptedReports() {
        try {
            int affected = repository.markInterruptedAsFailed("服务重启中断，请手动重试");
            if (affected > 0) {
                log.warn("[LogAnalysisRecovery] 启动恢复：{} 条 pending/processing 报告标为 failed", affected);
            }
        } catch (Exception e) {
            // 首启竞态或表缺失时不能拖垮启动：只记录
            log.error("[LogAnalysisRecovery] 启动恢复失败: {}", e.getMessage());
        }
    }
}
