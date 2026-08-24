package com.huawei.hisi.knowledgegraph.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * 前端项目发现器。
 *
 * <p>在建图入口收到后端 projectPath 后，自动发现关联的前端项目目录
 * （用于前端实体化建图）。发现策略：</p>
 * <ol>
 *   <li>显式指定：若调用方传了前端路径，直接校验 package.json 存在性。</li>
 *   <li>同级探测：在后端 projectPath 的父目录下，查找名为
 *       {@code <后端名>-frontend} 或 {@code <后端名>-front} 的目录，且目录内含 package.json。</li>
 * </ol>
 *
 * <p>前端目录作为独立 projectPath 存储（与后端 projectPath 不同），天然隔离于
 * 后端 cleanProjectData 的按路径精确删除。</p>
 */
@Slf4j
@Component
public class FrontendProjectDiscoverer {

    /**
     * 探测与后端项目关联的前端目录。
     *
     * @param backendProjectPath 后端项目根目录（不可为 null）
     * @param explicitFrontendPath 显式指定的前端路径（可为 null，表示自动探测）
     * @return 发现的前端项目目录（绝对路径）；未发现时返回空列表
     */
    public List<String> discover(String backendProjectPath, String explicitFrontendPath) {
        List<String> result = new ArrayList<>();
        if (backendProjectPath == null || backendProjectPath.isBlank()) {
            return result;
        }
        File backend = new File(backendProjectPath);
        if (!backend.isDirectory()) {
            log.debug("[FrontendProjectDiscoverer] 后端项目目录不存在: {}", backendProjectPath);
            return result;
        }

        // 1. 显式指定
        if (explicitFrontendPath != null && !explicitFrontendPath.isBlank()) {
            File explicit = new File(explicitFrontendPath);
            if (isFrontendProject(explicit)) {
                result.add(explicit.getAbsolutePath());
            }
            return result;
        }

        // 2. 同级探测
        File parent = backend.getParentFile();
        if (parent == null) {
            return result;
        }
        String backendName = backend.getName();
        String[] candidates = { backendName + "-frontend", backendName + "-front" };
        for (String candidate : candidates) {
            File frontendDir = new File(parent, candidate);
            if (isFrontendProject(frontendDir)) {
                result.add(frontendDir.getAbsolutePath());
                break; // 只取第一个命中的候选
            }
        }
        return result;
    }

    /**
     * 判断目录是否是前端项目（存在 package.json）。
     */
    private boolean isFrontendProject(File dir) {
        if (dir == null || !dir.isDirectory()) {
            return false;
        }
        File packageJson = new File(dir, "package.json");
        return Files.isRegularFile(packageJson.toPath());
    }
}
