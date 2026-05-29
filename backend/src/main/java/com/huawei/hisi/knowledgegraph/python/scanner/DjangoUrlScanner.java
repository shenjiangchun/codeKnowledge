package com.huawei.hisi.knowledgegraph.python.scanner;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.huawei.hisi.knowledgegraph.python.model.PyCall;
import com.huawei.hisi.knowledgegraph.python.model.PyClass;
import com.huawei.hisi.knowledgegraph.python.model.PyModule;
import com.huawei.hisi.neo4j.model.EntryPointNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Scans a parsed {@link PyModule} for Django view entry points.
 *
 * <h3>Two detection modes</h3>
 * <ol>
 *   <li><strong>URL configuration</strong> — if the module file path ends with
 *       {@code urls.py}, the scanner walks the {@link PyCall} list looking for
 *       calls to {@code path(...)}, {@code re_path(...)}, or {@code url(...)}.
 *       The first string argument is captured as the URL pattern.</li>
 *   <li><strong>Class-based views (CBV)</strong> — any {@link PyClass} whose base
 *       class list contains "View", "ViewSet", or "APIView" (substring match)
 *       emits one entry point per class.</li>
 * </ol>
 */
@Slf4j
@Component
public class DjangoUrlScanner {

    private static final Set<String> URL_FUNCTIONS = Set.of("path", "re_path", "url");

    private static final Set<String> CBV_BASE_KEYWORDS = Set.of("View", "ViewSet", "APIView");

    private static final String LANGUAGE_PYTHON = "python";
    private static final String FRAMEWORK_DJANGO = "django";

    /**
     * Scan the given module and produce entry points for Django URL patterns
     * and class-based views.
     */
    public List<EntryPointNode> scanModule(PyModule module, String projectPath) {
        if (module == null) {
            return List.of();
        }

        List<EntryPointNode> entries = new ArrayList<>();

        // Mode 1: URL config scanning (only in urls.py)
        if (module.getFilePath() != null && module.getFilePath().endsWith("urls.py")) {
            for (PyCall call : module.getCalls()) {
                EntryPointNode entry = classifyUrlCall(call, module, projectPath);
                if (entry != null) {
                    entries.add(entry);
                }
            }
        }

        // Mode 2: CBV scanning (any module)
        for (PyClass clazz : module.getClasses()) {
            if (isCbvClass(clazz)) {
                entries.add(buildCbvEntry(module, clazz, projectPath));
            }
        }

        return List.copyOf(entries);
    }

    private EntryPointNode classifyUrlCall(PyCall call,
                                           PyModule module,
                                           String projectPath) {
        String expr = call.getCalleeExpression();
        if (expr == null) {
            return null;
        }
        // Extract the function name (last segment)
        String funcName = expr.contains(".") ? expr.substring(expr.lastIndexOf('.') + 1) : expr;
        if (!URL_FUNCTIONS.contains(funcName)) {
            return null;
        }

        String urlPattern = call.getFirstStringArg();
        if (urlPattern == null) {
            urlPattern = "";
        }

        String entryId = sha256Prefix(module.getFilePath() + ":DJANGO:" + urlPattern + ":" + call.getLineNumber());
        String entryKey = urlPattern;
        String entryInfo = buildUrlEntryInfoJson(urlPattern, expr, call.getEnclosingFunction(),
                module.getFilePath(), call.getLineNumber());

        return EntryPointNode.builder()
                .entryId(entryId)
                .entryType(EntryPointNode.TYPE_HTTP)
                .entryKey(entryKey)
                .entryInfo(entryInfo)
                .projectPath(projectPath)
                .language(LANGUAGE_PYTHON)
                .framework(FRAMEWORK_DJANGO)
                .build();
    }

    private boolean isCbvClass(PyClass clazz) {
        for (String base : clazz.getBaseClasses()) {
            for (String keyword : CBV_BASE_KEYWORDS) {
                if (base.contains(keyword)) {
                    return true;
                }
            }
        }
        return false;
    }

    private EntryPointNode buildCbvEntry(PyModule module,
                                         PyClass clazz,
                                         String projectPath) {
        String entryId = sha256Prefix(module.getFilePath() + ":CBV:" + clazz.getName());
        String entryKey = clazz.getName();
        String entryInfo = buildCbvEntryInfoJson(clazz, module.getFilePath());

        return EntryPointNode.builder()
                .entryId(entryId)
                .entryType(EntryPointNode.TYPE_HTTP)
                .entryKey(entryKey)
                .entryInfo(entryInfo)
                .projectPath(projectPath)
                .language(LANGUAGE_PYTHON)
                .framework(FRAMEWORK_DJANGO)
                .build();
    }

    private static String buildUrlEntryInfoJson(String urlPattern,
                                                String callExpression,
                                                String enclosingFunction,
                                                String filePath,
                                                int lineNumber) {
        StringBuilder sb = new StringBuilder(128);
        sb.append('{');
        appendField(sb, "subType", "DJANGO_VIEW", true);
        appendField(sb, "urlPattern", urlPattern, false);
        appendField(sb, "callExpression", callExpression, false);
        appendField(sb, "enclosingFunction", enclosingFunction, false);
        appendField(sb, "filePath", filePath, false);
        sb.append(",\"lineNumber\":").append(lineNumber);
        sb.append('}');
        return sb.toString();
    }

    private static String buildCbvEntryInfoJson(PyClass clazz, String filePath) {
        StringBuilder sb = new StringBuilder(128);
        sb.append('{');
        appendField(sb, "subType", "DJANGO_CBV", true);
        appendField(sb, "className", clazz.getName(), false);
        appendField(sb, "baseClasses", String.join(",", clazz.getBaseClasses()), false);
        appendField(sb, "filePath", filePath, false);
        sb.append(",\"lineNumber\":").append(clazz.getLineStart());
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
