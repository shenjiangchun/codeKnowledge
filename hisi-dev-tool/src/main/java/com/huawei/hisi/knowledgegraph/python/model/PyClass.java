package com.huawei.hisi.knowledgegraph.python.model;

import java.util.Collections;
import java.util.List;

import lombok.Builder;
import lombok.Value;

/**
 * Represents a Python class definition.
 */
@Value
public class PyClass {
    String name;
    List<String> baseClasses;
    List<String> decorators;
    List<PyFunction> methods;
    List<PyClassAttribute> classAttributes;
    int lineStart;
    int lineEnd;

    @Builder
    private PyClass(String name,
                    List<String> baseClasses,
                    List<String> decorators,
                    List<PyFunction> methods,
                    List<PyClassAttribute> classAttributes,
                    int lineStart,
                    int lineEnd) {
        this.name = name;
        this.baseClasses = baseClasses == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(List.copyOf(baseClasses));
        this.decorators = decorators == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(List.copyOf(decorators));
        this.methods = methods == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(List.copyOf(methods));
        this.classAttributes = classAttributes == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(List.copyOf(classAttributes));
        this.lineStart = lineStart;
        this.lineEnd = lineEnd;
    }
}
