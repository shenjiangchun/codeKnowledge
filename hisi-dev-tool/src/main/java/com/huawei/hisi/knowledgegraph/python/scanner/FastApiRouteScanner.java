package com.huawei.hisi.knowledgegraph.python.scanner;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
 * Scans a parsed {@link PyModule} for FastAPI route handler definitions and
 * produces {@link EntryPointNode} instances representing each HTTP route.
 *
 * <p>Detection rule: a function whose decorator list contains a call of the
 * form {@code <identifier>.<httpMethod>(...)} where {@code httpMethod} is one
 * of {@code get/post/put/delete/patch/options/head}. The identifier portion
 * (typically {@code app} or {@code router}) is intentionally not constrained,
 * matching the FastAPI idiom of multiple routers per module.</p>
 */
@Slf4j
@Component
public class FastApiRouteScanner {

    private static final Set<String> HTTP_METHODS = Set.of(
            "get", "post", "put", "delete", "patch", "options", "head");

    /**
     * {@code (identifier).(method)(args)} — group 2 = method, group 3 = args.
     */
    private static final Pattern ROUTE_DECORATOR_PATTERN = Pattern.compile(
            "^([A-Za-z_][A-Za-z0-9_]*)\\.(get|post|put|delete|patch|options|head)\\((.*)\\)\\s*$",
            Pattern.DOTALL);

    /** Matches a single- or double-quoted string literal at the start of arg list. */
    private static final Pattern FIRST_STRING_ARG_PATTERN = Pattern.compile(
            "^\\s*(?:r|R|b|B|u|U)?(?:\"([^\"]*)\"|'([^']*)')");

    private static final String LANGUAGE_PYTHON = "python";
    private static final String FRAMEWORK_FASTAPI = "fastapi";

    /**
     * Scan the given module and produce one {@link EntryPointNode} per FastAPI
     * route handler discovered.
     *
     * @param module             parsed Python module (must not be null)
     * @param projectPath        owning project path (must not be null)
     * @return immutable list of entry points, possibly empty
     */
    public List<EntryPointNode> scanModule(PyModule module, String projectPath) {
        if (module == null) {
            return List.of();
        }

        String routerPrefix = extractSingleRouterPrefix(module);

        List<EntryPointNode> entries = new ArrayList<>();
        for (PyFunction function : module.getTopLevelFunctions()) {
            entries.addAll(scanFunction(module, function, projectPath, routerPrefix));
        }
        for (PyClass clazz : module.getClasses()) {
            for (PyFunction method : clazz.getMethods()) {
                entries.addAll(scanFunction(module, method, projectPath, routerPrefix));
            }
        }
        return List.copyOf(entries);
    }

    private String extractSingleRouterPrefix(PyModule module) {
        if (module.getCalls() == null) return null;
        String prefix = null;
        int routerCount = 0;
        for (PyCall call : module.getCalls()) {
            if (!"<module>".equals(call.getEnclosingFunction())) continue;
            String expr = call.getCalleeExpression();
            if (expr == null) continue;
            String funcName = expr.contains(".") ? expr.substring(expr.lastIndexOf('.') + 1) : expr;
            if ("APIRouter".equals(funcName)) {
                routerCount++;
                if (call.getFirstStringArg() != null && !call.getFirstStringArg().isEmpty()) {
                    prefix = call.getFirstStringArg();
                }
            }
        }
        return routerCount == 1 ? prefix : null;
    }

    private List<EntryPointNode> scanFunction(PyModule module,
                                              PyFunction function,
                                              String projectPath,
                                              String routerPrefix) {
        List<EntryPointNode> result = new ArrayList<>();
        for (String decorator : function.getDecorators()) {
            if (decorator == null) {
                continue;
            }
            Matcher matcher = ROUTE_DECORATOR_PATTERN.matcher(decorator.trim());
            if (!matcher.matches()) {
                continue;
            }
            String identifier = matcher.group(1);
            String method = matcher.group(2).toLowerCase();
            if (!HTTP_METHODS.contains(method)) {
                continue;
            }
            String httpMethod = method.toUpperCase();
            String url = extractFirstStringArg(matcher.group(3));
            if (url == null) {
                log.debug("FastAPI route decorator without parseable string path: {} (file={}, line={})",
                        decorator, module.getFilePath(), function.getLineStart());
                url = "";
            }
            if (routerPrefix != null && !"app".equals(identifier)) {
                url = routerPrefix + url;
            }
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

        String entryId = sha256Prefix(filePath + ":" + httpMethod + ":" + url);
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
                .framework(FRAMEWORK_FASTAPI)
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
        appendField(sb, "subType", "FASTAPI_ROUTE", true);
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
