package com.huawei.hisi.piagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * pi 的安装与配置准备（照 CodegraphSidecarService 的进程先例 + McpController.checkNodeInstalled 的检测方式）。
 *
 * <p>{@link #ensure()}：总开关开启时检查 node（要求 ≥ 22.19，pi 的 engines 约束）与已安装的 pi 版本，
 * 缺失则 npm install 到 {@code piagent.install-dir}（锁定版本）；随后生成：
 * <ul>
 *   <li>{@code <agent-dir>/models.json}：网关 provider，apiKey 用 {@code $HISI_LLM_KEY} 环境插值——
 *       真实密钥由进程启动时注入环境变量，不落盘；</li>
 *   <li>{@code <agent-dir>/mcp.json}：hisi-mcp-server 的 stdio 连接（35 个只读工具）；</li>
 *   <li>{@code <agent-dir>/settings.json}：pi 自带会话压缩的触发阈值与保留量（上下文管理的第二层）。</li>
 * </ul>
 */
@Slf4j
@Component
public class PiAgentInstaller {

    private static final String REQUIRED_NODE_MAJOR = "22.19.0";
    private static final String PACKAGE = "@earendil-works/pi-coding-agent";

    private final PiAgentProperties properties;
    private final Environment environment;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private Path installDir;
    private Path agentDir;

    public PiAgentInstaller(PiAgentProperties properties,
                            Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @PostConstruct
    public void ensure() {
        if (!properties.isEnabled()) {
            log.info("[PiAgent] 未启用，跳过安装");
            return;
        }
        // 测试上下文（@SpringBootTest + test profile）不真正安装/写配置
        for (String profile : environment.getActiveProfiles()) {
            if ("test".equals(profile)) {
                log.info("[PiAgent] test profile，跳过安装");
                return;
            }
        }
        try {
            installDir = resolve(properties.getInstallDir());
            agentDir = resolve(properties.getAgentDir());
            Files.createDirectories(installDir);
            Files.createDirectories(agentDir);

            checkNode();
            ensurePackage();
            writeModelsJson();
            writeMcpJson();
            writeSettingsJson();
            log.info("[PiAgent] 就绪: cli={} agentDir={}", cliEntry(), agentDir);
        } catch (Exception e) {
            // 安装失败不阻断启动：所有流仍在关闭状态，切换开关时会再报错
            log.error("[PiAgent] 初始化失败（不影响老流程，切流前需修复）: {}", e.getMessage());
            installDir = null;
        }
    }

    /** pi CLI 入口（node 直跑包内 bundle，跨平台、不依赖 PATH）。 */
    public Path cliEntry() {
        return installDir.resolve("node_modules").resolve(PACKAGE).resolve("dist").resolve("bundle").resolve("cli.js");
    }

    public Path agentDir() { return agentDir; }
    public boolean ready() { return installDir != null; }

    private void checkNode() {
        String version = run(List.of("node", "-v"));
        if (version == null) {
            throw new IllegalStateException("未检测到 node（pi 需要 node >= " + REQUIRED_NODE_MAJOR + "）");
        }
        if (compareVersions(version.trim().replaceFirst("^v", ""), REQUIRED_NODE_MAJOR) < 0) {
            throw new IllegalStateException("node 版本过低：" + version + "（pi 需要 >= " + REQUIRED_NODE_MAJOR + "）");
        }
    }

    private void ensurePackage() {
        Path packageJson = installDir.resolve("node_modules").resolve(PACKAGE).resolve("package.json");
        if (Files.exists(packageJson)) {
            try {
                Map<?, ?> meta = objectMapper.readValue(Files.readString(packageJson), Map.class);
                if (properties.getPackageVersion().equals(String.valueOf(meta.get("version")))) {
                    return;
                }
            } catch (Exception ignored) {
            }
        }
        log.info("[PiAgent] 安装 {}@{} 到 {}", PACKAGE, properties.getPackageVersion(), installDir);
        // Windows 上 npm 是 npm.cmd，ProcessBuilder 不能直接执行，需带扩展名
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String npm = windows ? "npm.cmd" : "npm";
        String output = run(List.of(npm, "install",
                "--prefix", installDir.toString(),
                "--no-save", "--no-audit", "--no-fund", "--loglevel", "error",
                "--registry", properties.getRegistry(),
                PACKAGE + "@" + properties.getPackageVersion()));
        if (output == null) {
            throw new IllegalStateException("npm install 失败（查看后端日志）");
        }
    }

    private void writeModelsJson() throws Exception {
        Map<String, Object> models = Map.of(
                "providers", Map.of(properties.getModelProvider(), Map.of(
                        "baseUrl", properties.getModelBaseUrl(),
                        "api", "anthropic-messages",
                        "apiKey", "$HISI_LLM_KEY",
                        "models", List.of(Map.of("id", properties.getModelId())))));
        Files.writeString(agentDir.resolve("models.json"),
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(models), StandardCharsets.UTF_8);
    }

    private void writeMcpJson() throws Exception {
        String mcpDist = resolveMcpDist();
        if (mcpDist == null) {
            log.warn("[PiAgent] 未找到 hisi-mcp-server 入口，跳过 mcp.json（MCP 工具不可用）");
            return;
        }
        Map<String, Object> mcp = Map.of("mcpServers", Map.of("hisi-mcp-server", Map.of(
                "command", "node",
                "args", List.of(mcpDist),
                "env", Map.of("HISI_API_URL", "http://localhost:8080"))));
        Files.writeString(agentDir.resolve("mcp.json"),
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(mcp), StandardCharsets.UTF_8);
    }

    /**
     * pi 自带的会话压缩（上下文管理第二层）：显式写进 {@code settings.json}，不从默认值里猜。
     * 只接管 {@code compaction} 一节，文件里其它已有配置原样保留。
     */
    private void writeSettingsJson() throws Exception {
        writeSettingsJson(agentDir, properties.getContext(), objectMapper);
    }

    static void writeSettingsJson(Path agentDir, PiAgentProperties.Context context, ObjectMapper objectMapper) throws Exception {
        Path file = agentDir.resolve("settings.json");
        Map<String, Object> settings = new LinkedHashMap<>();
        if (Files.exists(file)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> existing = objectMapper.readValue(Files.readString(file, StandardCharsets.UTF_8), Map.class);
            settings.putAll(existing);
        }
        settings.put("compaction", Map.of(
                "enabled", true,
                "reserveTokens", context.getCompactionReserveTokens(),
                "keepRecentTokens", context.getCompactionKeepRecentTokens()));
        Files.writeString(file,
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(settings), StandardCharsets.UTF_8);
    }

    private String resolveMcpDist() {
        String configured = properties.getMcpDistPath();
        if (configured != null && !configured.isBlank()) {
            Path path = resolve(configured);
            return Files.exists(path) ? path.toString() : null;
        }
        // 常见位置：monorepo 里的 hisi-mcp-server/dist/index.js
        String backendHome = System.getProperty("user.dir");
        for (String candidate : List.of(
                "../hisi-mcp-server/dist/index.js",
                "hisi-mcp-server/dist/index.js",
                "../hisi_dev_tool v5.0/hisi-mcp-server/dist/index.js")) {
            Path path = Path.of(backendHome).resolve(candidate).normalize();
            if (Files.exists(path)) {
                return path.toString();
            }
        }
        return null;
    }

    private Path resolve(String raw) {
        String value = raw;
        if (value.startsWith("${user.home}")) {
            value = value.replace("${user.home}", System.getProperty("user.home"));
        }
        value = environment.resolvePlaceholders(value);
        return Path.of(value).toAbsolutePath().normalize();
    }

    /** 跑命令并返回输出；失败返回 null 并把输出记到日志。 */
    private String run(List<String> command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                    if (output.length() > 200_000) {
                        output.delete(0, output.length() - 200_000);
                    }
                }
            }
            boolean finished = process.waitFor(10, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                log.error("[PiAgent] 命令超时: {}", String.join(" ", command));
                return null;
            }
            if (process.exitValue() != 0) {
                log.error("[PiAgent] 命令失败 exit={}: {}\n{}", process.exitValue(),
                        String.join(" ", command), output);
                return null;
            }
            return output.toString();
        } catch (Exception e) {
            log.error("[PiAgent] 命令执行异常: {}", e.getMessage());
            return null;
        }
    }

    static int compareVersions(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int length = Math.max(pa.length, pb.length);
        for (int i = 0; i < length; i++) {
            int va = i < pa.length ? Integer.parseInt(pa[i]) : 0;
            int vb = i < pb.length ? Integer.parseInt(pb[i]) : 0;
            if (va != vb) {
                return Integer.compare(va, vb);
            }
        }
        return 0;
    }
}
