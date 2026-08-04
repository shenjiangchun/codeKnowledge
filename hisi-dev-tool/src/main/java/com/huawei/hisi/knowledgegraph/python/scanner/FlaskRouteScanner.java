package com.huawei.hisi.knowledgegraph.python.scanner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.huawei.hisi.knowledgegraph.python.PythonKnowledgeGraphBuilder;
import com.huawei.hisi.knowledgegraph.python.model.PyCall;
import com.huawei.hisi.knowledgegraph.python.model.PyClass;
import com.huawei.hisi.knowledgegraph.python.model.PyFunction;
import com.huawei.hisi.knowledgegraph.python.model.PyModule;
import com.huawei.hisi.neo4j.model.EntryPointNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Scans a parsed {@link PyModule} for Flask route handler definitions and
 * produces {@link EntryPointNode} instances representing each HTTP route.
 *
 * <h3>Detection rules</h3>
 * <ol>
 *   <li>{@code <identifier>.route(...)} — classic Flask decorator.
 *       HTTP methods are extracted from the {@code methods=[...]} keyword arg;
 *       defaults to {@code GET} when absent.</li>
 *   <li>{@code <identifier>.<method>(...)} — Flask 2.0+ method-specific
 *       decorators ({@code @app.get(...)}, {@code @app.post(...)}, etc.).</li>
 * </ol>
 *
 * <h3>Blueprint support</h3>
 * URL prefixes declared via {@code url_prefix="..."} in {@code Blueprint(...)}
 * constructors are resolved and prepended to route paths.
 */
@Slf4j
@Component
public class FlaskRouteScanner {

    /** Matches {@code <identifier>.route(<args>)}. group 2 = args body. */
    private static final Pattern ROUTE_DECORATOR_PATTERN = Pattern.compile(
            "^([A-Za-z_][A-Za-z0-9_]*)\\.route\\((.*)\\)\\s*$",
            Pattern.DOTALL);

    /** Matches {@code <identifier>.<method>(<args>)} — Flask 2.0+ shortcut. */
    private static final Pattern METHOD_SHORTCUT_PATTERN = Pattern.compile(
            "^([A-Za-z_][A-Za-z0-9_]*)\\.(get|post|put|delete|patch|head|options)\\((.*)\\)\\s*$",
            Pattern.DOTALL);

    /** Matches a single- or double-quoted string literal at the start of arg list. */
    private static final Pattern FIRST_STRING_ARG_PATTERN = Pattern.compile(
            "^\\s*(?:r|R|b|B|u|U)?(?:\"([^\"]*)\"|'([^']*)')");

    /** Matches all quoted method names within a {@code methods=[...]} or {@code methods=(...)} block. */
    private static final Pattern METHODS_LIST_PATTERN = Pattern.compile(
            "methods\\s*=\\s*[\\[\\(]([^\\]\\)]+)[\\]\\)]");

    /** Matches individual quoted strings inside a methods list. */
    private static final Pattern QUOTED_STRING_PATTERN = Pattern.compile(
            "['\"]([A-Za-z]+)['\"]");

    /** Matches {@code varName = Blueprint(...)} at the start of a source line. */
    private static final Pattern BLUEPRINT_ASSIGNMENT_PATTERN = Pattern.compile(
            "^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*Blueprint\\b");

    /** Matches {@code url_prefix="..."} or {@code url_prefix='...'} in source. */
    private static final Pattern URL_PREFIX_PATTERN = Pattern.compile(
            "url_prefix\\s*=\\s*['\"]([^'\"]*)['\"]");

    private static final String LANGUAGE_PYTHON = "python";
    private static final String FRAMEWORK_FLASK = "flask";
    private static final String DEFAULT_METHOD = "GET";

    /**
     * Scan the given module and produce one {@link EntryPointNode} per Flask
     * route handler discovered.
     */
    public List<EntryPointNode> scanModule(PyModule module, String projectPath) {
        if (module == null) {
            return List.of();
        }

        Map<String, String> blueprintPrefixMap = buildBlueprintPrefixMap(module);

        List<EntryPointNode> entries = new ArrayList<>();
        for (PyFunction function : module.getTopLevelFunctions()) {
            entries.addAll(scanFunction(module, function, projectPath, blueprintPrefixMap));
        }
        for (PyClass clazz : module.getClasses()) {
            for (PyFunction method : clazz.getMethods()) {
                entries.addAll(scanFunction(module, method, projectPath, blueprintPrefixMap));
            }
        }
        return List.copyOf(entries);
    }

