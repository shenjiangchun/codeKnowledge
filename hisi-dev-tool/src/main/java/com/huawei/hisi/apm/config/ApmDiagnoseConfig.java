package com.huawei.hisi.apm.config;

import java.util.concurrent.ThreadPoolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Wires the dedicated executor used by the APM Failure Locator async pipeline.
 *
 * <p>This executor is intentionally isolated from Spring's general {@code @Async}
 * pool so diagnose workloads cannot starve unrelated async tasks (and vice versa).
 *
 * <p>The Caffeine-backed cache beans ({@code DiagnosisReportStore},
 * {@code DiagnosisDedupCache}, {@code ExceptionSpanIndex}) are auto-discovered
 * as {@code @Component}s elsewhere and intentionally NOT redefined here.
 */
@Configuration
@EnableConfigurationProperties(ApmDiagnoseProperties.class)
public class ApmDiagnoseConfig {

    private static final Logger LOG = LoggerFactory.getLogger(ApmDiagnoseConfig.class);

    @Bean(name = "apmDiagnoseExecutor")
    @ConditionalOnProperty(prefix = "hisi.apm.diagnose", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public ThreadPoolTaskExecutor apmDiagnoseExecutor(ApmDiagnoseProperties props) {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(props.getExecutorCorePoolSize());
        exec.setMaxPoolSize(props.getExecutorMaxPoolSize());
        exec.setQueueCapacity(props.getExecutorQueueCapacity());
        exec.setKeepAliveSeconds(props.getExecutorKeepAliveSeconds());
        exec.setThreadNamePrefix("apm-diagnose-");
        exec.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        exec.setWaitForTasksToCompleteOnShutdown(true);
        exec.setAwaitTerminationSeconds(30);
        exec.initialize();
        LOG.info("apmDiagnoseExecutor initialised - core={}, max={}, queue={}",
                props.getExecutorCorePoolSize(),
                props.getExecutorMaxPoolSize(),
                props.getExecutorQueueCapacity());
        return exec;
    }
}
