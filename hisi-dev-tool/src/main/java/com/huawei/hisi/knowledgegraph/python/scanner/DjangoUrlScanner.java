package com.huawei.hisi.knowledgegraph.python.scanner;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.huawei.hisi.knowledgegraph.python.model.PyCall;
import com.huawei.hisi.knowledgegraph.python.model.PyModule;
import com.huawei.hisi.neo4j.model.EntryPointNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Scans a parsed {@link PyModule} for Django view entry points.
 *
 * <h3>Detection model</h3>
 * Entry points are emitted ONLY for {@code path(...)} / {@code re_path(...)} /
 * {@code url(...)} calls in modules whose file path ends with {@code urls.py}.
 * For each registration the second positional argument (the view callable
 * expression) is resolved through {@link DjangoViewResolver} into a concrete
 * {@code methodNodeId} so downstream call-chain traversal can follow the
 * {@code (:EntryPoint)-->[methodNodeId]-->(:Method)} link.
 *
 * <p>Class-based-views (CBV) are emitted as a single class-level entry point
 * anchored on a representative handler method (one of
 * {@code get/post/put/delete/patch/dispatch}). They are NOT split into one
 * entry per HTTP method (deferred — see plan).
 *
 * <p>The previous "scan any module for CBV classes" mode has been removed —
 * untriggered CBV classes (those not registered to a URL) no longer produce
 * orphan entry points.
 */
@Slf4j
@Component
public class DjangoUrlScanner {

    private static final Set<String> URL_FUNCTIONS = Set.of("path", "re_path", "url");

    private static final Pattern INCLUDE_PATTERN = Pattern.compile(
            "include\\s*\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)");

    public record IncludeMapping(String prefix, String targetModulePath) {}

    private static final String LANGUAGE_PYTHON = "python";
    private static final String FRAMEWORK_DJANGO = "django";

    private final DjangoViewResolver viewResolver = new DjangoViewResolver();

    /**
     * Scan the given module and produce entry points for Django URL patterns.
     *
     * @param module          parsed module under scan
     * @param projectPath     project root (used to tag the entry point)
     * @param modulesByPath   global map of {@code modulePath -> PyModule} needed
     *                        for cross-module view-callable resolution. May be
     *                        {@code null} or empty when invoked from tests that
     *                        don't care about resolution; in that case no
     *                        methodNodeId will be set.
     */
    public List<EntryPointNode> scanModule(PyModule module,
                                           String projectPath,
                                           Map<String, PyModule> modulesByPath) {
        if (module == null) {
            return List.of();
        }
        if (module.getFilePath() == null || !module.getFilePath().endsWith("urls.py")) {
            return List.of();
        }

        List<EntryPointNode> entries = new ArrayList<>();
        for (PyCall call : module.getCalls()) {
            entries.addAll(classifyUrlCall(call, module, projectPath, modulesByPath));
        }
        return List.copyOf(entries);
    }

    /**
     * Backwards-compatible overload (no cross-module resolution) used by older
     * callers / unit tests. {@code methodNodeId} will be {@code null} when
     * resolution is not possible.
     */
    public List<EntryPointNode> scanModule(PyModule module, String projectPath) {
        return scanModule(module, projectPath, Map.of());
    }

    public List<IncludeMapping> scanIncludes(PyModule module) {
        if (module == null || module.getFilePath() == null
                || !module.getFilePath().endsWith("urls.py")) {
            return List.of();
        }
        List<IncludeMapping> result = new ArrayList<>();
        for (PyCall call : module.getCalls()) {
            String expr = call.getCalleeExpression();
            if (expr == null) continue;
            String funcName = expr.contains(".") ? expr.substring(expr.lastIndexOf('.') + 1) : expr;
            if (!URL_FUNCTIONS.contains(funcName)) continue;

            String viewExpression = call.getSecondPositionalArg();
            if (viewExpression == null) continue;
            Matcher m = INCLUDE_PATTERN.matcher(viewExpression);
            if (m.find()) {
                String prefix = call.getFirstStringArg() != null ? call.getFirstStringArg() : "";
                String targetModule = m.group(1).replace(".urls", "").replace(".", "/");
                String dotted = m.group(1);
                result.add(new IncludeMapping(prefix, dotted));
            }
        }
        return result;
    }

