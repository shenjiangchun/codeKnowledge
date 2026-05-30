package com.huawei.hisi.apm.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ApmDiagnosePropertiesTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void initValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeFactory() {
        if (factory != null) {
            factory.close();
        }
    }

    @Test
    @DisplayName("defaults are populated as documented")
    void defaults_populated() {
        ApmDiagnoseProperties p = new ApmDiagnoseProperties();

        assertThat(p.isEnabled()).isTrue();
        assertThat(p.isLlmEnabled()).isTrue();
        assertThat(p.isKgEnabled()).isTrue();
        assertThat(p.getExecutorCorePoolSize()).isEqualTo(2);
        assertThat(p.getExecutorMaxPoolSize()).isEqualTo(8);
        assertThat(p.getExecutorQueueCapacity()).isEqualTo(100);
        assertThat(p.getExecutorKeepAliveSeconds()).isEqualTo(60);
        assertThat(p.getTimeoutSeconds()).isEqualTo(60);
        assertThat(p.getLlmTimeoutSeconds()).isEqualTo(45);
        assertThat(p.getConfidenceLowThreshold()).isEqualTo(0.5);
        assertThat(validator.validate(p)).isEmpty();
    }

    @Test
    @DisplayName("executorCorePoolSize=0 violates @Min(1)")
    void corePoolSize_zero_rejected() {
        ApmDiagnoseProperties p = new ApmDiagnoseProperties();
        p.setExecutorCorePoolSize(0);

        Set<ConstraintViolation<ApmDiagnoseProperties>> violations = validator.validate(p);

        assertThat(violations)
                .anyMatch(v -> v.getPropertyPath().toString().equals("executorCorePoolSize"));
    }

    @Test
    @DisplayName("confidenceLowThreshold=1.5 violates @DecimalMax")
    void confidenceThreshold_above_one_rejected() {
        ApmDiagnoseProperties p = new ApmDiagnoseProperties();
        p.setConfidenceLowThreshold(1.5);

        Set<ConstraintViolation<ApmDiagnoseProperties>> violations = validator.validate(p);

        assertThat(violations)
                .anyMatch(v -> v.getPropertyPath().toString().equals("confidenceLowThreshold"));
    }

    @Test
    @DisplayName("negative timeoutSeconds violates @Min(1)")
    void timeoutSeconds_negative_rejected() {
        ApmDiagnoseProperties p = new ApmDiagnoseProperties();
        p.setTimeoutSeconds(-1);

        Set<ConstraintViolation<ApmDiagnoseProperties>> violations = validator.validate(p);

        assertThat(violations)
                .anyMatch(v -> v.getPropertyPath().toString().equals("timeoutSeconds"));
    }

    @Test
    @DisplayName("llmTimeoutSeconds >= timeoutSeconds violates cross-field @AssertTrue")
    void llmTimeout_must_be_strictly_less_than_overall() {
        ApmDiagnoseProperties p = new ApmDiagnoseProperties();
        p.setTimeoutSeconds(30);
        p.setLlmTimeoutSeconds(45);

        Set<ConstraintViolation<ApmDiagnoseProperties>> violations = validator.validate(p);

        assertThat(violations)
                .anyMatch(v -> v.getPropertyPath().toString().equals("llmTimeoutWithinOverall"));
    }
}
