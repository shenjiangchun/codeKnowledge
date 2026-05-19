package com.huawei.hisi.apm.service;

import com.huawei.hisi.apm.config.ApmConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.TimeUnit;

/**
 * Manages the OpenTelemetry Java Agent JAR file -- checking if it exists
 * locally and downloading it from GitHub releases on first use.
 *
 * <p>Resolution priority:
 * <ol>
 *   <li>Explicit path from {@link ApmConfig#getOtelAgentPath()}</li>
 *   <li>Cached JAR under {@code ~/.hisi-devtool/otel-agent/}</li>
 *   <li>Download from GitHub releases</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OtelAgentManager {

    private static final String AGENT_DIR = ".hisi-devtool/otel-agent";
    private static final String DOWNLOAD_URL_TEMPLATE =
            "https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v%s/opentelemetry-javaagent.jar";

    private final ApmConfig apmConfig;

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

        // 2. Check cached version
        String version = apmConfig.getOtelAgentVersion();
        Path cachedPath = getCachedAgentPath(version);
        if (Files.exists(cachedPath)) {
            log.info("[OTel Agent] Using cached agent: {}", cachedPath);
            return cachedPath.toAbsolutePath().toString();
        }

        // 3. Download
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
        return Files.exists(getCachedAgentPath(apmConfig.getOtelAgentVersion()));
    }

    /**
     * Get the expected cache path for a given version.
     */
    public Path getCachedAgentPath(String version) {
        String userHome = System.getProperty("user.home");
        return Paths.get(userHome, AGENT_DIR, "opentelemetry-javaagent-" + version + ".jar");
    }

    private void downloadAgent(String version, Path targetPath) throws IOException {
        Files.createDirectories(targetPath.getParent());

        String url = String.format(DOWNLOAD_URL_TEMPLATE, version);

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(300, TimeUnit.SECONDS) // Agent JAR is ~17MB
                .followRedirects(true)
                .build();

        Request request = new Request.Builder()
                .url(url)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Failed to download OTel agent from " + url + ": HTTP " + response.code());
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

            log.info("[OTel Agent] Downloaded {} bytes", Files.size(targetPath));
        }
    }
}