    public static void applyIncludes(List<EntryPointNode> entries,
                                     List<IncludeMapping> includes,
                                     Map<String, PyModule> modulesByPath) {
        if (includes.isEmpty()) return;
        for (IncludeMapping inc : includes) {
            String targetUrlsModule = inc.targetModulePath();
            for (EntryPointNode ep : entries) {
                if (ep.getFramework() == null || !FRAMEWORK_DJANGO.equals(ep.getFramework())) {
                    continue;
                }
                String entryInfo = ep.getEntryInfo();
                if (entryInfo == null) continue;
                PyModule targetMod = modulesByPath.get(targetUrlsModule);
                if (targetMod == null) continue;
                String targetFile = targetMod.getFilePath();
                if (targetFile != null && entryInfo.contains(escapeJson(targetFile))) {
                    String oldKey = ep.getEntryKey();
                    if (oldKey != null && !oldKey.startsWith(inc.prefix())) {
                        ep.setEntryKey(inc.prefix() + oldKey);
                    }
                }
            }
        }
    }

    private List<EntryPointNode> classifyUrlCall(PyCall call,
                                           PyModule module,
                                           String projectPath,
                                           Map<String, PyModule> modulesByPath) {
        String expr = call.getCalleeExpression();
        if (expr == null) {
            return List.of();
        }
        String funcName = expr.contains(".") ? expr.substring(expr.lastIndexOf('.') + 1) : expr;
        if (!URL_FUNCTIONS.contains(funcName)) {
            return List.of();
        }

        String urlPattern = call.getFirstStringArg();
        if (urlPattern == null) {
            urlPattern = "";
        }
        String viewExpression = call.getSecondPositionalArg();

        if (viewExpression != null && INCLUDE_PATTERN.matcher(viewExpression).find()) {
            return List.of();
        }

        List<DjangoViewResolver.ResolvedView> resolvedViews = List.of();
        if (viewExpression != null && modulesByPath != null && !modulesByPath.isEmpty()) {
            resolvedViews = viewResolver.resolveAll(viewExpression, module, modulesByPath);
            if (resolvedViews.isEmpty()) {
                log.warn("[DjangoUrlScanner] Cannot resolve view expression '{}' at {}:{} — "
                                + "entry point will have methodNodeId=null (call chain will be empty)",
                        viewExpression, module.getFilePath(), call.getLineNumber());
            }
        }

        if (resolvedViews.size() <= 1) {
            String methodNodeId = resolvedViews.isEmpty() ? null : resolvedViews.get(0).computeNodeId();
            String entryId = sha256Prefix(module.getFilePath() + ":DJANGO:" + urlPattern + ":" + call.getLineNumber());
            String entryInfo = buildUrlEntryInfoJson(urlPattern, expr, viewExpression,
                    call.getEnclosingFunction(), module.getFilePath(), call.getLineNumber());
            return List.of(EntryPointNode.builder()
                    .entryId(entryId)
                    .entryType(EntryPointNode.TYPE_HTTP)
                    .entryKey(urlPattern)
                    .entryInfo(entryInfo)
                    .projectPath(projectPath)
                    .language(LANGUAGE_PYTHON)
                    .framework(FRAMEWORK_DJANGO)
                    .methodNodeId(methodNodeId)
                    .build());
        }

        List<EntryPointNode> entries = new ArrayList<>();
        for (DjangoViewResolver.ResolvedView rv : resolvedViews) {
            String httpMethod = rv.qualName.contains(".")
                    ? rv.qualName.substring(rv.qualName.lastIndexOf('.') + 1)
                    : rv.qualName;
            String entryId = sha256Prefix(module.getFilePath() + ":DJANGO:" + urlPattern
                    + ":" + httpMethod + ":" + call.getLineNumber());
            String entryInfo = buildUrlEntryInfoJson(urlPattern, expr, viewExpression,
                    call.getEnclosingFunction(), module.getFilePath(), call.getLineNumber());
            entries.add(EntryPointNode.builder()
                    .entryId(entryId)
                    .entryType(EntryPointNode.TYPE_HTTP)
                    .entryKey(urlPattern + " [" + httpMethod.toUpperCase() + "]")
                    .entryInfo(entryInfo)
                    .projectPath(projectPath)
                    .language(LANGUAGE_PYTHON)
                    .framework(FRAMEWORK_DJANGO)
                    .methodNodeId(rv.computeNodeId())
                    .build());
        }
        return entries;
    }

    private static String buildUrlEntryInfoJson(String urlPattern,
                                                String callExpression,
                                                String viewExpression,
                                                String enclosingFunction,
                                                String filePath,
                                                int lineNumber) {
        StringBuilder sb = new StringBuilder(160);
        sb.append('{');
        appendField(sb, "subType", "DJANGO_VIEW", true);
        appendField(sb, "urlPattern", urlPattern, false);
        appendField(sb, "callExpression", callExpression, false);
        appendField(sb, "viewExpression", viewExpression, false);
        appendField(sb, "enclosingFunction", enclosingFunction, false);
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
