package com.huawei.hisi.apm.model;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link DiagnoseRequest}.
 */
class DiagnoseRequestTest {

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
    @DisplayName("Valid request passes validation")
    void validRequest_passes() {
        DiagnoseRequest req = new DiagnoseRequest(
            "abc123", "C:/proj", "session-1", true, "note");
        Set<ConstraintViolation<DiagnoseRequest>> violations = validator.validate(req);
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("Blank traceId violates @NotBlank")
    void blankTraceId_violates() {
        DiagnoseRequest req = new DiagnoseRequest(
            "  ", "C:/proj", null, null, null);
        Set<ConstraintViolation<DiagnoseRequest>> violations = validator.validate(req);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("traceId"));
    }

    @Test
    @DisplayName("Blank projectPath violates @NotBlank")
    void blankProjectPath_violates() {
        DiagnoseRequest req = new DiagnoseRequest(
            "abc", "", null, null, null);
        Set<ConstraintViolation<DiagnoseRequest>> violations = validator.validate(req);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("projectPath"));
    }

    @Test
    @DisplayName("Null forceRefresh is normalized to false")
    void nullForceRefresh_normalizedToFalse() {
        DiagnoseRequest req = new DiagnoseRequest(
            "abc", "C:/proj", null, null, null);
        assertThat(req.forceRefresh()).isFalse();
    }

    @Test
    @DisplayName("userNote longer than 500 chars violates @Size")
    void longUserNote_violates() {
        String longNote = "x".repeat(501);
        DiagnoseRequest req = new DiagnoseRequest(
            "abc", "C:/proj", null, false, longNote);
        Set<ConstraintViolation<DiagnoseRequest>> violations = validator.validate(req);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("userNote"));
    }
}
