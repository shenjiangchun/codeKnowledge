package com.huawei.hisi.knowledgegraph.service;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.huawei.hisi.knowledgegraph.exception.NoCheckpointException;
import com.huawei.hisi.knowledgegraph.link.CrossServiceLinker;
import com.huawei.hisi.knowledgegraph.vector.VectorWriter;
import com.huawei.hisi.neo4j.model.GenerationCheckpointNode;
import com.huawei.hisi.neo4j.model.MethodNode;
import com.huawei.hisi.neo4j.repository.Neo4jGenerationCheckpointRepository;
import com.huawei.hisi.neo4j.repository.Neo4jMethodNodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Incrementally refreshes the knowledge graph by detecting changed files
 * since the last checkpoint, deleting stale nodes, and rebuilding method
 * nodes from the updated source files.
 *
 * <p>Limitations: This service rebuilds <strong>method nodes</strong> only.
 * Call relations, entry points, and interface maps require a full generation
 * to update. Cross-service linking is re-run after rebuild.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IncrementalRefreshService {

    private final GitStatusService gitStatusService;
    private final Neo4jGenerationCheckpointRepository checkpointRepository;
    private final VectorWriter vectorWriter;
    private final CrossServiceLinker crossServiceLinker;
    private final Neo4jMethodNodeRepository methodNodeRepository;

    /**
     * Incrementally refresh the knowledge graph for a project.
     *
     * @param projectPath absolute path to the project
     * @return a RefreshResult with details about what was refreshed
     * @throws NoCheckpointException if no checkpoint exists for this project
     */
    public RefreshResult refresh(String projectPath) throws IOException {
        Objects.requireNonNull(projectPath, "projectPath");

        // Normalize path to ensure consistency (反斜杠→正斜杠)
        String normalizedProjectPath = com.huawei.hisi.knowledgegraph.util.ProjectPathResolver.normalize(projectPath);
        log.info("[IncrementalRefresh] Normalized path: {} -> {}", projectPath, normalizedProjectPath);

        // 1. Find checkpoint — if none, throw NoCheckpointException (caller maps to 409)
        GenerationCheckpointNode checkpoint = checkpointRepository
                .findByProjectPath(normalizedProjectPath)
                .orElseThrow(() -> new NoCheckpointException(normalizedProjectPath));

        // 2. Assert working directory is clean — throws WorkingDirDirtyException (caller maps to 412)
        gitStatusService.assertClean(normalizedProjectPath);

        // 3. Get current commit
        String currentCommit = gitStatusService.getCurrentCommitHash(normalizedProjectPath);
        String lastCommit = checkpoint.getLastCommit();

        // 3a. If checkpoint has no valid commit info, cannot do incremental diff
        if ("NO_COMMIT".equals(lastCommit) && currentCommit == null) {
            log.info("[IncrementalRefresh] No git history available for project={}, returning noop", normalizedProjectPath);
            return RefreshResult.noop();
        }

        // 4. If same commit -> noop
        if (currentCommit != null && currentCommit.equals(lastCommit)) {
            return RefreshResult.noop();
        }

        // 5. Get changed files via JGit diff
        List<String> changedFiles = gitStatusService.getChangedFilesJgit(
                normalizedProjectPath, lastCommit, currentCommit);
        if (changedFiles.isEmpty()) {
            return RefreshResult.noop();
        }

        // 6. Delete existing nodes for each changed file
        for (String file : changedFiles) {
            vectorWriter.deleteByFilePath(file, normalizedProjectPath);
        }
        int deleted = changedFiles.size();

        // 7. Rebuild method nodes for changed files that still exist on disk
        int rebuilt = rebuildMethodNodes(normalizedProjectPath, changedFiles);

        // 8. Cross-service linking (best-effort)
        try {
            crossServiceLinker.link(List.of(normalizedProjectPath));
        } catch (Exception e) {
            log.warn("Cross-service re-linking failed: {}", e.getMessage());
        }

        // 9. Update checkpoint
        String currentBranch = gitStatusService.getCurrentBranch(normalizedProjectPath);
        checkpointRepository.upsertCheckpoint(normalizedProjectPath, currentCommit, currentBranch);

        return new RefreshResult(false, changedFiles.size(), deleted, rebuilt);
    }

    /**
     * Re-parse changed Java files and save method nodes to Neo4j.
     * Files that no longer exist (deleted) are skipped — their nodes were
     * already removed in the deletion step.
     *
     * <p>Note: This rebuilds method nodes only — call relations, entry points,
     * and interface maps require a full generation to update.</p>
     *
     * @param projectPath  the project root path
     * @param changedFiles list of relative file paths that changed
     * @return the total number of method nodes rebuilt
     */
    private int rebuildMethodNodes(String projectPath, List<String> changedFiles) {
        int totalRebuilt = 0;
        JavaParser javaParser = new JavaParser();

        for (String changedFile : changedFiles) {
            if (!changedFile.endsWith(".java")) {
                continue;
            }

            Path filePath = Paths.get(projectPath, changedFile);
            if (!Files.exists(filePath)) {
                log.debug("File deleted, skipping rebuild: {}", changedFile);
                continue;
            }

            try {
                List<MethodNode> nodes = parseJavaFile(javaParser, filePath.toString(), projectPath);
                for (MethodNode node : nodes) {
                    methodNodeRepository.save(node);
                }
                totalRebuilt += nodes.size();
                log.debug("Rebuilt {} method nodes from: {}", nodes.size(), changedFile);
            } catch (Exception e) {
                log.warn("Failed to rebuild methods from {}: {}", changedFile, e.getMessage());
            }
        }

        log.info("Rebuilt {} method nodes from {} changed files", totalRebuilt, changedFiles.size());
        return totalRebuilt;
    }

    /**
     * Parse a single Java file and extract {@link MethodNode} objects.
     * Uses a lightweight JavaParser (no symbol solver) for speed.
     */
    private List<MethodNode> parseJavaFile(JavaParser javaParser, String filePath, String projectPath) {
        List<MethodNode> nodes = new ArrayList<>();

        File file = new File(filePath);
        if (!file.exists()) {
            return nodes;
        }

        try {
            ParseResult<CompilationUnit> result = javaParser.parse(file);
            if (!result.isSuccessful() || result.getResult().isEmpty()) {
                return nodes;
            }

            CompilationUnit cu = result.getResult().get();
            String packageName = cu.getPackageDeclaration()
                    .map(pd -> pd.getNameAsString())
                    .orElse("");

            cu.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz -> {
                String className = packageName.isEmpty()
                        ? clazz.getNameAsString()
                        : packageName + "." + clazz.getNameAsString();

                clazz.findAll(MethodDeclaration.class).forEach(method -> {
                    String nodeId = projectPath + ":" + className + "." +
                            method.getNameAsString() + "." +
                            Integer.toHexString(method.getSignature().hashCode());

                    String methodBody = method.getBody()
                            .map(body -> body.toString().replaceAll("\\s+", " ").trim())
                            .orElse("");

                    MethodNode node = MethodNode.builder()
                            .nodeId(nodeId)
                            .className(className)
                            .methodName(method.getNameAsString())
                            .signature(method.getSignature().asString())
                            .filePath(filePath)
                            .startLine(method.getBegin().map(p -> p.line).orElse(0))
                            .endLine(method.getEnd().map(p -> p.line).orElse(0))
                            .complexity(calculateComplexity(method))
                            .methodBody(methodBody)
                            .projectPath(projectPath)
                            .build();

                    nodes.add(node);
                });
            });
        } catch (Exception e) {
            log.error("Failed to parse Java file: {}", filePath, e);
        }

        return nodes;
    }

    /**
     * Calculate cyclomatic complexity for a method.
     */
    private int calculateComplexity(MethodDeclaration method) {
        int complexity = 1;
        complexity += method.findAll(com.github.javaparser.ast.stmt.IfStmt.class).size();
        complexity += method.findAll(com.github.javaparser.ast.stmt.ForStmt.class).size();
        complexity += method.findAll(com.github.javaparser.ast.stmt.WhileStmt.class).size();
        complexity += method.findAll(com.github.javaparser.ast.stmt.SwitchStmt.class).size();
        complexity += method.findAll(com.github.javaparser.ast.stmt.ForEachStmt.class).size();
        complexity += method.findAll(com.github.javaparser.ast.expr.ConditionalExpr.class).size();
        return complexity;
    }

    /**
     * Simple result record for an incremental refresh operation.
     */
    public record RefreshResult(boolean isNoop, int changedFiles, int deleted, int rebuilt) {
        public static RefreshResult noop() {
            return new RefreshResult(true, 0, 0, 0);
        }
    }
}
