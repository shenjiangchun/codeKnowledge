package com.huawei.hisi.apm.service;

import com.huawei.hisi.apm.config.ApmConfig;
import com.huawei.hisi.config.ProxyConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Authenticator;
import okhttp3.Credentials;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Manages the OpenTelemetry Java Agent JAR file -- checking if it exists
 * locally and downloading it from Maven Central or GitHub releases on first use.
 *
 * <p>Resolution priority:
 * <ol>
 *   <li>Explicit path from {@link ApmConfig#getOtelAgentPath()}</li>
 *   <li>Bundled JAR under {@code lib/otel-agent/} in the project directory (zero-setup)</li>
 *   <li>Cached JAR under {@code ~/.hisi-devtool/otel-agent/}</li>
 *   <li>Download from Maven Central (China-friendly) → GitHub releases (fallback)</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OtelAgentManager {

    private static final String AGENT_DIR = ".hisi-devtool/otel-agent";
    private static final String BUNDLED_AGENT_DIR = "lib/otel-agent";

    /**
     * Download URLs ordered by priority: Maven Central first (accessible from
     * China / internal networks via Aliyun mirror), then GitHub releases.
     */
    private static final List<String> DOWNLOAD_URL_TEMPLATES = List.of(
            "https://maven.aliyun.com/repository/central/io/opentelemetry/javaagent/opentelemetry-javaagent/%s/opentelemetry-javaagent-%s.jar",
            "https://repo1.maven.org/maven2/io/opentelemetry/javaagent/opentelemetry-javaagent/%s/opentelemetry-javaagent-%s.jar",
            "https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v%s/opentelemetry-javaagent.jar"
    );

    private final ApmConfig apmConfig;
    private final ProxyConfig proxyConfig;

    /**
     * Returns the path to the OTel Java Agent JAR.
     * Downloads from GitHub releases if not present locally.
     *
     * @return absolute path to the agent JAR
     * @throws IOException if download fails
     */
    public String ensureAgentAvailable() throws IOException {
        // 1. Check explicit config path
        String configPath = apmConfig.getOtelAgentPath();
        if (configPath != null && !configPath.isEmpty()) {
            Path path = Paths.get(configPath);
            if (Files.exists(path)) {
                log.info("[OTel Agent] Using configured path: {}", path);
                return path.toAbsolutePath().toString();
            }
            log.warn("[OTel Agent] Configured path does not exist: {}, falling back to auto-detect", configPath);
        }

        String version = apmConfig.getOtelAgentVersion();

        // 2. Check bundled JAR shipped with the project (zero-setup for new developers)
        Path bundledPath = getBundledAgentPath(version);
        if (bundledPath != null && Files.exists(bundledPath)) {
            log.info("[OTel Agent] Using bundled agent: {}", bundledPath);
            return bundledPath.toAbsolutePath().toString();
        }

        // 3. Check cached version in user home
        Path cachedPath = getCachedAgentPath(version);
        if (Files.exists(cachedPath)) {
            log.info("[OTel Agent] Using cached agent: {}", cachedPath);
            return cachedPath.toAbsolutePath().toString();
        }

        // 4. Download
        log.info("[OTel Agent] Downloading version {} ...", version);
        downloadAgent(version, cachedPath);
        log.info("[OTel Agent] Downloaded to: {}", cachedPath);
        return cachedPath.toAbsolutePath().toString();
    }

    /**
     * Quick check if agent is available without downloading.
     */
    public boolean isAgentAvailable() {
        String configPath = apmConfig.getOtelAgentPath();
        if (configPath != null && !configPath.isEmpty() && Files.exists(Paths.get(configPath))) {
            return true;
        }
        String version = apmConfig.getOtelAgentVersion();
        Path bundled = getBundledAgentPath(version);
        if (bundled != null && Files.exists(bundled)) {
            return true;
        }
        return Files.exists(getCachedAgentPath(version));
    }

    /**
     * Get the expected cache path for a given version.
     */
    public Path getCachedAgentPath(String version) {
        String userHome = System.getProperty("user.home");
        return Paths.get(userHome, AGENT_DIR, "opentelemetry-javaagent-" + version + ".jar");
    }

    /**
     * Resolve the bundled agent JAR shipped inside the project's {@code lib/otel-agent/} directory.
     * This enables zero-setup for developers who clone the repo — no download required.
     *
     * <p>Detection: walks up from CWD looking for the {@code lib/otel-agent/} directory,
     * so it works whether the app is started from the module root or the repo root.
     *
     * @return path to the bundled JAR, or {@code null} if not found
     */
    private Path getBundledAgentPath(String version) {
        String jarName = "opentelemetry-javaagent-" + version + ".jar";

        // Try relative to current working directory (typical: project module root)
        Path cwd = Paths.get("").toAbsolutePath();
        Path candidate = cwd.resolve(BUNDLED_AGENT_DIR).resolve(jarName);
        if (Files.exists(candidate)) {
            return candidate;
        }

        // Try one level up (repo root → module/lib/otel-agent/)
        Path parent = cwd.getParent();
        if (parent != null) {
            candidate = parent.resolve(BUNDLED_AGENT_DIR).resolve(jarName);
            if (Files.exists(candidate)) {
                return candidate;
            }
        }

        // Try using the class location to find the project root
        try {
            Path classPath = Paths.get(
                    OtelAgentManager.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            // classPath is typically target/classes — go up to module root
            Path moduleRoot = classPath.getParent().getParent();
            candidate = moduleRoot.resolve(BUNDLED_AGENT_DIR).resolve(jarName);
            if (Files.exists(candidate)) {
                return candidate;
            }
        } catch (Exception e) {
            log.debug("[OTel Agent] Could not resolve bundled path via class location: {}", e.getMessage());
        }

        return null;
    }

    private void downloadAgent(String version, Path targetPath) throws IOException {
        Files.createDirectories(targetPath.getParent());

        OkHttpClient client = buildHttpClient();
        IOException lastError = null;

        for (String template : DOWNLOAD_URL_TEMPLATES) {
            String url = buildUrl(template, version);
            log.info("[OTel Agent] Trying download from: {} (proxy={})", url, proxyConfig.isEnabled());

            try {
                downloadFrom(client, url, targetPath);
                log.info("[OTel Agent] Downloaded {} bytes from {}", Files.size(targetPath), url);
                return; // success
            } catch (IOException e) {
                lastError = e;
                log.warn("[OTel Agent] Failed to download from {}: {}", url, e.getMessage());
            }
        }

        throw new IOException(
                "Failed to download OTel agent v" + version + " from all sources. "
                        + "Please download manually and set apm.otel-agent-path in application.yml. "
                        + "Last error: " + (lastError != null ? lastError.getMessage() : "unknown"));
    }

    /**
     * Build URL from template. Maven Central templates use version twice
     * (path + filename), GitHub template uses it once.
     */
    private String buildUrl(String template, String version) {
        long placeholderCount = template.chars().filter(c -> c == '%').count();
        if (placeholderCount >= 2) {
            return String.format(template, version, version);
        }
        return String.format(template, version);
    }

    private void downloadFrom(OkHttpClient client, String url, Path targetPath) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + " from " + url);
            }
            if (response.body() == null) {
                throw new IOException("Empty response body from " + url);
            }

            // Write to temp file first, then atomic move
            Path tempFile = targetPath.resolveSibling(targetPath.getFileName() + ".tmp");
            try (InputStream in = response.body().byteStream()) {
                Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(tempFile, targetPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Build an OkHttpClient that respects the application's proxy configuration.
     * When {@code proxy.enabled=true}, the client routes through the configured
     * HTTP/SOCKS proxy, with optional authentication and SSL bypass.
     */
    private OkHttpClient buildHttpClient() {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(300, TimeUnit.SECONDS) // Agent JAR is ~17MB
                .followRedirects(true);

        if (proxyConfig.isEnabled()
                && proxyConfig.getHost() != null
                && !proxyConfig.getHost().isBlank()
                && proxyConfig.getPort() > 0) {

            Proxy.Type proxyType = "SOCKS".equalsIgnoreCase(proxyConfig.getType())
                    ? Proxy.Type.SOCKS
                    : Proxy.Type.HTTP;
            Proxy proxy = new Proxy(proxyType, new InetSocketAddress(proxyConfig.getHost(), proxyConfig.getPort()));
            builder.proxy(proxy);

            log.info("[OTel Agent] Using proxy: {}://{}:{}",
                    proxyConfig.getType(), proxyConfig.getHost(), proxyConfig.getPort());

            // Proxy authentication
            if (proxyConfig.getUsername() != null && !proxyConfig.getUsername().isBlank()) {
                Authenticator proxyAuth = (route, response) -> {
                    String credential = Credentials.basic(
                            proxyConfig.getUsername(),
                            proxyConfig.getPassword() != null ? proxyConfig.getPassword() : "");
                    return response.request().newBuilder()
                            .header("Proxy-Authorization", credential)
                            .build();
                };
                builder.proxyAuthenticator(proxyAuth);
            }
        }

        // SSL verification bypass (for internal network environments)
        if (proxyConfig.isDisableSslVerification()) {
            try {
                X509TrustManager trustAll = new X509TrustManager() {
                    @Override
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                };
                SSLContext sslContext = SSLContext.getInstance("TLS");
                sslContext.init(null, new TrustManager[]{trustAll}, new java.security.SecureRandom());
                builder.sslSocketFactory(sslContext.getSocketFactory(), trustAll);
                builder.hostnameVerifier((hostname, session) -> true);
                log.warn("[OTel Agent] SSL verification disabled (internal network mode)");
            } catch (Exception e) {
                log.error("[OTel Agent] Failed to disable SSL verification", e);
            }
        }

        return builder.build();
    }
}
