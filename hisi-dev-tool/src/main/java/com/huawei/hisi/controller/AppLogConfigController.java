package com.huawei.hisi.controller;

import com.huawei.hisi.model.ApiResponse;
import com.huawei.hisi.model.AppLogConfig;
import com.huawei.hisi.repository.AppLogConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 日志拉取配置 Controller
 * 提供定时任务配置的增删改查 API
 */
@Slf4j
@RestController
@RequestMapping("/api/log/config")
@RequiredArgsConstructor
public class AppLogConfigController {

    private final AppLogConfigRepository repository;

    /**
     * 获取所有配置
     */
    @GetMapping
    public ApiResponse<List<AppLogConfig>> list() {
        List<AppLogConfig> configs = repository.findAll();
        return ApiResponse.success(configs);
    }

    /**
     * 根据appId获取配置
     */
    @GetMapping("/{appId}")
    public ApiResponse<AppLogConfig> get(@PathVariable String appId) {
        AppLogConfig config = repository.findByAppId(appId);
        if (config == null) {
            return ApiResponse.error(404, "配置不存在");
        }
        return ApiResponse.success(config);
    }

    /**
     * 创建或更新配置
     */
    @PostMapping
    public ApiResponse<AppLogConfig> save(@RequestBody AppLogConfig config) {
        if (config.getAppId() == null || config.getAppId().isBlank()) {
            return ApiResponse.error(400, "appId 不能为空");
        }
        if (config.getProjectPath() == null || config.getProjectPath().isBlank()) {
            return ApiResponse.error(400, "projectPath 不能为空");
        }
        if (config.getDslQuery() == null || config.getDslQuery().isBlank()) {
            return ApiResponse.error(400, "dslQuery 不能为空");
        }
        if (config.getPullIntervalMinutes() == null || config.getPullIntervalMinutes() < 1) {
            config.setPullIntervalMinutes(10); // 默认10分钟
        }

        repository.save(config);
        log.info("[AppLogConfig] 配置保存成功: appId={}, projectPath={}", config.getAppId(), config.getProjectPath());

        AppLogConfig saved = repository.findByAppId(config.getAppId());
        return ApiResponse.success(saved);
    }

    /**
     * 删除配置
     */
    @DeleteMapping("/{appId}")
    public ApiResponse<Void> delete(@PathVariable String appId) {
        AppLogConfig existing = repository.findByAppId(appId);
        if (existing == null) {
            return ApiResponse.error(404, "配置不存在");
        }
        repository.deleteByAppId(appId);
        log.info("[AppLogConfig] 配置删除成功: appId={}", appId);
        return ApiResponse.success(null);
    }

    /**
     * 切换启用状态
     */
    @PostMapping("/{appId}/toggle")
    public ApiResponse<AppLogConfig> toggle(@PathVariable String appId) {
        AppLogConfig existing = repository.findByAppId(appId);
        if (existing == null) {
            return ApiResponse.error(404, "配置不存在");
        }
        boolean newEnabled = existing.getEnabled() == null || !existing.getEnabled();
        repository.toggleEnabled(appId, newEnabled);
        log.info("[AppLogConfig] 配置状态切换: appId={}, enabled={}", appId, newEnabled);

        AppLogConfig updated = repository.findByAppId(appId);
        return ApiResponse.success(updated);
    }
}