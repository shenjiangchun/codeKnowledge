package com.huawei.hisi.apm.service.locator;

/**
 * Base unchecked exception for LLM diagnose failures. Mapped to
 * {@link com.huawei.hisi.apm.model.ApmErrorCode#LLM_UPSTREAM_ERROR} by
 * {@link FailureLocatorService#mapException}.
 *
 * @author HiSi DevTool Team
 */
public class DiagnoseLlmException extends RuntimeException {

    /**
     * @param message human-readable failure description
     * @param cause   underlying cause, nullable
     */
    public DiagnoseLlmException(String message, Throwable cause) {
        super(message, cause);
    }
}
