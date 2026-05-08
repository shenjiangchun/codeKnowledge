package com.huawei.hisi.knowledgegraph.python.model;

import java.util.Collections;
import java.util.List;

import lombok.Builder;
import lombok.Value;

/**
 * Represents a Python function or method definition.
 */
@Value
public class PyFunction {
    String name;
    String qualName;
    List<String> paramNames;
    List<String> decorators;
    int lineStart;
    int lineEnd;
    boolean isMethod;
    String enclosingClass;

    @Builder
    private PyFunction(String name,
                       String qualName,
                       List<String> paramNames,
                       List<String> decorators,
                       int lineStart,
                       int lineEnd,
                       boolean isMethod,
                       String enclosingClass) {
        this.name = name;
        this.qualName = qualName;
        this.paramNames = paramNames == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(List.copyOf(paramNames));
        this.decorators = decorators == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(List.copyOf(decorators));
        this.lineStart = lineStart;
        this.lineEnd = lineEnd;
        this.isMethod = isMethod;
        this.enclosingClass = enclosingClass;
    }
}
