package com.huawei.hisi.apm.service.locator;

/**
 * Thrown when the LLM response cannot be parsed into the expected JSON schema.
 * Mapped to {@link com.huawei.hisi.apm.model.ApmErrorCode#LLM_INVALID_RESPONSE}.
 *
 * @author HiSi DevTool Team
 */
public class DiagnoseLlmInvalidResponseException extends DiagnoseLlmException {

    /**
     * @param message description including a truncated snippet of the raw response
     * @param cause   underlying parser cause, nullable
     */
    public DiagnoseLlmInvalidResponseException(String message, Throwable cause) {
        super(message, cause);
    }
}
