package com.huawei.hisi.knowledgegraph.python.scanner;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.huawei.hisi.knowledgegraph.python.model.PyCall;
import com.huawei.hisi.knowledgegraph.python.model.PyImport;
import com.huawei.hisi.knowledgegraph.python.model.PyModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Scans a parsed {@link PyModule} for outbound HTTP calls made via
 * {@code requests}, {@code httpx}, or {@code aiohttp} and produces
 * {@link PythonHttpCall} records for each detected call site.
 *
 * <h3>Detection heuristic</h3>
 * For every {@link PyCall} in the module, split {@code calleeExpression} on
 * {@code '.'}. If the last segment is a known HTTP method name <em>and</em>
 * the second-to-last segment is a recognised library/instance identifier,
 * the call is classified as an outbound HTTP call.
 *
 * <p>Instance method calls (e.g. {@code session.get}, {@code client.post})
 * are matched but tagged with {@code confidence=low} in the log output
 * because without type resolution we cannot be certain the receiver is
 * actually an HTTP client instance.
 */
@Slf4j
@Component
public class PythonHttpCallScanner {

    private static final Set<String> HTTP_METHODS = Set.of(
            "get", "post", "put", "delete", "patch", "head", "options", "request");

    /** Definite library-level identifiers. */
    private static final Set<String> KNOWN_LIBRARIES = Set.of(
            "requests", "httpx", "aiohttp");

    /**
     * Heuristic instance identifiers (common variable names).
     * Matches are flagged as low confidence.
     */
    private static final Set<String> INSTANCE_IDENTIFIERS = Set.of(
            "session", "client");

    private static final Set<String> HTTP_IMPORT_MODULES = Set.of(
            "requests", "httpx", "aiohttp");

    private static final String LANGUAGE_PYTHON = "python";

    /**
     * Scan the given module for outbound HTTP calls.
     *
     * @param module             parsed Python module (must not be null)
     * @param projectPath        owning project path (must not be null)
     * @param framework          framework name (e.g. "fastapi", "django", or null)
     * @return immutable list of HTTP call records, possibly empty
     */
    public List<PythonHttpCall> scanModule(PyModule module,
                                           String projectPath,
                                           String framework) {
        if (module == null) {
            return List.of();
        }

        boolean hasHttpImport = hasRelevantImport(module, HTTP_IMPORT_MODULES);

        List<PythonHttpCall> results = new ArrayList<>();
        for (PyCall call : module.getCalls()) {
            PythonHttpCall httpCall = classify(call, module.getFilePath(),
                    projectPath, framework, hasHttpImport);
            if (httpCall != null) {
                results.add(httpCall);
            }
        }
        return List.copyOf(results);
    }

    private PythonHttpCall classify(PyCall call,
                                    String filePath,
                                    String projectPath,
                                    String framework,
                                    boolean hasHttpImport) {
        String expr = call.getCalleeExpression();
        if (expr == null || expr.isEmpty()) {
            return null;
        }

        String[] parts = expr.split("\\.");
        if (parts.length < 2) {
            return null;
        }

        String lastSegment = parts[parts.length - 1].toLowerCase();
        if (!HTTP_METHODS.contains(lastSegment)) {
            return null;
        }

        String secondToLast = parts[parts.length - 2].toLowerCase();
        String library = resolveLibrary(secondToLast, hasHttpImport);
        if (library == null) {
            return null;
        }

        String httpMethod = lastSegment.toUpperCase();
        if ("REQUEST".equals(httpMethod)) {
            httpMethod = "REQUEST";
        }

        return PythonHttpCall.builder()
                .filePath(filePath)
                .lineNumber(call.getLineNumber())
                .enclosingFunction(call.getEnclosingFunction())
                .library(library)
                .httpMethod(httpMethod)
                .url(call.getFirstStringArg())
                .language(LANGUAGE_PYTHON)
                .framework(framework)
                .projectPath(projectPath)
                .build();
    }

    /**
     * Resolve the second-to-last segment to a library name.
     * Returns null if the segment is not recognised.
     */
    private String resolveLibrary(String segment, boolean hasHttpImport) {
        if (KNOWN_LIBRARIES.contains(segment)) {
            return segment;
        }
        if (INSTANCE_IDENTIFIERS.contains(segment) && hasHttpImport) {
            return segment;
        }
        return null;
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
