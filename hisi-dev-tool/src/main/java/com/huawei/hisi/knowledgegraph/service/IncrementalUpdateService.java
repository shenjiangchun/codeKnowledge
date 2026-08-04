package com.huawei.hisi.knowledgegraph.service;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.huawei.hisi.knowledgegraph.model.GenerationTask;
import com.huawei.hisi.knowledgegraph.model.GitStatus;
import com.huawei.hisi.knowledgegraph.model.IncrementalUpdateResult;
import com.huawei.hisi.knowledgegraph.repository.GenerationTaskRepository;
import com.huawei.hisi.neo4j.model.MethodNode;
import com.huawei.hisi.neo4j.repository.Neo4jMethodNodeRepository;
import com.huawei.hisi.neo4j.service.EmbeddingService;
import com.huawei.hisi.service.CodeAnalysisCoreService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 增量更新服务 (V1 - 已废弃)
 * 基于Git变更实现知识图谱的增量更新
 *
 * @deprecated Use IncrementalRefreshService instead. This V1 implementation stores
 * checkpoint in SQLite errorMessage field which is unreliable. Will be removed in v5.1.
 * @see IncrementalRefreshService
 */
@Deprecated(since = "5.0", forRemoval = true)
@Service
@Slf4j
@RequiredArgsConstructor
public class IncrementalUpdateService {

    private final GitStatusService gitStatusService;
    private final EmbeddingService embeddingService;
    private final LLMDescriptionService llmDescriptionService;
    private final GenerationTaskRepository generationTaskRepository;
    private final Neo4jMethodNodeRepository methodNodeRepository;
    private final CodeAnalysisCoreService codeAnalysisCoreService;

