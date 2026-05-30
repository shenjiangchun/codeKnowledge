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
 * Scans a parsed {@link PyModule} for Celery task definitions and produces
 * {@link EntryPointNode} instances representing each task.
 *
 * <h3>Detection rule</h3>
 * A function whose decorator list contains {@code celery.task},
 * {@code app.task}, or {@code shared_task} (exact or prefix match before
 * an opening paren).
 */
@Slf4j
@Component
public class CeleryTaskScanner {

    /** Matches {@code <identifier>.task} or {@code <identifier>.task(<args>)}. */
    private static final Pattern DOT_TASK_PATTERN = Pattern.compile(
            "^([A-Za-z_][A-Za-z0-9_]*)\\.task(?:\\((.*)\\))?\\s*$",
            Pattern.DOTALL);

    /** Matches {@code shared_task} or {@code shared_task(<args>)}. */
    private static final Pattern SHARED_TASK_PATTERN = Pattern.compile(
            "^shared_task(?:\\((.*)\\))?\\s*$",
            Pattern.DOTALL);

    /** Extracts {@code name="value"} or {@code name='value'} from decorator args. */
    private static final Pattern NAME_KW_PATTERN = Pattern.compile(
            "name\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')");

    private static final String LANGUAGE_PYTHON = "python";
    private static final String FRAMEWORK_CELERY = "celery";

    /**
     * Scan the given module and produce one {@link EntryPointNode} per Celery
     * task handler discovered.
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
            String trimmed = decorator.trim();
            String taskName = matchCeleryDecorator(trimmed, function);
            if (taskName != null) {
                result.add(buildEntry(module, function, taskName, projectPath));
            }
        }
        return result;
    }

    /**
     * Returns the task name if the decorator matches a Celery task pattern, null otherwise.
     */
    private String matchCeleryDecorator(String decorator, PyFunction function) {
        // Try <identifier>.task or <identifier>.task(...)
        Matcher dotMatcher = DOT_TASK_PATTERN.matcher(decorator);
        if (dotMatcher.matches()) {
            String args = dotMatcher.group(2);
            return resolveTaskName(args, function);
        }

        // Try shared_task or shared_task(...)
        Matcher sharedMatcher = SHARED_TASK_PATTERN.matcher(decorator);
        if (sharedMatcher.matches()) {
            String args = sharedMatcher.group(1);
            return resolveTaskName(args, function);
        }

        return null;
    }

    private String resolveTaskName(String args, PyFunction function) {
        if (args != null) {
            Matcher nameMatcher = NAME_KW_PATTERN.matcher(args);
            if (nameMatcher.find()) {
                String name = nameMatcher.group(1) != null ? nameMatcher.group(1) : nameMatcher.group(2);
                if (name != null && !name.isEmpty()) {
                    return name;
                }
            }
        }
        // Fall back to qualified name or plain name
        return function.getQualName() != null ? function.getQualName() : function.getName();
    }

    private EntryPointNode buildEntry(PyModule module,
                                      PyFunction function,
                                      String taskName,
                                      String projectPath) {
        String filePath = module.getFilePath();
        int lineNumber = function.getLineStart();

        String entryId = sha256Prefix(filePath + ":CELERY:" + taskName);
        String entryInfo = buildEntryInfoJson(taskName, function.getName(), filePath, lineNumber);

        // Compute methodNodeId: the task function is directly decorated,
        // so we can resolve it from the same module without cross-module lookup.
        String methodNodeId = PythonKnowledgeGraphBuilder.computeMethodNodeId(
                module.getModulePath(), function.getQualName(), function.getParamNames());

        return EntryPointNode.builder()
                .entryId(entryId)
                .entryType(EntryPointNode.TYPE_MQ_CONSUMER)
                .entryKey(taskName)
                .entryInfo(entryInfo)
                .methodNodeId(methodNodeId)
                .projectPath(projectPath)
                .language(LANGUAGE_PYTHON)
                .framework(FRAMEWORK_CELERY)
                .build();
    }

    private static String buildEntryInfoJson(String taskName,
                                             String functionName,
                                             String filePath,
                                             int lineNumber) {
        StringBuilder sb = new StringBuilder(128);
        sb.append('{');
        appendField(sb, "subType", "CELERY_TASK", true);
        appendField(sb, "taskName", taskName, false);
        appendField(sb, "functionName", functionName, false);
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
