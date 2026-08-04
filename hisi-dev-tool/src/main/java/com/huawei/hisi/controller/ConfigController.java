package com.huawei.hisi.controller;

import com.huawei.hisi.model.ApiResponse;
import com.huawei.hisi.model.AppConfig;
import com.huawei.hisi.service.AppConfigService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 配置管理控制器
 * 提供运行时配置的查询和更新接口
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@Slf4j
@RestController
@RequestMapping("/api/config")
@RequiredArgsConstructor
public class ConfigController {

    private final AppConfigService configService;

    /**
     * 根据键获取配置项
     * GET /api/config?key={key}
     *
     * @param key 配置键
     * @return 配置项
     */
    @GetMapping
    public ApiResponse<AppConfig> getConfig(@RequestParam String key) {
        AppConfig config = configService.getConfig(key);
        if (config == null) {
            return ApiResponse.error(404, "Configuration not found: " + key);
        }
        return ApiResponse.success(config);
    }

    /**
     * 更新配置项
     * PUT /api/config
     *
     * @param request 更新请求
     * @return 更新后的配置项
     */
    @PutMapping
    public ApiResponse<AppConfig> updateConfig(@RequestBody UpdateConfigRequest request) {
        if ("PROJECT_DIR".equals(request.getKey())) {
            if (!configService.isValidPath(request.getValue())) {
                return ApiResponse.error(400, "Invalid path: " + request.getValue());
            }
            configService.updateProjectDir(
                    request.getValue(),
                    request.getUpdatedBy() != null ? request.getUpdatedBy() : "system"
            );
            AppConfig updated = configService.getConfig(request.getKey());
            return ApiResponse.success(updated);
        } else if ("SELECTED_PROJECT".equals(request.getKey())) {
            configService.updateSelectedProject(
                    request.getValue(),
                    request.getUpdatedBy() != null ? request.getUpdatedBy() : "system"
            );
            AppConfig updated = configService.getConfig(request.getKey());
            return ApiResponse.success(updated);
        }
        return ApiResponse.error(400, "Unsupported configuration key: " + request.getKey());
    }

    /**
     * 获取 PROJECT_DIR 配置
     * GET /api/config/project-dir
     *
     * @return PROJECT_DIR 配置项
     */
    @GetMapping("/project-dir")
    public ApiResponse<AppConfig> getProjectDir() {
        AppConfig config = configService.getConfig("PROJECT_DIR");
        return ApiResponse.success(config);
    }

    /**
     * 获取 SELECTED_PROJECT 配置
     * GET /api/config/selected-project
     *
     * @return SELECTED_PROJECT 配置项
     */
    @GetMapping("/selected-project")
    public ApiResponse<AppConfig> getSelectedProject() {
        AppConfig config = configService.getConfig("SELECTED_PROJECT");
        return ApiResponse.success(config);
    }

    /**
     * 配置更新请求
     */
    @Data
    public static class UpdateConfigRequest {
        private String key;
        private String value;
        private String updatedBy;
    }
}