package com.huawei.hisi.service;

import com.huawei.hisi.config.DataSourceConfig;
import com.huawei.hisi.model.AppConfig;
import com.huawei.hisi.repository.AppConfigRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class AppConfigServiceImpl implements AppConfigService {

    private static final Logger LOG = LoggerFactory.getLogger(AppConfigServiceImpl.class);
    private static final String PROJECT_DIR_KEY = "PROJECT_DIR";
    private static final String SELECTED_PROJECT_KEY = "SELECTED_PROJECT";

    @Autowired
    private AppConfigRepository configRepository;

    private final AtomicReference<String> projectDirCache = new AtomicReference<>("");

    @PostConstruct
    public void init() {
        loadProjectDir();
    }

    private void loadProjectDir() {
        try {
            configRepository.findByKey(PROJECT_DIR_KEY).ifPresentOrElse(
                config -> {
                    projectDirCache.set(config.getValue());
                    LOG.info("Loaded PROJECT_DIR from database: {}", config.getValue());
                },
                () -> {
                    // Try to insert, if table exists
                    configRepository.upsert(PROJECT_DIR_KEY, "");
                    LOG.info("Initialized PROJECT_DIR with empty value");
                }
            );
        } catch (Exception e) {
            // Fallback to config file value if database access fails
            String defaultDir = DataSourceConfig.PROJECT_DIR;
            projectDirCache.set(defaultDir != null ? defaultDir : "");
            LOG.warn("Database access failed, using PROJECT_DIR from config file: {}", defaultDir);
        }
    }

    @Override
    public AppConfig getConfig(String key) {
        return configRepository.findByKey(key).orElse(null);
    }

    @Override
    public String getProjectDir() {
        return projectDirCache.get();
    }

    @Override
    public void updateProjectDir(String newPath, String updatedBy) {
        if (!isValidPath(newPath)) {
            throw new IllegalArgumentException("Invalid path: " + newPath);
        }

        configRepository.update(PROJECT_DIR_KEY, newPath);
        projectDirCache.set(newPath);

        LOG.info("PROJECT_DIR updated to: {} by {}", newPath, updatedBy);
    }

    @Override
    public String getSelectedProject() {
        AppConfig config = configRepository.findByKey(SELECTED_PROJECT_KEY).orElse(null);
        return config != null ? config.getValue() : "";
    }

    @Override
    public void updateSelectedProject(String projectName, String updatedBy) {
        // 检查是否已存在配置项
        configRepository.findByKey(SELECTED_PROJECT_KEY).ifPresentOrElse(
            config -> {
                configRepository.update(SELECTED_PROJECT_KEY, projectName);
                LOG.info("SELECTED_PROJECT updated to: {} by {}", projectName, updatedBy);
            },
            () -> {
                configRepository.upsert(SELECTED_PROJECT_KEY, projectName);
                LOG.info("SELECTED_PROJECT initialized to: {} by {}", projectName, updatedBy);
            }
        );
    }

    @Override
    public boolean isValidPath(String path) {
        if (path == null || path.trim().isEmpty()) {
            return true; // Allow empty path
        }

        File dir = new File(path);
        // Path is valid if it exists as a directory, or parent exists (for new directories)
        return dir.exists() || dir.getParentFile() != null && dir.getParentFile().exists();
    }
}
