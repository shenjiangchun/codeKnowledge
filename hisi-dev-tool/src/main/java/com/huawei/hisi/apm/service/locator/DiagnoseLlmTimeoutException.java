package com.huawei.hisi.apm.service.locator;

/**
 * Thrown when an LLM diagnose call exceeds the configured deadline. Mapped to
 * {@link com.huawei.hisi.apm.model.ApmErrorCode#LLM_TIMEOUT}.
 *
 * @author HiSi DevTool Team
 */
public class DiagnoseLlmTimeoutException extends DiagnoseLlmException {

    /**
     * @param message timeout context
     * @param cause   underlying cause, nullable
     */
    public DiagnoseLlmTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
