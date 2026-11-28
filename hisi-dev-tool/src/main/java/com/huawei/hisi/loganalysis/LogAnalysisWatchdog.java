package com.huawei.hisi.loganalysis;

import com.huawei.hisi.loganalysis.v2.LogAnalysisExecutorV2;
import com.huawei.hisi.repository.LogAnalysisRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 日志分析运行看门狗：清理挂死任务，保证「永远 pending/processing」不再出现。
 *
 * <ul>
 *   <li>processing 超过 {@value #PROCESSING_TIMEOUT_SECONDS} 秒（默认 30 分钟）→ failed：
 *       正常单条分析分钟级完成；进程被杀或 LLM 挂死不会抛异常，行会永远停在 processing。</li>
 *   <li>pending 超过 {@value #PENDING_TIMEOUT_SECONDS} 秒（默认 24 小时）→ failed：
 *       正常队列寿命为分钟级，超过一天的排队必然是死任务。</li>
 * </ul>
 *
 * <p>判死 processing 的同时要释放 {@link LogAnalysisExecutorV2} 的 inFlight 集合，
 * 否则手动重试仍会被 reserve() 拒绝（inFlight 是内存 Set，只有运行中的会话才该占位）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LogAnalysisWatchdog {

    /** processing 超时阈值（秒）：正常单条分析分钟级完成，30 分钟余量充足。 */
    static final long PROCESSING_TIMEOUT_SECONDS = 30 * 60;
    /** pending 超时阈值（秒）：排队超过一天判死。 */
    static final long PENDING_TIMEOUT_SECONDS = 24 * 3600;

    private final LogAnalysisRepository repository;
    private final LogAnalysisExecutorV2 executorV2;

    @Scheduled(fixedDelay = 300_000) // 每 5 分钟巡检
    public void reapStaleReports() {
        long now = System.currentTimeMillis() / 1000L;
        try {
            List<Long> stuckProcessing = repository.markProcessingTimeoutFailed(
                    now - PROCESSING_TIMEOUT_SECONDS, "处理超时（30 分钟），已判失败，请重试");
            for (Long reportId : stuckProcessing) {
                executorV2.release(reportId);
            }
            int stalePending = repository.markPendingTimeoutFailed(
                    now - PENDING_TIMEOUT_SECONDS, "排队超过 24 小时，已判失败，请重试");
            if (!stuckProcessing.isEmpty() || stalePending > 0) {
                log.warn("[LogAnalysisWatchdog] 僵尸清理：processing 超时 {} 条，pending 超时 {} 条",
                        stuckProcessing.size(), stalePending);
            }
        } catch (Exception e) {
            log.error("[LogAnalysisWatchdog] 巡检失败: {}", e.getMessage());
        }
    }
}