    /**
     * 执行增量更新
     *
     * @param projectPath 项目路径
     * @return 增量更新结果
     */
    public IncrementalUpdateResult incrementalUpdate(String projectPath) {
        long startTime = System.currentTimeMillis();

        // 1. 验证项目路径
        if (!isValidProjectPath(projectPath)) {
            throw new IllegalArgumentException("无效的项目路径: " + projectPath);
        }

        // 2. 获取当前 Git 状态
        GitStatus gitStatus = gitStatusService.getGitStatus(projectPath);
        if (!gitStatus.isClean()) {
            log.warn("项目有未提交的更改: {}", projectPath);
        }

        String newCommitHash = gitStatus.getCommitHash();
        String branch = gitStatus.getBranch();

        // 3. 获取上次生成日志 (KG_LOG tasks store commit hash in errorMessage field)
        Optional<GenerationTask> lastLogOpt = generationTaskRepository.findLatestByProjectPathAndType(projectPath, "KG_LOG");
        if (lastLogOpt.isEmpty()) {
            throw new IllegalStateException("未找到上次生成日志，请先执行全量生成 (FULL generation)");
        }

        GenerationTask lastLog = lastLogOpt.get();
        String oldCommitHash = lastLog.getErrorMessage();

        // 4. 获取变更文件
        List<String> changedFiles = gitStatusService.getChangedFiles(projectPath, oldCommitHash, newCommitHash);
        log.info("检测到 {} 个变更文件: {}", changedFiles.size(), changedFiles);

        // 5. 如果没有变更，返回空结果
        if (changedFiles.isEmpty()) {
            return buildEmptyResult(projectPath, oldCommitHash, newCommitHash, branch);
        }

        // 6. 获取现有方法节点
        List<MethodNode> existingMethods = methodNodeRepository.findByProjectPath(projectPath);
        Map<String, MethodNode> existingMethodMap = existingMethods.stream()
                .collect(Collectors.toMap(
                        IncrementalUpdateService::generateMethodUniqueId,
                        m -> m,
                        (a, b) -> a
                ));

        // 7. 解析变更文件，提取方法变更
        MethodChangeResult changeResult = parseMethodChanges(projectPath, changedFiles, existingMethodMap);

        // 8. 执行新增操作
        List<MethodNode> newMethods = new ArrayList<>();
        for (MethodNode newMethod : changeResult.getNewMethods()) {
            // 生成描述（使用一致性校验版本，含方法体）
            String description = llmDescriptionService.generateDescriptionWithBody(newMethod);
            newMethod.setDescription(description);

            // 保存
            methodNodeRepository.save(newMethod);
            newMethods.add(newMethod);
        }

        // 9. 执行更新操作
        List<MethodNode> updatedMethods = new ArrayList<>();
        for (MethodNode modifiedMethod : changeResult.getModifiedMethods()) {
            // 生成新的描述（使用一致性校验版本，含方法体）
            String description = llmDescriptionService.generateDescriptionWithBody(modifiedMethod);
            modifiedMethod.setDescription(description);

            // 保存
            methodNodeRepository.save(modifiedMethod);
            updatedMethods.add(modifiedMethod);
        }

        // 10. 执行删除操作
        List<String> deletedMethodIds = new ArrayList<>();
        for (String deletedMethodId : changeResult.getDeletedMethodIds()) {
            methodNodeRepository.deleteByNodeId(deletedMethodId);
            deletedMethodIds.add(deletedMethodId);
        }

        // 11. 生成向量（异步，可选）
        generateEmbeddingsAsync(newMethods, updatedMethods);

        // 12. 计算总方法数
        int totalMethods = existingMethods.size() + newMethods.size() - deletedMethodIds.size();

        // 13. 保存生成日志 (as KG_LOG task, commit hash stored in errorMessage)
        long costTime = System.currentTimeMillis() - startTime;
        long nowEpoch = java.time.Instant.now().getEpochSecond();
        long startEpoch = nowEpoch - (costTime / 1000);
        GenerationTask logTask = GenerationTask.builder()
                .taskType("KG_LOG")
                .projectPath(projectPath)
                .status("COMPLETED")
                .totalCount(totalMethods)
                .progress(totalMethods)
                .successCount(newMethods.size())
                .failCount(deletedMethodIds.size())
                .errorMessage(newCommitHash)
                .startedAt(startEpoch)
                .finishedAt(nowEpoch)
                .build();
        generationTaskRepository.insert(logTask);

        // 14. 构建返回结果
        return IncrementalUpdateResult.builder()
                .success(true)
                .projectPath(projectPath)
                .oldCommitHash(oldCommitHash)
                .newCommitHash(newCommitHash)
                .branch(branch)
                .totalMethods(totalMethods)
                .newMethods(newMethods.size())
                .updatedMethods(updatedMethods.size())
                .deletedMethods(deletedMethodIds.size())
                .changedFiles(changedFiles.size())
                .costTimeMs(costTime)
                .newMethodNodes(newMethods)
                .updatedMethodNodes(updatedMethods)
                .deletedMethodIds(deletedMethodIds)
                .build();
    }

