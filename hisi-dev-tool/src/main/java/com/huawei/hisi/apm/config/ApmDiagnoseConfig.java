package com.huawei.hisi.apm.config;

import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import com.huawei.hisi.apm.service.locator.KgEnricher;
import com.huawei.hisi.apm.service.locator.LlmClient;
import com.huawei.hisi.apm.service.locator.LlmDiagnoser;
import com.huawei.hisi.service.UnifiedTextService;

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

    /**
     * Default no-op {@link KgEnricher} used when no real implementation is on
     * the classpath. Returns an empty evidence list. Task 10 supplies the
     * production implementation which will override this bean.
     */
    @Bean
    @ConditionalOnMissingBean
    public KgEnricher noopKgEnricher() {
        return (projectPath, exceptionSpans) -> List.of();
    }

    /**
     * Default stub {@link LlmDiagnoser} returning a template fallback at a
     * mid-range confidence. The production {@code LlmDiagnoseAdapter} marks
     * itself {@code @Primary} so it wins whenever an {@link LlmClient} bean
     * exists and {@code hisi.apm.diagnose.llmEnabled=true}.
     */
    @Bean
    @ConditionalOnMissingBean
    public LlmDiagnoser stubLlmDiagnoser() {
        return (projectPath, exceptionSpans, kgEvidence, userNote) ->
            new LlmDiagnoser.LlmResult("(LLM disabled — template fallback)", 0.6);
    }

    /**
     * Default {@link LlmClient} backed by {@link UnifiedTextService}. Only
     * registered when {@code UnifiedTextService} is on the application context
     * (it auto-loads whenever the text model is configured).
     *
     * <p>System and user prompts are concatenated since {@code UnifiedTextService}
     * exposes only a single-prompt {@code generateText} entry point.
     */
    @Bean
    @ConditionalOnMissingBean(LlmClient.class)
    @ConditionalOnBean(UnifiedTextService.class)
    public LlmClient unifiedTextServiceLlmClient(UnifiedTextService textService) {
        return (systemPrompt, userPrompt) ->
            textService.generateText(systemPrompt + "\n\n" + userPrompt);
    }
}
