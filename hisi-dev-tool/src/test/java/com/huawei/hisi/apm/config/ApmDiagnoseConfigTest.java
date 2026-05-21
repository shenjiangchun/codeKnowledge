package com.huawei.hisi.apm.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class ApmDiagnoseConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
            .withUserConfiguration(ApmDiagnoseConfig.class);

    @Test
    @DisplayName("apmDiagnoseExecutor bean is created with properties applied")
    void executor_bean_created_with_properties() {
        runner.withPropertyValues(
                        "hisi.apm.diagnose.executor-core-pool-size=4",
                        "hisi.apm.diagnose.executor-max-pool-size=12",
                        "hisi.apm.diagnose.executor-queue-capacity=200")
                .run(context -> {
                    assertThat(context).hasBean("apmDiagnoseExecutor");
                    ThreadPoolTaskExecutor exec = context.getBean(
                            "apmDiagnoseExecutor", ThreadPoolTaskExecutor.class);
                    assertThat(exec.getCorePoolSize()).isEqualTo(4);
                    assertThat(exec.getMaxPoolSize()).isEqualTo(12);
                });
    }

    @Test
    @DisplayName("defaults: executor bean present when enabled flag absent (matchIfMissing)")
    void executor_bean_present_by_default() {
        runner.run(context -> {
            assertThat(context).hasBean("apmDiagnoseExecutor");
            ThreadPoolTaskExecutor exec = context.getBean(
                    "apmDiagnoseExecutor", ThreadPoolTaskExecutor.class);
            assertThat(exec.getCorePoolSize()).isEqualTo(2);
            assertThat(exec.getMaxPoolSize()).isEqualTo(8);
        });
    }

    @Test
    @DisplayName("hisi.apm.diagnose.enabled=false suppresses the executor bean")
    void executor_bean_absent_when_disabled() {
        runner.withPropertyValues("hisi.apm.diagnose.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean("apmDiagnoseExecutor"));
    }
}