    /**
     * 解析方法变更
     */
    private MethodChangeResult parseMethodChanges(String projectPath, List<String> changedFiles,
                                                   Map<String, MethodNode> existingMethodMap) {
        List<MethodNode> newMethods = new ArrayList<>();
        List<MethodNode> modifiedMethods = new ArrayList<>();
        Set<String> parsedMethodIds = new HashSet<>();

        // 解析所有变更文件
        for (String changedFile : changedFiles) {
            if (!changedFile.endsWith(".java")) {
                continue;
            }

            try {
                Path filePath = Paths.get(projectPath, changedFile);
                if (!Files.exists(filePath)) {
                    // 文件被删除，跳过
                    log.info("文件已被删除，跳过: {}", changedFile);
                    continue;
                }

                List<MethodNode> methodsInFile = parseJavaFile(filePath.toString(), projectPath);

                for (MethodNode method : methodsInFile) {
                    String methodId = generateMethodUniqueId(method);
                    parsedMethodIds.add(methodId);

                    MethodNode existingMethod = existingMethodMap.get(methodId);
                    if (existingMethod == null) {
                        // 新增方法
                        newMethods.add(method);
                    } else {
                        // 检查方法内容是否变化（比较方法体）
                        String existingBody = existingMethod.getMethodBody();
                        String newBody = method.getMethodBody();
                        boolean contentChanged = (existingBody == null && newBody != null) ||
                            (existingBody != null && !existingBody.equals(newBody));

                        if (contentChanged) {
                            // 方法已修改
                            method.setNodeId(existingMethod.getNodeId());
                            modifiedMethods.add(method);
                        }
                    }
                }
            } catch (Exception e) {
                log.error("解析变更文件失败: {}", changedFile, e);
            }
        }

        // 找出被删除的方法
        List<String> deletedMethodIds = new ArrayList<>();
        for (String methodId : existingMethodMap.keySet()) {
            if (!parsedMethodIds.contains(methodId)) {
                // 方法对应的文件被修改或删除，且方法不在新文件中
                MethodNode existingMethod = existingMethodMap.get(methodId);

                // 检查方法所在的文件是否在变更列表中
                String methodFile = existingMethod.getFilePath();
                if (methodFile != null) {
                    String relativePath = getRelativePath(projectPath, methodFile);
                    if (changedFiles.contains(relativePath)) {
                        deletedMethodIds.add(existingMethod.getNodeId());
                    }
                }
            }
        }

        return new MethodChangeResult(newMethods, modifiedMethods, deletedMethodIds);
    }

    /**
     * 解析 Java 文件，提取方法节点
     */
    private List<MethodNode> parseJavaFile(String filePath, String projectPath) {
        List<MethodNode> methodNodes = new ArrayList<>();

        try {
            File file = new File(filePath);
            if (!file.exists()) {
                return methodNodes;
            }

            // 使用 JavaParser 解析文件
            JavaParser javaParser = new JavaParser();
            ParseResult<CompilationUnit> parseResult = javaParser.parse(file);

            if (!parseResult.isSuccessful() || !parseResult.getResult().isPresent()) {
                return methodNodes;
            }

            CompilationUnit cu = parseResult.getResult().get();

            // 获取包名
            String packageName = cu.getPackageDeclaration()
                    .map(pd -> pd.getNameAsString())
                    .orElse("");

            // 遍历所有类
            cu.findAll(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class).forEach(clazz -> {
                String className = packageName.isEmpty() ?
                        clazz.getNameAsString() :
                        packageName + "." + clazz.getNameAsString();

                // 遍历所有方法
                clazz.findAll(MethodDeclaration.class).forEach(method -> {
                    String nodeId = generateNodeId(projectPath, className, method);
                    String signature = method.getSignature().asString();

                    MethodNode node = MethodNode.builder()
                            .nodeId(nodeId)
                            .className(className)
                            .methodName(method.getNameAsString())
                            .signature(signature)
                            .filePath(filePath)
                            .startLine(method.getBegin().map(p -> p.line).orElse(0))
                            .endLine(method.getEnd().map(p -> p.line).orElse(0))
                            .complexity(calculateComplexity(method))
                            .methodBody(compressMethodBody(method))
                            .projectPath(projectPath)
                            .build();

                    methodNodes.add(node);
                });
            });

        } catch (Exception e) {
            log.error("解析 Java 文件失败: {}", filePath, e);
        }

        return methodNodes;
    }

    /**
     * 生成节点 ID
     * 格式: projectPath:className.methodName.signatureHash
     */
    private String generateNodeId(String projectPath, String className, MethodDeclaration method) {
        String methodId = className + "." + method.getNameAsString() + "." +
                signatureHash(method.getSignature().toString());
        return projectPath + ":" + methodId;
    }

