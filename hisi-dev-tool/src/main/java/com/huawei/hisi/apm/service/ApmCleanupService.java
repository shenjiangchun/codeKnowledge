package com.huawei.hisi.apm.service;

import com.huawei.hisi.apm.config.ApmConfig;
import com.huawei.hisi.apm.repository.ApmSpanRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class ApmCleanupService {

    private final ApmSpanRepository apmSpanRepository;
    private final ApmConfig apmConfig;

    /**
     * Periodically cleans up APM spans older than the configured TTL.
     * Runs every 10 minutes.
     */
    @Scheduled(fixedRate = 600_000) // every 10 minutes
    public void cleanupExpiredSpans() {
        int ttlHours = apmConfig.getSpanTtlHours();
        long cutoff = Instant.now().minus(ttlHours, ChronoUnit.HOURS).getEpochSecond();
        int deleted = apmSpanRepository.deleteOlderThan(cutoff);
        if (deleted > 0) {
            log.info("[APM Cleanup] Removed {} expired spans (older than {}h)", deleted, ttlHours);
        }
    }
}
