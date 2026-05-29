package com.huawei.hisi.service;

import com.huawei.hisi.model.AppConfig;

public interface AppConfigService {

    /**
     * 获取配置项
     */
    AppConfig getConfig(String key);

    /**
     * 获取 PROJECT_DIR 配置
     */
    String getProjectDir();

    /**
     * 更新 PROJECT_DIR 配置
     */
    void updateProjectDir(String newPath, String updatedBy);

    /**
     * 获取 SELECTED_PROJECT 配置
     */
    String getSelectedProject();

    /**
     * 更新 SELECTED_PROJECT 配置
     */
    void updateSelectedProject(String projectName, String updatedBy);

    /**
     * 验证路径有效性
     */
    boolean isValidPath(String path);
}