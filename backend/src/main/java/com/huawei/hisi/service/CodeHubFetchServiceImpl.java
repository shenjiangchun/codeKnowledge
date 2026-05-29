package com.huawei.hisi.service;

import com.huawei.hisi.model.GitFetchDto;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.PullResult;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.huawei.hisi.config.DataSourceConfig.PROJECT_DIR;

@Service
public class CodeHubFetchServiceImpl implements CodeHubFetchService {

    /**
     * API基础URL
     */
    @Value("${app.codeHubUser}")
    private String user;

    /**
     * API密钥
     */
    @Value("${app.codeHubPassword}")
    private String password;

    /**
     * 批量克隆 Git 仓库 (增量模式)
     * 如果仓库已存在则拉取最新代码并切换分支，不存在则克隆
     */
    @Override
    public Map<String, Object> fetchRepositories(List<GitFetchDto> dtos) throws IOException {
        List<String> successList = new ArrayList<>();
        List<String> failedList = new ArrayList<>();
        List<String> projectRoots = new ArrayList<>();
        List<String> updatedList = new ArrayList<>();  // 已存在并更新的仓库
        List<String> clonedList = new ArrayList<>();   // 新克隆的仓库

        // 确保基础目录存在
        File baseDir = new File(PROJECT_DIR);
        if (!baseDir.exists()) {
            baseDir.mkdirs();
        }

        UsernamePasswordCredentialsProvider credentialsProvider =
                new UsernamePasswordCredentialsProvider(user, password);

        for (GitFetchDto dto : dtos) {
            try {
                String repoName = extractRepoName(dto.getRepoUrl());
                File repoDir = new File(baseDir, repoName);

                if (repoDir.exists() && new File(repoDir, ".git").exists()) {
                    // 仓库已存在，执行增量更新
                    System.out.println("仓库已存在，拉取最新代码: " + repoName);
                    try (Git git = Git.open(repoDir)) {
                        // 拉取最新代码
                        PullResult pullResult = git.pull()
                                .setCredentialsProvider(credentialsProvider)
                                .call();

                        // 切换到指定分支
                        git.checkout()
                                .setName(dto.getBranch())
                                .call();

                        if (pullResult.isSuccessful()) {
                            System.out.println("更新成功: " + repoName + " -> " + dto.getBranch());
                            updatedList.add(repoName);
                        } else {
                            System.out.println("更新完成(无变化): " + repoName);
                            updatedList.add(repoName);
                        }
                        successList.add(repoName);
                    }
                } else {
                    // 仓库不存在，执行克隆
                    System.out.println("开始克隆仓库: " + repoName);
                    CloneCommand clone = Git.cloneRepository()
                            .setURI(dto.getRepoUrl())
                            .setDirectory(repoDir)
                            .setBranch(dto.getBranch())
                            .setCredentialsProvider(credentialsProvider);

                    try (Git git = clone.call()) {
                        System.out.println("克隆成功: " + repoDir.getAbsolutePath());
                        clonedList.add(repoName);
                        successList.add(repoName);
                    }
                }

                // 查找 src/main/java 的项目根目录
                List<Path> sourceRoots = findSourceRoots(repoDir.toPath());
                projectRoots.addAll(sourceRoots.stream()
                        .map(Path::toString)
                        .collect(Collectors.toList()));

            } catch (Exception e) {
                System.err.println("操作失败 [" + dto.getRepoUrl() + "]: " + e.getMessage());
                failedList.add(dto.getRepoUrl() + " -> " + e.getMessage());
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("successCount", successList.size());
        result.put("failCount", failedList.size());
        result.put("successList", successList);
        result.put("failedList", failedList);
        result.put("updatedList", updatedList);
        result.put("clonedList", clonedList);
        result.put("projectRoots", projectRoots);
        result.put("savedTo", PROJECT_DIR);

        return result;
    }

    /**
     * 从 Git URL 提取仓库名
     */
    private String extractRepoName(String repoUrl) {
        try {
            URI uri = URI.create(repoUrl);
            String path = uri.getPath(); // /HiAPM-CODE/eureka-server.git
            String filename = Paths.get(path).getFileName().toString();
            if (filename.toLowerCase().endsWith(".git")) {
                return filename.substring(0, filename.length() - 4);
            }
            return filename;
        } catch (Exception e) {
            System.err.println("无法解析仓库名，使用默认名称");
            return "unknown-repo";
        }
    }

    /**
     * 查找所有包含 src/main/java 的项目根目录（祖父目录）
     */
    public List<Path> findSourceRoots(Path projectRoot) throws IOException {
        List<Path> sourceRoots = new ArrayList<>();
        Files.walkFileTree(projectRoot, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.endsWith("src/main/java")) {
                    Path projectRootDir = dir.getParent().getParent().getParent();
                    if (projectRootDir != null && Files.exists(projectRootDir)) {
                        sourceRoots.add(projectRootDir);
                        System.out.println("发现有效项目根目录: " + projectRootDir);
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return sourceRoots;
    }
}