package com.huawei.hisi.service;

import com.huawei.hisi.model.GitRepositoryInfo;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 项目管理服务接口
 * M1.3 - 定义项目管理方法
 */
public interface ProjectService {

    /**
     * 获取已克隆的项目列表
     * @return 项目列表
     */
    List<String> listProjects();

    /**
     * 克隆 Git 仓库到本地
     * @param repository Git 仓库地址
     * @param branch 分支名称
     * @return 操作结果
     */
    Map<String, Object> cloneProject(String repository, String branch);

    /**
     * 获取项目状态
     * @param project 项目名称
     * @return 项目状态信息
     */
    Map<String, Object> getStatus(String project);

    /**
     * 扫描项目目录中的 Git 仓库
     * @return 找到的 Git 仓库列表
     */
    List<GitRepositoryInfo> scanGitRepositories();
}