package com.huawei.hisi.service;

import com.huawei.hisi.model.KnowledgeGraphTask;
import java.util.List;

/**
 * 知识图谱生成任务服务接口
 */
public interface KnowledgeGraphTaskService {

    /**
     * 启动知识图谱生成任务
     * @param projectPath 项目完整路径
     * @return 创建的任务对象
     */
    KnowledgeGraphTask startTask(String projectPath);

    /**
     * 启动知识图谱生成任务（支持自定义屏蔽目录）
     * @param projectPath 项目完整路径
     * @param excludePaths 屏蔽目录列表（可为 null，使用默认值）
     * @return 创建的任务对象
     */
    KnowledgeGraphTask startTask(String projectPath, List<String> excludePaths);

    /**
     * 获取单个项目的最新任务状态
     * @param projectPath 项目完整路径
     * @return 任务对象
     */
    KnowledgeGraphTask getLatestTask(String projectPath);

    /**
     * 批量获取多个项目的最新任务状态
     * @param projectPaths 项目完整路径列表
     * @return 任务列表
     */
    List<KnowledgeGraphTask> getTaskStatus(List<String> projectPaths);

    /**
     * 获取所有正在运行的任务
     * @return 任务列表
     */
    List<KnowledgeGraphTask> getRunningTasks();

    /**
     * 异步执行知识图谱生成任务
     * @param taskId 任务ID
     * @param projectPath 项目路径
     */
    void executeTaskAsync(Long taskId, String projectPath);

    /**
     * 异步执行知识图谱生成任务（支持自定义屏蔽目录）
     * @param taskId 任务ID
     * @param projectPath 项目路径
     * @param excludePaths 屏蔽目录列表
     */
    void executeTaskAsync(Long taskId, String projectPath, List<String> excludePaths);
}