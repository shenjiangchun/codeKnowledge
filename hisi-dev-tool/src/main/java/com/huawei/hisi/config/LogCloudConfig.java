package com.huawei.hisi.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 日志云配置属性类
 * 支持 HTTP API 和 Playwright 两种调用方式
 */
@Data
@Component
@ConfigurationProperties(prefix = "logcloud")
public class LogCloudConfig {

    /**
     * 日志云基础 URL（用于 Playwright 浏览器访问）
     */
    private String baseUrl = "https://console.his.huawei.com";

    /**
     * 应用 ID
     */
    private String appId = "com.huawei.hiapm";

    /**
     * 登录用户名（Playwright 模式用）
     */
    private String username;

    /**
     * 登录密码（Playwright 模式用）
     */
    private String password;

    /**
     * API 配置
     */
    private ApiConfig api = new ApiConfig();

    /**
     * 浏览器配置（Playwright 模式用）
     */
    private BrowserConfig browser = new BrowserConfig();

    /**
     * API 配置
     */
    @Data
    public static class ApiConfig {
        /**
         * API 基础 URL
         */
        private String baseUrl = "https://apig-beta.his.huawei.com/api/gaia/rm-openapi";

        /**
         * 查询接口路径
         */
        private String queryPath = "/platforms/logQueryService/dslQuery/hiapm_error_fetcher";

        /**
         * 请求头：X-HW-ID
         */
        private String headerXhwId = "com.huawei.hiapm";

        /**
         * 请求头：x-hw-appkey
         * 从配置文件或环境变量 LOGCLOUD_APPKEY 读取
         */
        private String headerAppkey = "";

        /**
         * 请求超时时间（毫秒）
         */
        private long timeout = 60000;

        /**
         * 连接超时时间（毫秒）
         */
        private long connectTimeout = 30000;
    }

    /**
     * 浏览器配置（Playwright 模式用）
     */
    @Data
    public static class BrowserConfig {
        /**
         * 无头模式
         */
        private boolean headless = true;

        /**
         * 超时时间（毫秒）
         */
        private long timeout = 60000;

        /**
         * 缓存过期时间（秒）
         */
        private long cacheExpire = 1800;
    }
}