package com.huawei.hisi.knowledgegraph.python.scanner;

import lombok.Builder;
import lombok.Value;

/**
 * Immutable record describing a single Python HTTP outbound call site.
 *
 * <p>This DTO mirrors the conceptual schema of {@link com.huawei.hisi.model.HttpCallInfo}
 * (the Java RestTemplate/WebClient equivalent) but adds Python-specific
 * provenance ({@code language}, {@code framework}) so the cross-service linker
 * (P4) can match Python out-edges to Java in-edges by URL.
 */
@Value
@Builder
public class PythonHttpCall {
    String filePath;
    int lineNumber;
    String enclosingFunction;
    String library;
    String httpMethod;
    String url;
    String language;
    String framework;
    String projectPath;
}
