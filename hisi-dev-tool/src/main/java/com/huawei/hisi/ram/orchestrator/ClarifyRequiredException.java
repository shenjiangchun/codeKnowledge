package com.huawei.hisi.ram.orchestrator;

import java.util.List;

/**
 * Thrown by a {@link DagNode} when it needs additional user input before it
 * can produce its output. The orchestrator catches this, records a
 * {@code CLARIFY_REQ} event, and parks the session in
 * {@code WAITING_CLARIFY} until {@code resume(...)} is called.
 */
public class ClarifyRequiredException extends RuntimeException {

    private final List<String> clarifyQuestions;
    private final java.util.Map<String, Object> partialOutput;

    public ClarifyRequiredException(List<String> clarifyQuestions) {
        this(clarifyQuestions, null);
    }

    public ClarifyRequiredException(List<String> clarifyQuestions,
                                     java.util.Map<String, Object> partialOutput) {
        super("Clarification required: " + clarifyQuestions);
        this.clarifyQuestions = clarifyQuestions == null ? List.of() : List.copyOf(clarifyQuestions);
        this.partialOutput = partialOutput;
    }

    public List<String> getClarifyQuestions() {
        return clarifyQuestions;
    }

    public java.util.Map<String, Object> getPartialOutput() {
        return partialOutput;
    }
}
