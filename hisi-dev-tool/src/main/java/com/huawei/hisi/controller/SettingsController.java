package com.huawei.hisi.controller;

import com.huawei.hisi.config.ProxyConfig;
import com.huawei.hisi.model.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.*;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Settings Controller - provides REST API for reading/writing application.yml
 * Used by the Settings page in the frontend (both browser and Electron mode).
 */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private static final Logger log = LoggerFactory.getLogger(SettingsController.class);

    private final ProxyConfig proxyConfig;

    public SettingsController(ProxyConfig proxyConfig) {
        this.proxyConfig = proxyConfig;
    }

    /**
     * Resolve the external config file path.
     * Priority: spring.config.additional-location > fallback to classpath resource.
     */
    private Path resolveConfigPath() {
        // Check common external locations
        String[] candidates = {
            System.getProperty("spring.config.additional-location", ""),
            System.getProperty("user.dir") + "/config/application.yml",
            System.getProperty("user.home") + "/.hisi-devtool/application.yml",
        };

        for (String candidate : candidates) {
            if (candidate.isEmpty()) continue;
            // Handle directory path (ending with /)
            Path p = candidate.endsWith("/") || candidate.endsWith("\\")
                ? Paths.get(candidate, "application.yml")
                : Paths.get(candidate);
            if (Files.exists(p)) {
                return p;
            }
        }

        // Fallback: look for config/ next to the running jar
        Path jarDir = Paths.get(System.getProperty("user.dir"));
        Path configFile = jarDir.resolve("config").resolve("application.yml");
        if (Files.exists(configFile)) {
            return configFile;
        }

        return null;
    }

    @GetMapping("/config")
    public ApiResponse<Map<String, Object>> getConfig() {
        Path configPath = resolveConfigPath();
        InputStream inputStream = null;
        String source = "classpath";

        try {
            if (configPath != null) {
                inputStream = new FileInputStream(configPath.toFile());
                source = configPath.toString();
            } else {
                // Fallback to classpath application.yml
                ClassPathResource resource = new ClassPathResource("application.yml");
                if (resource.exists()) {
                    inputStream = resource.getInputStream();
                } else {
                    return ApiResponse.error("No config file found");
                }
            }

            Yaml yaml = new Yaml();
            Map<String, Object> config = yaml.load(inputStream);
            if (config == null) {
                config = new LinkedHashMap<>();
            }
            log.info("[Settings] Loaded config from {}", source);
            return ApiResponse.success(config);
        } catch (IOException e) {
            log.error("[Settings] Failed to read config", e);
            return ApiResponse.error("Failed to read config: " + e.getMessage());
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException e) {
                    log.warn("[Settings] Failed to close input stream", e);
                }
            }
        }
    }

    @PostMapping("/config")
    public ApiResponse<Void> saveConfig(@RequestBody Map<String, Object> config) {
        Path configPath = resolveConfigPath();

        // If no external config exists, create one in user's home directory
        if (configPath == null) {
            String userHome = System.getProperty("user.home");
            Path configDir = Paths.get(userHome, ".hisi-devtool");
            configPath = configDir.resolve("application.yml");
            log.info("[Settings] Creating new config file at {}", configPath);
        }

        try {
            // Ensure parent directory exists
            Files.createDirectories(configPath.getParent());

            DumperOptions options = new DumperOptions();
            options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
            options.setPrettyFlow(true);
            options.setIndent(2);
            options.setIndicatorIndent(0);

            Yaml yaml = new Yaml(options);
            try (FileWriter writer = new FileWriter(configPath.toFile())) {
                yaml.dump(config, writer);
            }

            log.info("[Settings] Config saved to {}", configPath);
            return ApiResponse.success(null);
        } catch (IOException e) {
            log.error("[Settings] Failed to save config", e);
            return ApiResponse.error("Failed to save config: " + e.getMessage());
        }
    }

    // ==================== 代理配置（运行时热修改） ====================

    /**
     * 获取当前代理配置
     */
    @GetMapping("/proxy")
    public ApiResponse<ProxyConfig.ProxySettings> getProxySettings() {
        return ApiResponse.success(proxyConfig.getSettings());
    }

    /**
     * 更新代理配置（立即生效，无需重启）
     * 同时持久化到 application.yml
     */
    @PostMapping("/proxy")
    public ApiResponse<Void> updateProxySettings(@RequestBody Map<String, Object> body) {
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        String host = (String) body.getOrDefault("host", "");
        int port = body.get("port") instanceof Number n ? n.intValue() : 0;
        String type = (String) body.getOrDefault("type", "HTTP");
        String username = (String) body.getOrDefault("username", "");
        String password = (String) body.getOrDefault("password", "");
        String nonProxyHosts = (String) body.getOrDefault("nonProxyHosts", "localhost,127.0.0.1");
        boolean disableSslVerification = Boolean.TRUE.equals(body.get("disableSslVerification"));

        // 1. 运行时生效
        proxyConfig.updateProxy(enabled, host, port, type, username, password, nonProxyHosts, disableSslVerification);

        // 2. 持久化到 application.yml
        Path configPath = resolveConfigPath();

        // If no external config exists, create one in user's home directory
        if (configPath == null) {
            String userHome = System.getProperty("user.home");
            Path configDir = Paths.get(userHome, ".hisi-devtool");
            configPath = configDir.resolve("application.yml");
            log.info("[Settings] Creating new config file at {}", configPath);
        }

        try {
            persistProxyToYaml(configPath, enabled, host, port, type, username, password, nonProxyHosts, disableSslVerification);
        } catch (IOException e) {
            log.warn("[Settings] 代理配置已生效但持久化失败: {}", e.getMessage());
            return ApiResponse.success(null); // 运行时已生效，不阻塞
        }

        return ApiResponse.success(null);
    }

    private void persistProxyToYaml(Path configPath, boolean enabled, String host, int port,
                                     String type, String username, String password, String nonProxyHosts,
                                     boolean disableSslVerification)
            throws IOException {
        Yaml yaml = new Yaml();
        Map<String, Object> config;

        // Try to read existing config, or fall back to classpath
        if (Files.exists(configPath)) {
            try (FileInputStream fis = new FileInputStream(configPath.toFile())) {
                config = yaml.load(fis);
                if (config == null) config = new LinkedHashMap<>();
            }
        } else {
            ClassPathResource resource = new ClassPathResource("application.yml");
            if (resource.exists()) {
                try (InputStream is = resource.getInputStream()) {
                    config = yaml.load(is);
                    if (config == null) config = new LinkedHashMap<>();
                }
            } else {
                config = new LinkedHashMap<>();
            }
        }

        // 更新 proxy 部分
        Map<String, Object> proxySection = new LinkedHashMap<>();
        proxySection.put("enabled", enabled);
        proxySection.put("host", host);
        proxySection.put("port", port);
        proxySection.put("type", type);
        proxySection.put("username", username);
        proxySection.put("password", password);
        proxySection.put("non-proxy-hosts", nonProxyHosts);
        proxySection.put("disable-ssl-verification", disableSslVerification);
        config.put("proxy", proxySection);

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setIndent(2);
        options.setIndicatorIndent(0);

        Yaml dumper = new Yaml(options);
        try (FileWriter writer = new FileWriter(configPath.toFile())) {
            dumper.dump(config, writer);
        }
        log.info("[Settings] Proxy config persisted to {}", configPath);
    }
}
