package com.huawei.hisi.fixengine.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Input payload for the test-generation agent.
 * Populated from log-analysis results and the target method metadata.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestGenInput {

    /** Name to use for the generated test method (e.g. "testNullPointerException"). */
    private String testMethodName;

    /** Signature of the method under test (e.g. "com.foo.Bar.doStuff(String)"). */
    private String testMethodSignature;

    /** Entry-point parameters captured at runtime. */
    private Map<String, Object> entryParams;

    /** Call-chain spans from capture or log analysis. */
    private List<Map<String, Object>> spans;

    /** Simple class name of the target exception (e.g. "NullPointerException"). */
    private String exceptionType;

    /** Exception message to reproduce. */
    private String exceptionMessage;

    /** Ordered list of call-chain class.method signatures. */
    private List<String> callChain;
}