    /**
     * 计算圈复杂度
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
     * 压缩方法体
     */
    private String compressMethodBody(MethodDeclaration method) {
        if (!method.getBody().isPresent()) {
            return "";
        }
        String body = method.getBody().get().toString();
        // 简单压缩：移除多余空白
        return body.replaceAll("\\s+", " ").trim();
    }

    /**
     * 验证项目路径
     */
    private boolean isValidProjectPath(String projectPath) {
        if (projectPath == null || projectPath.isEmpty()) {
            return false;
        }
        Path path = Paths.get(projectPath);
        return Files.isDirectory(path) && gitStatusService.isValidGitDirectory(projectPath);
    }

    /**
     * 构建空结果
     */
    private IncrementalUpdateResult buildEmptyResult(String projectPath, String oldCommitHash,
                                                      String newCommitHash, String branch) {
        return IncrementalUpdateResult.builder()
                .success(true)
                .projectPath(projectPath)
                .oldCommitHash(oldCommitHash)
                .newCommitHash(newCommitHash)
                .branch(branch)
                .totalMethods(0)
                .newMethods(0)
                .updatedMethods(0)
                .deletedMethods(0)
                .changedFiles(0)
                .costTimeMs(0)
                .build();
    }

    /**
     * 异步生成向量
     */
    private void generateEmbeddingsAsync(List<MethodNode> newMethods, List<MethodNode> updatedMethods) {
        // 向量生成通常是异步的，这里简单实现
        // 实际项目中可以使用 @Async 或消息队列
        try {
            for (MethodNode method : newMethods) {
                String text = method.getClassName() + "." + method.getMethodName() + "." + method.getSignature();
                embeddingService.generateEmbedding(text);
            }
            for (MethodNode method : updatedMethods) {
                String text = method.getClassName() + "." + method.getMethodName() + "." + method.getSignature();
                embeddingService.generateEmbedding(text);
            }
        } catch (Exception e) {
            log.warn("向量生成失败，但不影响主流程", e);
        }
    }

    /**
     * 获取相对路径
     */
    private String getRelativePath(String basePath, String absolutePath) {
        if (absolutePath == null) {
            return "";
        }
        Path base = Paths.get(basePath);
        Path absolute = Paths.get(absolutePath);
        try {
            return base.relativize(absolute).toString().replace("\\", "/");
        } catch (Exception e) {
            return absolutePath;
        }
    }

    /**
     * 生成方法唯一标识
     * 使用 className + methodName + signature 作为唯一标识
     */
    public static String generateMethodUniqueId(MethodNode method) {
        return method.getClassName() + "." + method.getMethodName() + "." +
                Integer.toHexString(method.getSignature().hashCode());
    }

    /**
     * 生成内容哈希
     * 基于方法体内容生成哈希，用于检测方法是否被修改
     */
    public static String generateContentHash(MethodNode method) {
        try {
            String content = method.getClassName() + "." +
                    method.getMethodName() + "." +
                    method.getSignature() + "." +
                    (method.getMethodBody() != null ? method.getMethodBody() : "");

            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(content.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return UUID.randomUUID().toString();
        }
    }

    private static String signatureHash(String signature) {
        return Integer.toHexString(signature.hashCode());
    }

    /**
     * 方法变更结果内部类
     */
    private static class MethodChangeResult {
        private final List<MethodNode> newMethods;
        private final List<MethodNode> modifiedMethods;
        private final List<String> deletedMethodIds;

        public MethodChangeResult(List<MethodNode> newMethods, List<MethodNode> modifiedMethods,
                                  List<String> deletedMethodIds) {
            this.newMethods = newMethods;
            this.modifiedMethods = modifiedMethods;
            this.deletedMethodIds = deletedMethodIds;
        }

        public List<MethodNode> getNewMethods() {
            return newMethods;
        }

        public List<MethodNode> getModifiedMethods() {
            return modifiedMethods;
        }

        public List<String> getDeletedMethodIds() {
            return deletedMethodIds;
        }
    }
}
