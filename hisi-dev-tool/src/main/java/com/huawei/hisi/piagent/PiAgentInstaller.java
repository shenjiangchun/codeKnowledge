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
 *   <li>{@code <agent-dir>/mcp.json}：hisi-mcp-server 的 stdio 连接（35 个只读工具 + 5 个 act_* 动作工具，每个流按清单取子集）；
 *       不写 env，MCP 子进程直接继承 pi 进程的环境；</li>
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

    // 可能被多个流的线程读写（ensureReady 会重入 ensure），要保证可见性
    private volatile Path installDir;
    private volatile Path agentDir;
    /** pi 装好、配置写完之后的就绪标志；安装过程中保持 false，流这时调用 ensureReady() 会等 ensure() 结束。 */
    private volatile boolean ready;

    public PiAgentInstaller(PiAgentProperties properties,
                            Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    /**
     * 启动自检。已经装好就同步把配置写完（很快）；需要跑 npm 时（pi 没装、hisi-mcp-server 要构建）
     * 转后台线程——远端慢网络上 npm 可能跑好几分钟，不能让启动卡在这里。
     */
    @PostConstruct
    void init() {
        if (skipped()) {
            return;
        }
        try {
            installDir = resolve(properties.getInstallDir());
            agentDir = resolve(properties.getAgentDir());
            if (needsNpm()) {
                log.info("[PiAgent] pi 未安装或 hisi-mcp-server 需要构建：转入后台执行，不阻塞启动（首次有流要用时会等它完成）");
                Thread worker = new Thread(this::ensure, "pi-agent-install");
                worker.setDaemon(true);
                worker.start();
                return;
            }
        } catch (Exception e) {
            log.error("[PiAgent] 安装目录不可用: {}", e.getMessage());
            installDir = null;
            return;
        }
        ensure();
    }

    /** 自检与安装：已就绪直接返回；否则装 pi、构建 MCP、生成配置，全部完成才置就绪。 */
    public synchronized void ensure() {
        if (skipped() || ready()) {
            return;
        }
        try {
            installDir = resolve(properties.getInstallDir());
            agentDir = resolve(properties.getAgentDir());
            Files.createDirectories(installDir);
            Files.createDirectories(agentDir);

            checkNode();
            ensurePackage();
            ensureMcpDist();
            writeModelsJson();
            writeMcpJson();
            writeSettingsJson();
            syncSkills();
            ready = true;
            log.info("[PiAgent] 就绪: cli={} agentDir={}", cliEntry(), agentDir);
        } catch (Exception e) {
            // 安装失败不阻断启动；但所有 agent 流都依赖 pi，装好之前它们都不可用——
            // 下一次有流要用 pi 时 ensureReady() 会再装一次，不需要重启后端
            log.error("[PiAgent] 安装/初始化失败，pi 相关功能在装好之前不可用（下次执行时会自动重试）: {}", e.getMessage());
            ready = false;
            installDir = null;
        }
    }

    /** 总开关关闭、或测试上下文（@SpringBootTest + test profile）时不安装、不写配置。 */
    private boolean skipped() {
        if (!properties.isEnabled()) {
            log.info("[PiAgent] 未启用，跳过安装");
            return true;
        }
        for (String profile : environment.getActiveProfiles()) {
            if ("test".equals(profile)) {
                log.info("[PiAgent] test profile，跳过安装");
                return true;
            }
        }
        return false;
    }

    /** 需要跑 npm 的两件事：pi 没装好；hisi-mcp-server 的 dist 缺失或比源码旧。 */
    private boolean needsNpm() throws Exception {
        if (!piInstalled()) {
            return true;
        }
        Path project = resolveMcpProject();
        if (project == null) {
            return false;
        }
        Path dist = project.resolve("dist").resolve("index.js");
        return !Files.exists(dist) || mcpSourcesNewerThan(project, dist);
    }

    /**
     * 流要用 pi 之前调：已就绪直接返回；启动时没装上、或后台安装还在进行，就在这里等它装完。
     * 总开关关着时不重试。
     */
    public void ensureReady() {
        if (!ready() && properties.isEnabled()) {
            ensure();
        }
    }

    /** pi CLI 入口（node 直跑包内 bundle，跨平台、不依赖 PATH）。 */
    public Path cliEntry() {
        return installDir.resolve("node_modules").resolve(PACKAGE).resolve("dist").resolve("bundle").resolve("cli.js");
    }

    public Path agentDir() { return agentDir; }
    public boolean ready() { return ready; }

    /** skill 名 → pi skills 目录下的路径（供会话按声明装配 {@code --skill}）。 */
    public Path skillPath(String name) {
        return agentDir.resolve("skills").resolve(name);
    }

    private void checkNode() {
        String version = run(List.of("node", "-v"));
        if (version == null) {
            throw new IllegalStateException("未检测到 node（pi 需要 node >= " + REQUIRED_NODE_MAJOR + "）");
        }
        if (compareVersions(version.trim().replaceFirst("^v", ""), REQUIRED_NODE_MAJOR) < 0) {
            throw new IllegalStateException("node 版本过低：" + version + "（pi 需要 >= " + REQUIRED_NODE_MAJOR + "）");
        }
    }

    private void ensurePackage() throws Exception {
        if (piInstalled()) {
            return;
        }
        Path packageDir = installDir.resolve("node_modules").resolve(PACKAGE);
        // 包目录在但入口不在（装到一半被打断）、或版本不对：npm 会认为已满足而什么都不做，先删掉再装
        if (Files.exists(packageDir)) {
            deleteRecursively(packageDir);
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
        if (!Files.exists(cliEntry())) {
            throw new IllegalStateException("npm install 已结束但 pi 入口不存在：" + cliEntry());
        }
    }

    /** pi 本体是否装好：包版本与锁定版本一致，且 CLI 入口在。 */
    private boolean piInstalled() {
        return installDir != null && isInstalled(installDir.resolve("node_modules").resolve(PACKAGE).resolve("package.json"),
                cliEntry(), properties.getPackageVersion(), objectMapper);
    }

    /**
     * 装好的判据：包版本与锁定版本一致，且 CLI 入口文件在。
     * 只看 package.json 不够——装到一半被打断时它可能已经落下，而入口还没有。
     */
    static boolean isInstalled(Path packageJson, Path cliEntry, String expectedVersion, ObjectMapper objectMapper) {
        if (!Files.exists(cliEntry)) {
            return false;
        }
        try {
            Map<?, ?> meta = objectMapper.readValue(Files.readString(packageJson), Map.class);
            return expectedVersion.equals(String.valueOf(meta.get("version")));
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * 两个 provider 指向同一个网关模型：
     * <ul>
     *   <li>{@code <model-provider>}：不声明推理能力，pi 不发 thinking 参数，思考与否由网关默认决定（agent 会话用）；</li>
     *   <li>{@code <model-provider>-nothink}：声明 {@code reasoning: true}，配合 {@code --thinking off} 时 pi 会显式
     *       发送 {@code thinking: disabled}——一次性结构化调用用，思考 token 算在输出预算里，不关掉正文容易被挤空。</li>
     * </ul>
     */
    static final String NO_THINK_PROVIDER_SUFFIX = "-nothink";

    /**
     * models.json 里 provider 的名字。对外没有含义（models.json 与 --model 引用都从这里派生，
     * 改名不会破坏任何东西），所以不做配置项，省一个需要解释的开关。
     */
    static final String MODEL_PROVIDER = "hisi-gw";

    private void writeModelsJson() throws Exception {
        Map<String, Object> models = Map.of("providers", Map.of(
                MODEL_PROVIDER, provider(false),
                MODEL_PROVIDER + NO_THINK_PROVIDER_SUFFIX, provider(true)));
        Files.writeString(agentDir.resolve("models.json"),
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(models), StandardCharsets.UTF_8);
    }

    private Map<String, Object> provider(boolean reasoning) {
        return Map.of(
                "baseUrl", properties.getModelBaseUrl(),
                "api", "anthropic-messages",
                "apiKey", "$HISI_LLM_KEY",
                "models", List.of(reasoning
                        ? Map.of("id", properties.getModelId(), "reasoning", true)
                        : Map.of("id", properties.getModelId())));
    }

    private void writeMcpJson() throws Exception {
        String mcpDist = resolveMcpDist();
        if (mcpDist == null) {
            log.warn("[PiAgent] 未找到 hisi-mcp-server 入口，跳过 mcp.json（MCP 工具不可用）");
            return;
        }
        Map<String, Object> mcp = Map.of("mcpServers",
                Map.of("hisi-mcp-server", mcpServerEntry(Path.of(mcpDist))));
        Files.writeString(agentDir.resolve("mcp.json"),
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(mcp), StandardCharsets.UTF_8);
    }

    /**
     * mcp.json 里只写启动命令，不写 {@code env}：pi 会把自身进程环境原样传给 stdio MCP 子进程（实测），
     * {@code HISI_API_URL}、{@code HISI_SCOPE_TOKEN} 由 PiFlowClient 每次启动 pi 时按需放进环境。
     *
     * <p>不能在这里写 {@code ${HISI_SCOPE_TOKEN}} 这类环境变量引用：pi 把空值也当未设置、也不支持默认值写法，
     * 只读流不带令牌时解析失败，整个 MCP 服务连接失败，所有只读 ReAct 流都拿不到工具（2026-11-26 真机故障）。
     */
    static Map<String, Object> mcpServerEntry(Path mcpDist) {
        return Map.of("command", "node", "args", List.of(mcpDist.toString()));
    }

    /** MCP 服务回头调后端用的地址：端口跟着 server.port 走，换端口启动时工具不会打到别的实例上。 */
    String backendBaseUrl() {
        return "http://localhost:" + environment.getProperty("server.port", "8080");
    }

    /**
     * 把仓库里的 skill（{@code classpath:pi-skills/&lt;name&gt;/SKILL.md}，清单见
     * {@link com.huawei.hisi.agentbase.AgentRegistry}）同步到 pi 的 skills 目录。
     * 已不在声明里的旧 skill 一并删掉：skill 名会出现在系统提示词里，留着过期的会误导模型。
     */
    private void syncSkills() throws Exception {
        Path skillsDir = agentDir.resolve("skills");
        Files.createDirectories(skillsDir);
        java.util.Set<String> kept = new java.util.TreeSet<>();
        for (com.huawei.hisi.agentbase.AgentRegistry.ReactAgent agent
                : com.huawei.hisi.agentbase.AgentRegistry.agents().values()) {
            for (String name : agent.skills()) {
                kept.add(name);
                org.springframework.core.io.Resource resource = new org.springframework.core.io.ClassPathResource(
                        "pi-skills/" + name + "/SKILL.md", getClass().getClassLoader());
                if (!resource.exists()) {
                    log.error("[PiAgent] skill 文件缺失: pi-skills/{}/SKILL.md（声明里引用了它）", name);
                    continue;
                }
                copyResource(resource, skillsDir.resolve(name).resolve("SKILL.md"));
            }
        }
        try (java.util.stream.Stream<Path> existing = Files.list(skillsDir)) {
            for (Path dir : (Iterable<Path>) existing.filter(Files::isDirectory)::iterator) {
                if (!kept.contains(dir.getFileName().toString())) {
                    deleteRecursively(dir);
                    log.info("[PiAgent] 移除已下线的 skill: {}", dir.getFileName());
                }
            }
        }
        log.info("[PiAgent] 同步 skills：{}", kept);
    }

    /** 资源可能在 jar 里（getFile 会失败）：一律按流复制。 */
    private void copyResource(org.springframework.core.io.Resource source, Path target) throws Exception {
        Files.createDirectories(target.getParent());
        Files.deleteIfExists(target);
        try (java.io.InputStream in = source.getInputStream()) {
            Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void deleteRecursively(Path dir) throws Exception {
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            for (Path path : (Iterable<Path>) walk.sorted(java.util.Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(path);
            }
        }
    }

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
        Path project = resolveMcpProject();
        if (project == null) {
            return null;
        }
        Path dist = project.resolve("dist").resolve("index.js");
        return Files.exists(dist) ? dist.toString() : null;
    }

    /**
     * 找 hisi-mcp-server 的项目目录（不是 dist 入口）：配置了 mcp-dist-path 就只认配置，
     * 否则按 monorepo 常见相对位置探测（package.json 存在即视为项目目录）。
     */
    private Path resolveMcpProject() {
        String configured = properties.getMcpDistPath();
        if (configured != null && !configured.isBlank()) {
            Path path = resolve(configured);
            return Files.exists(path) ? path : null;
        }
        String backendHome = System.getProperty("user.dir");
        for (String candidate : List.of(
                "../hisi-mcp-server",
                "hisi-mcp-server",
                "../hisi_dev_tool v5.0/hisi-mcp-server")) {
            Path path = Path.of(backendHome).resolve(candidate).normalize();
            if (Files.exists(path.resolve("package.json"))) {
                return path;
            }
        }
        return null;
    }

    /**
     * 远端拉取分支后不再需要手工 npm install / tsc：dist 缺失或源码比 dist 新时，
     * 后端启动就地构建 hisi-mcp-server（pi 每次会话按 mcp.json 直接拉起 dist，无长驻 MCP 进程）。
     */
    private void ensureMcpDist() {
        Path project = resolveMcpProject();
        if (project == null) {
            log.warn("[PiAgent] 未找到 hisi-mcp-server 项目目录，跳过 mcp.json（MCP 工具不可用）");
            return;
        }
        Path dist = project.resolve("dist").resolve("index.js");
        if (Files.exists(dist) && !mcpSourcesNewerThan(project, dist)) {
            return;
        }
        log.info("[PiAgent] hisi-mcp-server 需要构建（dist 缺失或源码更新），开始 npm ci + build ...");
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String npm = windows ? "npm.cmd" : "npm";
        String install = run(project, List.of(npm, "ci", "--no-audit", "--no-fund", "--loglevel", "error",
                "--registry", properties.getRegistry()));
        if (install == null) {
            install = run(project, List.of(npm, "install", "--no-audit", "--no-fund", "--loglevel", "error",
                    "--registry", properties.getRegistry()));
        }
        if (install == null) {
            log.warn("[PiAgent] hisi-mcp-server 依赖安装失败，MCP 工具不可用（检查 node/npm 与网络）");
            return;
        }
        if (run(project, List.of(npm, "run", "build")) == null) {
            log.warn("[PiAgent] hisi-mcp-server 构建失败（tsc 报错），MCP 工具不可用");
        }
    }

    /** dist 不存在或任一 src/*.ts 比 dist/index.js 新，都视为需要重建。 */
    private boolean mcpSourcesNewerThan(Path project, Path dist) {
        try {
            long distTime = Files.getLastModifiedTime(dist).toMillis();
            Path src = project.resolve("src");
            if (!Files.exists(src)) {
                return true;
            }
            try (java.util.stream.Stream<Path> walk = Files.walk(src)) {
                return walk.filter(p -> p.toString().endsWith(".ts"))
                        .anyMatch(p -> {
                            try {
                                return Files.getLastModifiedTime(p).toMillis() > distTime;
                            } catch (Exception e) {
                                return true;
                            }
                        });
            }
        } catch (Exception e) {
            return true;
        }
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
        return run(null, command);
    }

    /** 同上，但指定工作目录（npm ci / tsc 要在 hisi-mcp-server 目录里跑）。 */
    private String run(Path cwd, List<String> command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            if (cwd != null) {
                pb.directory(cwd.toFile());
            }
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
