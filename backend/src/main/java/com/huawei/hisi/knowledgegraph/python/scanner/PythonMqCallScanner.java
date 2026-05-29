package com.huawei.hisi.knowledgegraph.python.scanner;

import java.util.ArrayList;
import java.util.List;

import com.huawei.hisi.knowledgegraph.python.model.PyCall;
import com.huawei.hisi.knowledgegraph.python.model.PyModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Scans a parsed {@link PyModule} for outbound message-queue calls and
 * produces {@link PythonMqCall} records for each detected call site.
 *
 * <h3>Detection heuristics</h3>
 * <ul>
 *   <li>{@code *.send_task(...)} &rarr; library=celery, topic=firstStringArg</li>
 *   <li>{@code *.send(...)} &rarr; library=kafka (heuristic), topic=firstStringArg</li>
 *   <li>{@code *.publish(...)} &rarr; library=aio_pika, topic=firstStringArg</li>
 * </ul>
 */
@Slf4j
@Component
public class PythonMqCallScanner {

    private static final String LANGUAGE_PYTHON = "python";

    /**
     * Scan the given module for outbound MQ calls.
     */
    public List<PythonMqCall> scanModule(PyModule module,
                                         String projectPath,
                                         String framework) {
        if (module == null) {
            return List.of();
        }

        List<PythonMqCall> results = new ArrayList<>();
        for (PyCall call : module.getCalls()) {
            PythonMqCall mqCall = classify(call, module.getFilePath(),
                    projectPath, framework);
            if (mqCall != null) {
                results.add(mqCall);
            }
        }
        return List.copyOf(results);
    }

    private PythonMqCall classify(PyCall call,
                                  String filePath,
                                  String projectPath,
                                  String framework) {
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
}
