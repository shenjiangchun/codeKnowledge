package com.huawei.hisi.knowledgegraph.python.scanner;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.huawei.hisi.knowledgegraph.python.model.PyCall;
import com.huawei.hisi.knowledgegraph.python.model.PyImport;
import com.huawei.hisi.knowledgegraph.python.model.PyModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class PythonMqCallScanner {

    private static final String LANGUAGE_PYTHON = "python";

    private static final Set<String> KAFKA_IMPORT_MODULES = Set.of(
            "kafka", "confluent_kafka", "aiokafka");

    public List<PythonMqCall> scanModule(PyModule module,
                                         String projectPath,
                                         String framework) {
        if (module == null) {
            return List.of();
        }

        boolean hasKafkaImport = hasRelevantImport(module, KAFKA_IMPORT_MODULES);

        List<PythonMqCall> results = new ArrayList<>();
        for (PyCall call : module.getCalls()) {
            PythonMqCall mqCall = classify(call, module.getFilePath(),
                    projectPath, framework, hasKafkaImport);
            if (mqCall != null) {
                results.add(mqCall);
            }
        }
        return List.copyOf(results);
    }

    private PythonMqCall classify(PyCall call,
                                  String filePath,
                                  String projectPath,
                                  String framework,
                                  boolean hasKafkaImport) {
        String expr = call.getCalleeExpression();
        if (expr == null || expr.isEmpty()) {
            return null;
        }

        int lastDot = expr.lastIndexOf('.');
        if (lastDot < 0) {
            return null;
        }
        String lastSegment = expr.substring(lastDot + 1);

        String library;
        if ("send_task".equals(lastSegment)) {
            library = "celery";
        } else if ("send".equals(lastSegment) && call.getFirstStringArg() != null) {
            if (!hasKafkaImport) return null;
            library = "kafka";
        } else if ("publish".equals(lastSegment)) {
            library = "aio_pika";
        } else {
            return null;
        }

        return PythonMqCall.builder()
                .filePath(filePath)
                .lineNumber(call.getLineNumber())
                .enclosingFunction(call.getEnclosingFunction())
                .library(library)
                .topic(call.getFirstStringArg())
                .language(LANGUAGE_PYTHON)
                .framework(framework)
                .projectPath(projectPath)
                .build();
    }

    private static boolean hasRelevantImport(PyModule module, Set<String> modules) {
        if (module.getImports() == null) return false;
        for (PyImport imp : module.getImports()) {
            String moduleName = imp.getModuleName();
            if (moduleName == null) continue;
            String topLevel = moduleName.contains(".")
                    ? moduleName.substring(0, moduleName.indexOf('.'))
                    : moduleName;
            if (modules.contains(topLevel)) return true;
            if (imp.isFromImport() && modules.contains(moduleName)) return true;
        }
        return false;
    }
}
