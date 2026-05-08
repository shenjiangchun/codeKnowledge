package com.huawei.hisi.knowledgegraph.python.model;

import java.util.Collections;
import java.util.List;

import lombok.Builder;
import lombok.Value;

/**
 * Top-level container for a parsed Python source file.
 */
@Value
public class PyModule {
    String filePath;
    String modulePath;
    List<PyImport> imports;
    List<PyClass> classes;
    List<PyFunction> topLevelFunctions;
    List<PyCall> calls;

    @Builder
    private PyModule(String filePath,
                     String modulePath,
                     List<PyImport> imports,
                     List<PyClass> classes,
                     List<PyFunction> topLevelFunctions,
                     List<PyCall> calls) {
        this.filePath = filePath;
        this.modulePath = modulePath;
        this.imports = imports == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(List.copyOf(imports));
        this.classes = classes == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(List.copyOf(classes));
        this.topLevelFunctions = topLevelFunctions == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(List.copyOf(topLevelFunctions));
        this.calls = calls == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(List.copyOf(calls));
    }
}
