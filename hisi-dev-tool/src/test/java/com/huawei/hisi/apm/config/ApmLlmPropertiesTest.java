package com.huawei.hisi.apm.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ApmLlmProperties}.
 */
class ApmLlmPropertiesTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void initValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        if (factory != null) {
            factory.close();
        }
    }

    @Test
    void defaults_arePopulated() {
        ApmLlmProperties p = new ApmLlmProperties();
        assertThat(p.getTimeoutSeconds()).isEqualTo(40);
        assertThat(p.getMaxConcurrency()).isEqualTo(2);
        assertThat(p.getTemperature()).isEqualTo(0.2);
        assertThat(p.getMaxTokens()).isEqualTo(1024);
    }

    @Test
    void maxConcurrency_zero_violatesMin() {
        ApmLlmProperties p = validProps();
        p.setMaxConcurrency(0);
        Set<ConstraintViolation<ApmLlmProperties>> violations = validator.validate(p);
        assertThat(violations)
                .anyMatch(v -> v.getPropertyPath().toString().equals("maxConcurrency"));
    }

    @Test
    void maxConcurrency_seventeen_violatesMax() {
        ApmLlmProperties p = validProps();
        p.setMaxConcurrency(17);
        Set<ConstraintViolation<ApmLlmProperties>> violations = validator.validate(p);
        assertThat(violations)
                .anyMatch(v -> v.getPropertyPath().toString().equals("maxConcurrency"));
    }

    @Test
    void valid_props_haveNoViolations() {
        Set<ConstraintViolation<ApmLlmProperties>> violations = validator.validate(validProps());
        assertThat(violations).isEmpty();
    }

    private ApmLlmProperties validProps() {
        ApmLlmProperties p = new ApmLlmProperties();
        p.setBaseUrl("https://example.com");
        p.setModel("claude-opus-4-6-cc");
        p.setApiKey("sk-test");
        return p;
    }
}
