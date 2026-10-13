package com.huawei.hisi.knowledgegraph.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;

/**
 * 前端项目校验器。
 *
 * <p>由「自动发现前端目录」收敛为「显式路径校验」：前端目录不再通过
 * {@code <后端名>-frontend} 命名约定自动探测，而是由用户在绑定构建时显式指定，
 * 本组件仅校验该目录存在且含 {@code package.json}。</p>
 */
@Slf4j
@Component
public class FrontendProjectDiscoverer {

    /**
     * 校验显式指定的前端项目目录：必须存在且含 {@code package.json}。
     *
     * @param frontendPath 前端项目目录（绝对路径，不可为空）
     * @throws IllegalArgumentException 目录为空 / 不存在 / 不含 package.json
     */
    public void validateFrontendProject(String frontendPath) {
        if (frontendPath == null || frontendPath.isBlank()) {
            throw new IllegalArgumentException("前端目录不能为空");
        }
        File dir = new File(frontendPath);
        if (!dir.isDirectory()) {
            throw new IllegalArgumentException("前端目录不存在: " + frontendPath);
        }
        if (!isFrontendProject(dir)) {
            throw new IllegalArgumentException("前端目录不含 package.json: " + frontendPath);
        }
    }

    /**
     * 判断目录是否是前端项目（存在 package.json）。
     */
    private boolean isFrontendProject(File dir) {
        File packageJson = new File(dir, "package.json");
        return Files.isRegularFile(packageJson.toPath());
    }
}
