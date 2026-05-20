package com.huawei.hisi.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.security.cert.X509Certificate;
import java.util.concurrent.atomic.AtomicReference;

/**
 * HTTP 代理配置
 * 支持运行时通过 /api/settings/proxy API 热修改，无需重启。
 * 所有外部 HTTP 调用（ZhipuService、SiliconFlowEmbeddingService 等）共享此 RestTemplate Bean。
 */
@Configuration
@ConfigurationProperties(prefix = "proxy")
public class ProxyConfig {

    private static final Logger log = LoggerFactory.getLogger(ProxyConfig.class);

    private boolean enabled;
    private String host = "";
    private int port;
    private String type = "HTTP";
    private String username = "";
    private String password = "";
    private String nonProxyHosts = "localhost,127.0.0.1";
    private boolean disableSslVerification = false;

    /**
     * 持有当前 RestTemplate 实例的原子引用，支持运行时替换
     */
    private final AtomicReference<RestTemplate> restTemplateRef = new AtomicReference<>();

    // ==================== Getters / Setters ====================

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getNonProxyHosts() { return nonProxyHosts; }
    public void setNonProxyHosts(String nonProxyHosts) { this.nonProxyHosts = nonProxyHosts; }

    public boolean isDisableSslVerification() { return disableSslVerification; }
    public void setDisableSslVerification(boolean disableSslVerification) { this.disableSslVerification = disableSslVerification; }

    // ==================== Bean ====================

    /**
     * 全局 RestTemplate Bean（Primary，覆盖 AsyncConfig 中的旧定义）
     * 初始化时根据 application.yml 配置决定是否使用代理
     */
    @Bean
    @Primary
    public RestTemplate restTemplate() {
        RestTemplate rt = buildRestTemplate();
        restTemplateRef.set(rt);
        return rt;
    }

    // ==================== 运行时热修改 ====================

    /**
     * 运行时更新代理配置并重建 RestTemplate
     * 由 SettingsController 的 /api/settings/proxy 端点调用
     */
    public void updateProxy(boolean enabled, String host, int port, String type,
                            String username, String password, String nonProxyHosts,
                            boolean disableSslVerification) {
        this.enabled = enabled;
        this.host = host != null ? host : "";
        this.port = port;
        this.type = type != null ? type : "HTTP";
        this.username = username != null ? username : "";
        this.password = password != null ? password : "";
        this.nonProxyHosts = nonProxyHosts != null ? nonProxyHosts : "localhost,127.0.0.1";
        this.disableSslVerification = disableSslVerification;

        // 重建 RestTemplate
        RestTemplate newRt = buildRestTemplate();
        restTemplateRef.set(newRt);

        log.info("[Proxy] 代理配置已更新: enabled={}, host={}, port={}, type={}, disableSsl={}",
            enabled, this.host, port, this.type, disableSslVerification);
    }

    /**
     * 获取当前有效的 RestTemplate（可能已被运行时替换）
     * 注意: Spring 注入的 RestTemplate Bean 引用不会自动更新，
     * 所以使用代理的服务应该通过 ProxyConfig.getCurrentRestTemplate() 获取。
     * 但为了向后兼容，@Bean 返回的初始实例仍然可用。
     */
    public RestTemplate getCurrentRestTemplate() {
        return restTemplateRef.get();
    }

    /**
     * 获取当前代理配置的快照（用于 API 返回）
     */
    public ProxySettings getSettings() {
        return new ProxySettings(enabled, host, port, type, username, password, nonProxyHosts, disableSslVerification);
    }

    // ==================== 内部方法 ====================

    private RestTemplate buildRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(30000);
        // 读超时 180s：embedding-3 / glm-4-flash 偶发慢响应（含长方法体输入）
        factory.setReadTimeout(180000);

        if (enabled && host != null && !host.isBlank() && port > 0) {
            Proxy.Type proxyType = "SOCKS".equalsIgnoreCase(type) ? Proxy.Type.SOCKS : Proxy.Type.HTTP;
            Proxy proxy = new Proxy(proxyType, new InetSocketAddress(host, port));
            factory.setProxy(proxy);

            // 设置代理认证
            if (username != null && !username.isBlank()) {
                Authenticator.setDefault(new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(
                            ProxyConfig.this.username,
                            ProxyConfig.this.password != null ? ProxyConfig.this.password.toCharArray() : new char[0]
                        );
                    }
                });
            }

            log.info("[Proxy] RestTemplate 已配置代理: {}://{}:{}", type, host, port);
        } else {
            log.info("[Proxy] RestTemplate 未使用代理（直连模式）");
        }

        // SSL证书验证配置
        if (disableSslVerification) {
            disableSslVerification();
            log.warn("[Proxy] SSL证书验证已禁用（仅用于内网/测试环境）");
        }

        return new RestTemplate(factory);
    }

    /**
     * 禁用SSL证书验证（信任所有证书
     * 注意：仅用于内网开发/测试环境，生产环境使用请使用
     */
    private void disableSslVerification() {
        try {
            // 创建信任所有证书的TrustManager
            TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                    public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                }
            };

            // 初始化SSLContext
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, trustAllCerts, new java.security.SecureRandom());

            // 设置默认的SSLSocketFactory
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());

            // 设置HostnameVerifier信任所有主机名
            HostnameVerifier allHostsValid = new HostnameVerifier() {
                public boolean verify(String hostname, SSLSession session) { return true; }
            };
            HttpsURLConnection.setDefaultHostnameVerifier(allHostsValid);

        } catch (Exception e) {
            log.error("[Proxy] 禁用SSL证书验证失败", e);
        }
    }

    // ==================== DTO ====================

    /**
     * 代理配置快照 DTO
     */
    public record ProxySettings(
        boolean enabled,
        String host,
        int port,
        String type,
        String username,
        String password,
        String nonProxyHosts,
        boolean disableSslVerification
    ) {}
}