    // ── Blueprint prefix resolution ──────────────────────────────────────

    private Map<String, String> buildBlueprintPrefixMap(PyModule module) {
        if (module.getCalls() == null || module.getFilePath() == null) {
            return Collections.emptyMap();
        }
        List<String> sourceLines = readSourceLines(module.getFilePath());
        Map<String, String> map = new HashMap<>();
        for (PyCall call : module.getCalls()) {
            if (!"<module>".equals(call.getEnclosingFunction())) continue;
            String expr = call.getCalleeExpression();
            if (expr == null) continue;
            String funcName = expr.contains(".") ? expr.substring(expr.lastIndexOf('.') + 1) : expr;
            if (!"Blueprint".equals(funcName)) continue;
            String varName = findBlueprintVarName(sourceLines, call.getLineNumber());
            if (varName == null) continue;
            String prefix = extractUrlPrefixFromSource(sourceLines, call.getLineNumber());
            map.put(varName, prefix != null ? prefix : "");
        }
        return Collections.unmodifiableMap(map);
    }

    private static List<String> readSourceLines(String filePath) {
        try {
            return Files.readAllLines(Path.of(filePath), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return Collections.emptyList();
        }
    }

    private static String findBlueprintVarName(List<String> sourceLines, int lineNumber) {
        if (sourceLines == null || lineNumber < 1 || lineNumber > sourceLines.size()) {
            return null;
        }
        Matcher m = BLUEPRINT_ASSIGNMENT_PATTERN.matcher(sourceLines.get(lineNumber - 1));
        return m.find() ? m.group(1) : null;
    }

    private static String extractUrlPrefixFromSource(List<String> sourceLines, int lineNumber) {
        if (sourceLines == null || lineNumber < 1 || lineNumber > sourceLines.size()) return null;
        String line = sourceLines.get(lineNumber - 1);
        Matcher m = URL_PREFIX_PATTERN.matcher(line);
        return m.find() ? m.group(1) : null;
    }

    // ── Function scanning ───────────────────────────────────────────────

    private List<EntryPointNode> scanFunction(PyModule module,
                                              PyFunction function,
                                              String projectPath,
                                              Map<String, String> blueprintPrefixMap) {
        List<EntryPointNode> result = new ArrayList<>();
        for (String decorator : function.getDecorators()) {
            if (decorator == null) {
                continue;
            }
            String trimmed = decorator.trim();

            // Try classic @app.route(...) pattern first
            Matcher routeMatcher = ROUTE_DECORATOR_PATTERN.matcher(trimmed);
            if (routeMatcher.matches()) {
                String identifier = routeMatcher.group(1);
                String argsBody = routeMatcher.group(2);
                String url = extractFirstStringArg(argsBody);
                if (url == null) {
                    log.debug("Flask route decorator without parseable string path: {} (file={}, line={})",
                            decorator, module.getFilePath(), function.getLineStart());
                    url = "";
                }
                String prefix = blueprintPrefixMap.get(identifier);
                if (prefix != null) {
                    url = prefix + url;
                }
                List<String> methods = extractAllMethods(argsBody);
                for (String httpMethod : methods) {
                    result.add(buildEntry(module, function, httpMethod, url, projectPath));
                }
                continue;
            }

            // Try Flask 2.0+ method-specific decorator: @app.get(...), @app.post(...), etc.
            Matcher shortcutMatcher = METHOD_SHORTCUT_PATTERN.matcher(trimmed);
            if (shortcutMatcher.matches()) {
                String identifier = shortcutMatcher.group(1);
                String httpMethod = shortcutMatcher.group(2).toUpperCase();
                String argsBody = shortcutMatcher.group(3);
                String url = extractFirstStringArg(argsBody);
                if (url == null) {
                    log.debug("Flask method-shortcut decorator without parseable string path: {} (file={}, line={})",
                            decorator, module.getFilePath(), function.getLineStart());
                    url = "";
                }
                String prefix = blueprintPrefixMap.get(identifier);
                if (prefix != null) {
                    url = prefix + url;
                }
                result.add(buildEntry(module, function, httpMethod, url, projectPath));
            }
        }
        return result;
    }

    // ── Argument extraction helpers ─────────────────────────────────────

    private static String extractFirstStringArg(String argsBody) {
        if (argsBody == null) {
            return null;
        }
        Matcher m = FIRST_STRING_ARG_PATTERN.matcher(argsBody);
        if (!m.find()) {
            return null;
        }
        return m.group(1) != null ? m.group(1) : m.group(2);
    }

    /**
     * Extracts all HTTP method names from a {@code methods=["GET","POST",...]}
     * or {@code methods=('GET','POST',...)} keyword argument.
     * Returns a singleton list of {@code "GET"} when no methods kwarg is found.
     */
    private static List<String> extractAllMethods(String argsBody) {
        if (argsBody == null) {
            return List.of(DEFAULT_METHOD);
        }
        Matcher listMatcher = METHODS_LIST_PATTERN.matcher(argsBody);
        if (!listMatcher.find()) {
            return List.of(DEFAULT_METHOD);
        }
        String inner = listMatcher.group(1);
        List<String> methods = new ArrayList<>();
        Matcher strMatcher = QUOTED_STRING_PATTERN.matcher(inner);
        while (strMatcher.find()) {
            methods.add(strMatcher.group(1).toUpperCase());
        }
        return methods.isEmpty() ? List.of(DEFAULT_METHOD) : List.copyOf(methods);
    }

    // ── Node construction ───────────────────────────────────────────────

    private EntryPointNode buildEntry(PyModule module,
                                      PyFunction function,
                                      String httpMethod,
                                      String url,
                                      String projectPath) {
        String filePath = module.getFilePath();
        String handlerClass = function.getEnclosingClass() != null
                ? function.getEnclosingClass()
                : module.getModulePath();
        String handlerMethod = function.getName();
        int lineNumber = function.getLineStart();

        String entryId = sha256Prefix(filePath + ":" + httpMethod + ":" + url + ":" + lineNumber);
        String entryKey = httpMethod + " " + url;
        String entryInfo = buildEntryInfoJson(httpMethod, url, handlerClass, handlerMethod, filePath, lineNumber);

        // Compute methodNodeId: the handler function is directly decorated,
        // so we can resolve it from the same module without cross-module lookup.
        String methodNodeId = PythonKnowledgeGraphBuilder.computeMethodNodeId(
                module.getModulePath(), function.getQualName(), function.getParamNames());

        return EntryPointNode.builder()
                .entryId(entryId)
                .entryType(EntryPointNode.TYPE_HTTP)
                .entryKey(entryKey)
                .entryInfo(entryInfo)
                .methodNodeId(methodNodeId)
                .projectPath(projectPath)
                .language(LANGUAGE_PYTHON)
                .framework(FRAMEWORK_FLASK)
                .build();
    }

    private static String buildEntryInfoJson(String httpMethod,
                                             String url,
                                             String handlerClass,
                                             String handlerMethod,
                                             String filePath,
                                             int lineNumber) {
        StringBuilder sb = new StringBuilder(128);
        sb.append('{');
        appendField(sb, "subType", "FLASK_ROUTE", true);
        appendField(sb, "httpMethod", httpMethod, false);
        appendField(sb, "url", url, false);
        appendField(sb, "handlerClass", handlerClass, false);
        appendField(sb, "handlerMethod", handlerMethod, false);
        appendField(sb, "filePath", filePath, false);
        sb.append(",\"lineNumber\":").append(lineNumber);
        sb.append('}');
        return sb.toString();
    }

    private static void appendField(StringBuilder sb, String key, String value, boolean first) {
        if (!first) {
            sb.append(',');
        }
        sb.append('"').append(key).append("\":");
        if (value == null) {
            sb.append("null");
        } else {
            sb.append('"').append(escapeJson(value)).append('"');
        }
    }

    private static String escapeJson(String s) {
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    private static String sha256Prefix(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b & 0xFF));
            }
            return hex.substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
