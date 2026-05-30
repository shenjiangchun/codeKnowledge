package com.huawei.hisi.knowledgegraph.util;

import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Utility to detect the primary programming language of a project.
 * Used by KnowledgeGraphBuilder to decide whether to build Java or Python
 * knowledge graph.
 */
@Slf4j
public final class ProjectLanguageDetector {

    private ProjectLanguageDetector() {
        // utility class
    }

    /**
     * Language enum - currently only JAVA and PYTHON are supported.
     */
    public enum Language {
        JAVA,
        PYTHON
    }

    /**
     * Detect the primary language of a project by checking for language-specific
     * project files and counting source files.
     *
     * @param projectPath path to project root
     * @return detected language (defaults to JAVA if no clear signal)
     */
    public static Language detectLanguage(String projectPath) {
        Path root = Paths.get(projectPath);
        if (!Files.exists(root)) {
            log.warn("[LanguageDetector] Project path does not exist: {}, defaulting to JAVA", projectPath);
            return Language.JAVA;
        }

        // 1. Check for Java-specific project markers
        if (hasJavaMarkers(root)) {
            log.info("[LanguageDetector] Detected Java project (pom.xml/build.gradle found)");
            return Language.JAVA;
        }

        // 2. Check for Python-specific project markers
        if (hasPythonMarkers(root)) {
            log.info("[LanguageDetector] Detected Python project (requirements.txt/pyproject.toml/setup.py found)");
            return Language.PYTHON;
        }

        // 3. Count source files to decide
        Language counted = countSourceFiles(root);
        log.info("[LanguageDetector] Language decided by source file count: {}", counted);
        return counted;
    }

    /**
     * Check if the project has Java-specific marker files.
     */
    private static boolean hasJavaMarkers(Path root) {
        return Files.exists(root.resolve("pom.xml"))
            || Files.exists(root.resolve("build.gradle"))
            || Files.exists(root.resolve("build.gradle.kts"))
            || Files.exists(root.resolve("src/main/java"));
    }

    /**
     * Check if the project has Python-specific marker files.
     */
    private static boolean hasPythonMarkers(Path root) {
        return Files.exists(root.resolve("requirements.txt"))
            || Files.exists(root.resolve("pyproject.toml"))
            || Files.exists(root.resolve("setup.py"))
            || Files.exists(root.resolve("setup.cfg"))
            || Files.exists(root.resolve("Pipfile"))
            || Files.exists(root.resolve("Pipfile.lock"));
    }

    /**
     * Count Java vs Python source files in the project (limit to first 1000 files to avoid performance issues).
     */
    private static Language countSourceFiles(Path root) {
        int javaCount = 0;
        int pythonCount = 0;

        try (Stream<Path> walk = Files.walk(root, 10)) {
            Set<Path> files = walk
                .filter(Files::isRegularFile)
                .filter(p -> {
                    // 排除 .worktrees / target / build / .git 等目录下的文件，避免误判语言
                    for (Path seg : p) {
                        if (com.huawei.hisi.service.CodeAnalysisCoreService
                                .EXCLUDED_SCAN_DIRS.contains(seg.toString())) {
                            return false;
                        }
                    }
                    return true;
                })
                .limit(1000)
                .collect(Collectors.toSet());

            for (Path file : files) {
                String fileName = file.getFileName().toString();
                if (fileName.endsWith(".java")) {
                    javaCount++;
                } else if (fileName.endsWith(".py")) {
                    pythonCount++;
                }
            }

            log.info("[LanguageDetector] Source file counts: Java={}, Python={}", javaCount, pythonCount);

        } catch (Exception e) {
            log.warn("[LanguageDetector] Failed to count source files, defaulting to JAVA: {}", e.getMessage());
            return Language.JAVA;
        }

        if (pythonCount > javaCount) {
            return Language.PYTHON;
        }
        return Language.JAVA; // default to Java if counts are equal or both zero
    }
}
