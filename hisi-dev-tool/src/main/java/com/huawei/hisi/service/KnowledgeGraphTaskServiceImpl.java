package com.huawei.hisi.service;

import com.huawei.hisi.knowledgegraph.model.GenerationTask;
import com.huawei.hisi.knowledgegraph.model.GitStatus;
import com.huawei.hisi.knowledgegraph.repository.GenerationTaskRepository;
import com.huawei.hisi.knowledgegraph.service.GitStatusService;
import com.huawei.hisi.knowledgegraph.service.KgGenerationQueue;
import com.huawei.hisi.knowledgegraph.service.KnowledgeGraphBuilder;
import com.huawei.hisi.model.KnowledgeGraphTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 知识图谱生成任务服务实现
 * Uses unified GenerationTaskRepository with task_type = "KG".
 */
@Service
public class KnowledgeGraphTaskServiceImpl implements KnowledgeGraphTaskService {

    private static final Logger LOG = LoggerFactory.getLogger(KnowledgeGraphTaskServiceImpl.class);
    private static final String TASK_TYPE = "KG";

    @Autowired
    private GenerationTaskRepository taskRepository;

    @Autowired
    private KnowledgeGraphBuilder knowledgeGraphBuilder;

    @Autowired
    private GitStatusService gitStatusService;

    @Autowired
    private KgGenerationQueue kgGenerationQueue;

    @Lazy
    @Autowired
    private KnowledgeGraphTaskService self;

    @Override
    public KnowledgeGraphTask startTask(String projectPath) {
        return startTask(projectPath, null);
    }

    @Override
    public KnowledgeGraphTask startTask(String rawProjectPath, List<String> excludePaths) {
        // Git 校验仍在此处做，队列只管执行
        final String projectPath = com.huawei.hisi.knowledgegraph.util.KnowledgeGraphCommonUtils.normalizePath(rawProjectPath);
        File projectDir = new File(projectPath);
        if (!projectDir.exists() || !projectDir.isDirectory()) {
            throw new IllegalArgumentException("项目路径不存在: " + projectPath);
        }

        if (gitStatusService.isValidGitDirectory(projectPath)) {
            GitStatus gitStatus = gitStatusService.getGitStatus(projectPath);
            if (gitStatus.isHasUncommittedChanges()) {
                throw new IllegalStateException("工作目录有未提交的更改，请先提交所有更改后再生成知识图谱");
            }
            if (gitStatus.isHasUnpushedCommits()) {
                throw new IllegalStateException(String.format(
                    "本地有 %d 个未推送的提交，请先推送到远程仓库后再生成知识图谱",
                    gitStatus.getUnpushedCommitCount()
                ));
            }
        } else {
            LOG.warn("项目路径不是有效的 Git 仓库: {}", projectPath);
        }

        // 入队（队列内部处理 PENDING 任务创建和重复检查）
        return kgGenerationQueue.enqueue(projectPath, excludePaths);
    }

    @Override
    @Async("analysisTaskExecutor")
    public void executeTaskAsync(Long taskId, String projectPath) {
        executeTaskAsync(taskId, projectPath, null);
    }

    @Override
    @Async("analysisTaskExecutor")
    public void executeTaskAsync(Long taskId, String projectPath, List<String> excludePaths) {
        LOG.info("Starting async knowledge graph generation for task: {}, excludePaths={}", taskId, excludePaths);
        long startTime = System.currentTimeMillis();

        taskRepository.updateStarted(taskId);

        try {
            Map<String, Object> result = knowledgeGraphBuilder.buildKnowledgeGraph(projectPath, excludePaths);
            int methodNodeCount = result.get("methodNodeCount") != null ? (int) result.get("methodNodeCount") : 0;
            int callRelationCount = result.get("callRelationCount") != null ? (int) result.get("callRelationCount") : 0;

            taskRepository.updateCompleted(taskId, methodNodeCount, methodNodeCount, methodNodeCount, 0);

            LOG.info("Knowledge graph task completed: id={}, methodNodes={}, callRelations={}",
                taskId, methodNodeCount, callRelationCount);

        } catch (Exception e) {
            LOG.error("Knowledge graph task failed: id={}, error={}", taskId, e.getMessage(), e);
            taskRepository.updateFailed(taskId, e.getMessage());
        }
    }

    @Override
    public KnowledgeGraphTask getLatestTask(String projectPath) {
        return taskRepository.findLatestByProjectPathAndType(projectPath, TASK_TYPE)
            .map(task -> {
                // 检查任务是否已卡住（RUNNING状态超过1天）
                if ("RUNNING".equals(task.getStatus()) && task.getStartedAt() != null) {
                    long now = Instant.now().getEpochSecond();
                    long hours = (now - task.getStartedAt()) / 3600;
                    if (hours >= 24) {
                        // 认为任务已失败
                        taskRepository.updateFailed(task.getId(), "任务超时（超过1天）");
                        task.setStatus("FAILED");
                        task.setErrorMessage("任务超时（超过1天）");
                        LOG.warn("任务超时自动标记为FAILED: taskId={}, projectPath={}", task.getId(), projectPath);
                    }
                }
                return toKnowledgeGraphTask(task);
            })
            .orElse(null);
    }

    @Override
    public List<KnowledgeGraphTask> getTaskStatus(List<String> projectPaths) {
        if (projectPaths == null || projectPaths.isEmpty()) {
            return taskRepository.findRunningOrPending(TASK_TYPE).stream()
                .map(this::toKnowledgeGraphTask)
                .collect(Collectors.toList());
        }
        return taskRepository.findLatestByProjectPaths(projectPaths, TASK_TYPE).stream()
            .map(this::toKnowledgeGraphTask)
            .collect(Collectors.toList());
    }

    @Override
    public List<KnowledgeGraphTask> getRunningTasks() {
        return taskRepository.findRunningOrPending(TASK_TYPE).stream()
            .map(this::toKnowledgeGraphTask)
            .collect(Collectors.toList());
    }

    /**
     * Convert GenerationTask to KnowledgeGraphTask for API compatibility.
     */
    private KnowledgeGraphTask toKnowledgeGraphTask(GenerationTask gt) {
        KnowledgeGraphTask task = new KnowledgeGraphTask();
        task.setId(gt.getId());
        task.setProjectPath(gt.getProjectPath());
        task.setProjectName(gt.getProjectPath() != null ? new File(gt.getProjectPath()).getName() : null);
        task.setStatus(gt.getStatus());
        task.setErrorMessage(gt.getErrorMessage());
        task.setMethodNodeCount(gt.getTotalCount());
        task.setRecordsProcessed(gt.getProgress());
        if (gt.getStartedAt() != null) {
            task.setStartTime(LocalDateTime.ofInstant(Instant.ofEpochSecond(gt.getStartedAt()), ZoneId.systemDefault()));
        }
        if (gt.getFinishedAt() != null) {
            task.setEndTime(LocalDateTime.ofInstant(Instant.ofEpochSecond(gt.getFinishedAt()), ZoneId.systemDefault()));
        }
        if (gt.getStartedAt() != null && gt.getFinishedAt() != null) {
            task.setCostTimeMs((gt.getFinishedAt() - gt.getStartedAt()) * 1000);
        }
        if (gt.getCreatedAt() != null) {
            task.setCreatedAt(LocalDateTime.ofInstant(Instant.ofEpochSecond(gt.getCreatedAt()), ZoneId.systemDefault()));
        }
        return task;
    }
}
