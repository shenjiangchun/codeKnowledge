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
 * Used by KnowledgeGraphBuilder to decide whether to build Java, Python,
 * or TypeScript (via codegraph sidecar) knowledge graph.
 */
@Slf4j
public final class ProjectLanguageDetector {

    private ProjectLanguageDetector() {
        // utility class
    }

    /**
     * Language enum - JAVA / PYTHON handled by hisi native scanners;
     * TYPESCRIPT / JAVASCRIPT (含 Vue) 由 codegraph sidecar 处理。
     */
    public enum Language {
        JAVA,
        PYTHON,
        TYPESCRIPT,
        JAVASCRIPT
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

        // 3. Check for TypeScript/Vue markers (Vue 视为 TypeScript)
        if (hasTypeScriptMarkers(root)) {
            log.info("[LanguageDetector] Detected TypeScript/Vue project (tsconfig.json/vue.config.js/nuxt.config.ts or .ts/.tsx/.vue files)");
            return Language.TYPESCRIPT;
        }

        // 4. Check for JavaScript markers
        if (hasJavaScriptMarkers(root)) {
            log.info("[LanguageDetector] Detected JavaScript project (package.json without tsconfig.json or .js/.jsx files)");
            return Language.JAVASCRIPT;
        }

        // 5. Count source files to decide
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
     * Check if the project has TypeScript/Vue marker files.
     *
     * <p>判定条件（满足任一即视为 TS 项目，由 codegraph sidecar 处理）：
     * <ul>
     *   <li>根目录存在 {@code tsconfig.json}</li>
     *   <li>根目录存在 {@code vue.config.js} 或 {@code nuxt.config.ts}（Vue 视为 TS）</li>
     *   <li>根目录前 1000 个非排除目录文件中存在 {@code .ts/.tsx/.vue} 文件</li>
     * </ul>
     * 排除目录沿用 {@link com.huawei.hisi.service.CodeAnalysisCoreService#EXCLUDED_SCAN_DIRS}，
     * 避免 {@code node_modules} 等目录里的第三方 .ts 文件造成误判。</p>
     */
    private static boolean hasTypeScriptMarkers(Path root) {
        if (Files.exists(root.resolve("tsconfig.json"))) {
            return true;
        }
        // Vue 项目（vue.config.js / nuxt.config.ts）视为 TypeScript
        if (Files.exists(root.resolve("vue.config.js"))
                || Files.exists(root.resolve("nuxt.config.ts"))) {
            return true;
        }
        try (Stream<Path> walk = Files.walk(root, 10)) {
            return walk
                .filter(Files::isRegularFile)
                .filter(p -> {
                    for (Path seg : p) {
                        if (com.huawei.hisi.service.CodeAnalysisCoreService
                                .EXCLUDED_SCAN_DIRS.contains(seg.toString())) {
                            return false;
                        }
                    }
                    return true;
                })
                .limit(1000)
                .anyMatch(p -> {
                    String name = p.getFileName().toString();
                    return name.endsWith(".ts") || name.endsWith(".tsx") || name.endsWith(".vue");
                });
        } catch (Exception e) {
            log.warn("[LanguageDetector] Failed to walk for TypeScript markers, defaulting to false: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Check if the project has JavaScript marker files.
     *
     * <p>判定条件（在 TS 之后判断，避免 TS 项目被识别为 JS）：
     * <ul>
     *   <li>根目录存在 {@code package.json}（且没有 tsconfig.json，已被 hasTypeScriptMarkers 先命中）</li>
     *   <li>根目录前 1000 个非排除目录文件中存在 {@code .js/.jsx} 文件</li>
     * </ul>
     */
    private static boolean hasJavaScriptMarkers(Path root) {
        if (Files.exists(root.resolve("package.json"))) {
            return true;
        }
        try (Stream<Path> walk = Files.walk(root, 10)) {
            return walk
                .filter(Files::isRegularFile)
                .filter(p -> {
                    for (Path seg : p) {
                        if (com.huawei.hisi.service.CodeAnalysisCoreService
                                .EXCLUDED_SCAN_DIRS.contains(seg.toString())) {
                            return false;
                        }
                    }
                    return true;
                })
                .limit(1000)
                .anyMatch(p -> {
                    String name = p.getFileName().toString();
                    return name.endsWith(".js") || name.endsWith(".jsx");
                });
        } catch (Exception e) {
            log.warn("[LanguageDetector] Failed to walk for JavaScript markers, defaulting to false: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Count Java/Python vs TS/JS source files in the project (limit to first 1000 files to avoid performance issues).
     * 仅在各 marker 都未命中时作为兜底，按文件数判定。
     */
    private static Language countSourceFiles(Path root) {
        int javaCount = 0;
        int pythonCount = 0;
        int tsCount = 0;
        int jsCount = 0;

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
                } else if (fileName.endsWith(".ts") || fileName.endsWith(".tsx") || fileName.endsWith(".vue")) {
                    tsCount++;
                } else if (fileName.endsWith(".js") || fileName.endsWith(".jsx")) {
                    jsCount++;
                }
            }

            log.info("[LanguageDetector] Source file counts: Java={}, Python={}, TS/JS={}",
                    javaCount, pythonCount, tsCount + jsCount);

        } catch (Exception e) {
            log.warn("[LanguageDetector] Failed to count source files, defaulting to JAVA: {}", e.getMessage());
            return Language.JAVA;
        }

        if (pythonCount > javaCount && pythonCount > tsCount + jsCount) {
            return Language.PYTHON;
        }
        if (tsCount + jsCount > javaCount && tsCount + jsCount > pythonCount) {
            return tsCount >= jsCount ? Language.TYPESCRIPT : Language.JAVASCRIPT;
        }
        return Language.JAVA; // default to Java if counts are equal or both zero
    }
}
