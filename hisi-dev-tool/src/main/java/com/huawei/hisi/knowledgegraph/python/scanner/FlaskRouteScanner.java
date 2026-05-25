package com.huawei.hisi.knowledgegraph.python.scanner;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.huawei.hisi.knowledgegraph.python.PythonKnowledgeGraphBuilder;
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
 * <h3>Detection rule</h3>
 * A function whose decorator list contains a call of the form
 * {@code <identifier>.route(...)}. The identifier is typically {@code app}
 * (Flask app) or {@code bp} (blueprint) but is intentionally not constrained.
 *
 * <h3>HTTP method extraction</h3>
 * Flask routes accept an optional {@code methods=["GET","POST"]} keyword.
 * When present, the first listed method is used. When absent, the default
 * is {@code GET}.
 */
@Slf4j
@Component
public class FlaskRouteScanner {

    /** Matches {@code <identifier>.route(<args>)}. group 2 = args body. */
    private static final Pattern ROUTE_DECORATOR_PATTERN = Pattern.compile(
            "^([A-Za-z_][A-Za-z0-9_]*)\\.route\\((.*)\\)\\s*$",
            Pattern.DOTALL);

    /** Matches a single- or double-quoted string literal at the start of arg list. */
    private static final Pattern FIRST_STRING_ARG_PATTERN = Pattern.compile(
            "^\\s*(?:r|R|b|B|u|U)?(?:\"([^\"]*)\"|'([^']*)')");

    /** Matches first method name within a {@code methods=[...]} keyword arg. */
    private static final Pattern METHODS_KW_PATTERN = Pattern.compile(
            "methods\\s*=\\s*[\\[\\(]\\s*(?:\"([A-Za-z]+)\"|'([A-Za-z]+)')");

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

        List<EntryPointNode> entries = new ArrayList<>();
        for (PyFunction function : module.getTopLevelFunctions()) {
            entries.addAll(scanFunction(module, function, projectPath));
        }
        for (PyClass clazz : module.getClasses()) {
            for (PyFunction method : clazz.getMethods()) {
                entries.addAll(scanFunction(module, method, projectPath));
            }
        }
        return List.copyOf(entries);
    }

    private List<EntryPointNode> scanFunction(PyModule module,
                                              PyFunction function,
                                              String projectPath) {
        List<EntryPointNode> result = new ArrayList<>();
        for (String decorator : function.getDecorators()) {
            if (decorator == null) {
                continue;
            }
            Matcher matcher = ROUTE_DECORATOR_PATTERN.matcher(decorator.trim());
            if (!matcher.matches()) {
                continue;
            }
            String args = matcher.group(2);
            String url = extractFirstStringArg(args);
            if (url == null) {
                log.debug("Flask route decorator without parseable string path: {} (file={}, line={})",
                        decorator, module.getFilePath(), function.getLineStart());
                url = "";
            }
            String httpMethod = extractFirstMethod(args);
            result.add(buildEntry(module, function, httpMethod, url, projectPath));
        }
        return result;
    }

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

    private static String extractFirstMethod(String argsBody) {
        if (argsBody == null) {
            return DEFAULT_METHOD;
        }
        Matcher m = METHODS_KW_PATTERN.matcher(argsBody);
        if (!m.find()) {
            return DEFAULT_METHOD;
        }
        String method = m.group(1) != null ? m.group(1) : m.group(2);
        return method.toUpperCase();
    }

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
