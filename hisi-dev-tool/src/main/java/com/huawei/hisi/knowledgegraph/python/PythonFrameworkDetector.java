package com.huawei.hisi.knowledgegraph.python;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

import lombok.extern.slf4j.Slf4j;

/**
 * Detects which Python web/MQ frameworks are present in a project by
 * inspecting dependency manifests ({@code requirements.txt},
 * {@code pyproject.toml}, {@code setup.py}, {@code Pipfile}).
 *
 * <p>The result drives which scanners are run by
 * {@link PythonKnowledgeGraphBuilder}.
 */
@Slf4j
public final class PythonFrameworkDetector {

    /** Supported Python frameworks. */
    public enum Framework {
        FASTAPI("fastapi"),
        DJANGO("django"),
        FLASK("flask");

        private final String token;

        Framework(String token) {
            this.token = token;
        }

        public String token() {
            return token;
        }
    }

    private static final String[] MANIFEST_FILES = {
            "requirements.txt",
            "pyproject.toml",
            "setup.py",
            "setup.cfg",
            "Pipfile"
    };

    private PythonFrameworkDetector() {
    }

    /**
     * Inspect the project root and return the set of detected frameworks.
     * An empty set is returned if no manifest can be read.
     */
    public static Set<Framework> detect(String projectPath) {
        Set<Framework> found = EnumSet.noneOf(Framework.class);
        if (projectPath == null || projectPath.isEmpty()) {
            return found;
        }

        for (String manifest : MANIFEST_FILES) {
            Path p = Paths.get(projectPath, manifest);
            if (!Files.isRegularFile(p)) {
                continue;
            }
            String content;
            try {
                content = Files.readString(p, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
            } catch (IOException e) {
                log.debug("Failed to read manifest {}: {}", p, e.getMessage());
                continue;
            }
            for (Framework f : Framework.values()) {
                if (content.contains(f.token())) {
                    found.add(f);
                }
            }
        }

        // Fallback: if no manifest found, scan Python source files for framework imports
        if (found.isEmpty()) {
            log.info("[Python KG] No manifest files found, trying import-based detection");
            try (java.util.stream.Stream<Path> walk = Files.walk(Paths.get(projectPath))) {
                String importLines = walk
                    .filter(p -> p.toString().endsWith(".py"))
                    .limit(100)
                    .map(p -> {
                        try { return Files.readString(p, StandardCharsets.UTF_8); }
                        catch (IOException e) { return ""; }
                    })
                    .collect(java.util.stream.Collectors.joining("\n"));

                String lower = importLines.toLowerCase(Locale.ROOT);
                for (Framework f : Framework.values()) {
                    if (lower.contains("import " + f.token()) || lower.contains("from " + f.token())) {
                        found.add(f);
                    }
                }
            } catch (IOException e) {
                log.warn("[Python KG] Fallback detection failed: {}", e.getMessage());
            }
        }

        log.info("[Python KG] Frameworks detected for {}: {}", projectPath, found);
        return found;
    }
}
