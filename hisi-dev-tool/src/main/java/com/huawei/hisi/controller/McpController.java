package com.huawei.hisi.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * MCP 安装包下载控制器
 */
@RestController
@RequestMapping("/api/mcp")
public class McpController {

    @Value("${mcp.project.path:}")
    private String mcpProjectPath;

    private final ExecutorService executor = Executors.newCachedThreadPool();

    /**
     * 获取 MCP 信息
     */
    @GetMapping("/info")
    public Map<String, Object> getMcpInfo() {
        Map<String, Object> info = new HashMap<>();
        info.put("version", "2.0.0");
        info.put("name", "hisi-dev-tool-mcp");
        info.put("description", "MCP server for HiSi DevTool - Knowledge Graph, Hybrid Search, Log Query");

        List<Map<String, Object>> tools = new ArrayList<>();

        // ── 知识图谱工具 (27) ──────────────────────────────────────────────
        tools.add(createToolInfo("kg_generate",        "同步生成知识图谱",           Arrays.asList("projectPath")));
        tools.add(createToolInfo("kg_tasks_generate",  "异步提交知识图谱生成任务",    Arrays.asList("projectPath")));
        tools.add(createToolInfo("kg_status",          "获取知识图谱服务状态",        Arrays.asList("projectPath", "projectPaths?")));
        tools.add(createToolInfo("kg_task_status",     "查询图谱生成任务进度",        Arrays.asList("projectPaths?")));
        tools.add(createToolInfo("kg_callers",         "查询方法的调用者（上游）",    Arrays.asList("className", "methodName", "projectPath", "projectPaths?")));
        tools.add(createToolInfo("kg_callees",         "查询方法的被调用者（下游）",  Arrays.asList("className", "methodName", "projectPath", "maxDepth?", "projectPaths?")));
        tools.add(createToolInfo("kg_method_detail",   "获取方法详情",               Arrays.asList("nodeId", "projectPath", "projectPaths?")));
        tools.add(createToolInfo("kg_method_by_class", "获取类中所有方法",            Arrays.asList("className", "projectPath", "projectPaths?")));
        tools.add(createToolInfo("kg_entry_points",    "获取项目入口点列表",          Arrays.asList("projectPath", "entryType?", "projectPaths?")));
        tools.add(createToolInfo("kg_call_chain_by_key",  "按入口Key获取完整调用链", Arrays.asList("entryKey", "projectPath", "projectPaths?")));
        tools.add(createToolInfo("kg_call_chain_by_type", "按类型获取调用链列表",    Arrays.asList("entryType", "projectPath", "projectPaths?")));
        tools.add(createToolInfo("kg_downstream",      "获取节点下游调用链",          Arrays.asList("nodeId", "projectPath", "maxDepth?", "projectPaths?")));
        tools.add(createToolInfo("kg_call_chain_graph","获取调用链图结构",            Arrays.asList("entryKey", "projectPath", "projectPaths?")));
        tools.add(createToolInfo("kg_affecting",       "获取影响指定方法的上游链",    Arrays.asList("className", "methodName", "projectPath", "projectPaths?")));
        tools.add(createToolInfo("kg_bridges",         "获取节点的桥接点",            Arrays.asList("nodeId", "projectPath", "projectPaths?")));
        tools.add(createToolInfo("kg_implementations", "获取接口的所有实现类",        Arrays.asList("interfaceName", "projectPath", "projectPaths?")));
        tools.add(createToolInfo("kg_interfaces",      "获取类实现的所有接口",        Arrays.asList("className", "projectPath", "projectPaths?")));
        tools.add(createToolInfo("kg_cycles_detect",   "检测循环调用",               Arrays.asList("projectPath", "entryKey?", "projectPaths?")));
        tools.add(createToolInfo("kg_mybatis_mappers", "获取MyBatis Mapper接口列表", Arrays.asList("projectPath", "projectPaths?")));
        tools.add(createToolInfo("kg_mybatis_sql",     "获取MyBatis SQL映射信息",    Arrays.asList("projectPath", "mapperInterface?", "statementType?", "projectPaths?")));
        tools.add(createToolInfo("kg_mapper_sql",      "获取指定Mapper的SQL详情",    Arrays.asList("mapperInterface", "projectPath", "projectPaths?")));
        tools.add(createToolInfo("kg_feign_chain",     "获取Feign服务调用链",        Arrays.asList("serviceName", "projectPath", "projectPaths?")));
        tools.add(createToolInfo("kg_mq_chain",        "获取MQ消息调用链",           Arrays.asList("topic", "projectPath", "projectPaths?")));
        tools.add(createToolInfo("kg_bridge_stats",    "获取桥接点统计信息",          Arrays.asList("projectPath", "projectPaths?")));
        tools.add(createToolInfo("kg_business_flow",   "生成业务流程文档",            Arrays.asList("projectPath", "entryKey", "outputFormat?", "projectPaths?")));
        tools.add(createToolInfo("kg_unit_test",       "生成单元测试建议",            Arrays.asList("projectPath", "className", "methodName?", "projectPaths?")));

        // ── 混合检索工具 (3) ──────────────────────────────────────────────
        tools.add(createToolInfo("hybrid_search",   "三层混合检索：关键词+向量+图遍历",       Arrays.asList("query", "projectPath", "limit?", "graphDepth?")));
        tools.add(createToolInfo("vector_sync",     "将项目代码向量化并同步到向量库",          Arrays.asList("projectName", "projectPath", "syncType?")));
        tools.add(createToolInfo("vector_status",   "获取向量库状态和统计信息",               Collections.emptyList()));

        // ── 日志查询工具 (4) ──────────────────────────────────────────────
        tools.add(createToolInfo("log_query",         "查询日志（关键字/级别/TraceId/时间范围）", Arrays.asList("keyword?", "logLevel?", "traceId?", "startTime?", "endTime?")));
        tools.add(createToolInfo("log_analyze",       "提交日志AI分析任务，返回reportId",        Arrays.asList("message?", "stackTrace?", "serviceName?")));
        tools.add(createToolInfo("log_report",        "获取AI分析报告详情",                      Arrays.asList("reportId")));
        tools.add(createToolInfo("log_report_status", "查询AI分析报告状态",                      Arrays.asList("reportId")));

        info.put("tools", tools);
        info.put("toolCount", tools.size());

        return info;
    }

    /**
     * 下载 MCP 安装包
     */
    @GetMapping("/download")
    public ResponseEntity<Resource> downloadMcp() throws IOException {
        // 确定 MCP 项目路径
        Path mcpPath = determineMcpPath();

        if (mcpPath == null || !Files.exists(mcpPath)) {
            throw new RuntimeException("MCP project not found");
        }

        // 创建临时 ZIP 文件
        Path tempZip = Files.createTempFile("hisi-dev-tool-mcp-", ".zip");

        try (ZipOutputStream zipOut = new ZipOutputStream(new FileOutputStream(tempZip.toFile()))) {
            // 添加必要文件
            addToZip(zipOut, mcpPath, "package.json");
            addToZip(zipOut, mcpPath, "tsconfig.json");
            addDirectoryToZip(zipOut, mcpPath, "src");
            addDirectoryToZip(zipOut, mcpPath, "dist");
            addDirectoryToZip(zipOut, mcpPath, "skills");
            addToZip(zipOut, mcpPath, "install-skills.bat");
            addToZip(zipOut, mcpPath, "install-skills.sh");
            addToZip(zipOut, mcpPath, "SKILLS_README.md");

            // 添加安装脚本
            addInstallScript(zipOut, "install-mcp.bat", createWindowsInstallScript());
            addInstallScript(zipOut, "install-mcp.sh", createUnixInstallScript());

            // 添加配置模板
            addConfigTemplate(zipOut);
        }

        Resource resource = new UrlResource(tempZip.toUri());

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"hisi-dev-tool-mcp-v1.2.0.zip\"")
                .body(resource);
    }

    /**
     * 在线安装 MCP（流式输出进度）
     * POST /api/mcp/install
     * 参数:
     *   - mcpDir: MCP 项目目录
     *   - projectDir: 要配置 MCP 的目标项目目录（可选，默认为当前工作目录）
     */
    @PostMapping(value = "/install", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter installMcp(@RequestBody Map<String, String> request) {
        SseEmitter emitter = new SseEmitter(300000L); // 5 分钟超时
        String mcpDir = request.get("mcpDir");
        String projectDir = request.get("projectDir");

        executor.execute(() -> {
            try {
                installMcpAsync(emitter, mcpDir, projectDir);
            } catch (Exception e) {
                try {
                    emitter.send(SseEmitter.event().name("error").data(e.getMessage()));
                    emitter.complete();
                } catch (IOException ignored) {}
            }
        });

        return emitter;
    }

    /**
     * 检查 MCP 安装状态
     * GET /api/mcp/status
     */
    @GetMapping("/status")
    public Map<String, Object> checkStatus(@RequestParam(required = false) String mcpDir) {
        Map<String, Object> status = new HashMap<>();

        // 检测 Claude Code CLI 是否安装
        boolean claudeCodeInstalled = checkClaudeCodeInstalled();
        status.put("claudeCodeInstalled", claudeCodeInstalled);
        status.put("claudeType", claudeCodeInstalled ? "Claude Code CLI" : "Claude Desktop");

        Path mcpPath = mcpDir != null && !mcpDir.isEmpty() ? Paths.get(mcpDir) : determineMcpPath();

        if (mcpPath == null || !Files.exists(mcpPath)) {
            status.put("installed", false);
            status.put("message", "MCP 目录不存在");
            return status;
        }

        status.put("mcpDir", mcpPath.toString());
        status.put("packageJsonExists", Files.exists(mcpPath.resolve("package.json")));
        status.put("nodeModulesExists", Files.exists(mcpPath.resolve("node_modules")));
        status.put("distExists", Files.exists(mcpPath.resolve("dist/index.js")));
        status.put("installed", Files.exists(mcpPath.resolve("dist/index.js")));

        // 检查 MCP 是否已配置（根据 Claude 类型）
        if (claudeCodeInstalled) {
            // Claude Code CLI: 检查 settings.json 中的 mcpServers 配置
            boolean mcpConfigured = checkClaudeCodeMcpConfigured();
            status.put("mcpConfigured", mcpConfigured);
            status.put("claudeConfigPath", getClaudeCodeSettingsPath());
        } else {
            // Claude Desktop: 检查 claude_desktop_config.json
            String claudeConfigPath = getClaudeDesktopConfigPath();
            status.put("claudeConfigPath", claudeConfigPath);
            status.put("claudeConfigExists", Files.exists(Paths.get(claudeConfigPath)));
            status.put("mcpConfigured", checkClaudeDesktopMcpConfigured(claudeConfigPath));
        }

        return status;
    }

    /**
     * 检查 Claude Code CLI 是否安装
     */
    private boolean checkClaudeCodeInstalled() {
        try {
            ProcessBuilder pb;
            String os = System.getProperty("os.name").toLowerCase();

            if (os.contains("win")) {
                pb = new ProcessBuilder("cmd", "/c", "claude --version");
            } else {
                pb = new ProcessBuilder("sh", "-c", "claude --version");
            }

            pb.redirectErrorStream(true);
            Process process = pb.start();
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 检查 Claude Code CLI 的 MCP 是否已配置
     */
    private boolean checkClaudeCodeMcpConfigured() {
        try {
            ProcessBuilder pb;
            String os = System.getProperty("os.name").toLowerCase();

            if (os.contains("win")) {
                pb = new ProcessBuilder("cmd", "/c", "claude mcp list");
            } else {
                pb = new ProcessBuilder("sh", "-c", "claude mcp list");
            }

            pb.redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            process.waitFor();
            // 检查输出中是否包含 hisi-dev-tool
            return output.toString().contains("hisi-dev-tool");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 检查 Claude Desktop 的 MCP 是否已配置
     */
    private boolean checkClaudeDesktopMcpConfigured(String configPath) {
        try {
            Path configFile = Paths.get(configPath);
            if (!Files.exists(configFile)) {
                return false;
            }
            String content = Files.readString(configFile);
            return content.contains("hisi-dev-tool");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 获取 Claude Code CLI 的 settings.json 路径
     */
    private String getClaudeCodeSettingsPath() {
        String userHome = System.getProperty("user.home");
        String os = System.getProperty("os.name").toLowerCase();

        if (os.contains("win")) {
            return userHome + "\\.claude\\settings.json";
        } else {
            return userHome + "/.claude/settings.json";
        }
    }

    /**
     * 获取 Claude Desktop 配置路径（已废弃，保留兼容）
     */
    private String getClaudeDesktopConfigPath() {
        String userHome = System.getProperty("user.home");
        String os = System.getProperty("os.name").toLowerCase();

        if (os.contains("win")) {
            return System.getenv("APPDATA") + "\\Claude\\claude_desktop_config.json";
        } else if (os.contains("mac")) {
            return userHome + "/Library/Application Support/Claude/claude_desktop_config.json";
        } else {
            return userHome + "/.config/claude/claude_desktop_config.json";
        }
    }

    // === 安装逻辑（强制完整安装模式）===

    private void installMcpAsync(SseEmitter emitter, String mcpDir, String projectDir) throws IOException {
        Path mcpPath;

        if (mcpDir != null && !mcpDir.isEmpty()) {
            mcpPath = Paths.get(mcpDir);
        } else {
            mcpPath = determineMcpPath();
        }

        if (mcpPath == null || !Files.exists(mcpPath)) {
            sendError(emitter, "MCP 目录不存在，请先下载并解压 MCP 包");
            return;
        }

        // 确定目标项目目录
        Path targetProjectPath;
        if (projectDir != null && !projectDir.isEmpty()) {
            targetProjectPath = Paths.get(projectDir);
        } else {
            targetProjectPath = Paths.get(System.getProperty("user.dir"));
        }

        sendProgress(emitter, "info", "开始安装 MCP（强制完整安装模式）...");
        sendProgress(emitter, "info", "MCP 项目目录: " + mcpPath);
        sendProgress(emitter, "info", "目标项目目录: " + targetProjectPath);

        // 0. 先卸载已有的 MCP 配置（确保干净安装）
        sendProgress(emitter, "step", "0/5 卸载旧版本");
        uninstallMcp(emitter, targetProjectPath);

        // 1. 检查 Node.js
        sendProgress(emitter, "step", "1/5 检查环境");
        if (!checkNodeInstalled()) {
            sendError(emitter, "未找到 Node.js，请先安装 Node.js 18+");
            return;
        }
        sendProgress(emitter, "success", "Node.js 环境检查通过");

        // 2. 强制重新安装依赖
        sendProgress(emitter, "step", "2/5 安装依赖");
        sendProgress(emitter, "info", "正在安装 npm 依赖，请稍候...");
        if (!runCommand(emitter, mcpPath, "npm install --registry=https://registry.npmmirror.com", "依赖安装")) {
            return;
        }

        // 3. 强制重新构建
        sendProgress(emitter, "step", "3/5 构建 MCP Server");
        sendProgress(emitter, "info", "正在构建...");
        if (!runCommand(emitter, mcpPath, "npm run build", "构建")) {
            return;
        }

        // 4. 强制重新安装 Skills
        sendProgress(emitter, "step", "4/5 安装 Skills");
        forceInstallSkills(emitter, mcpPath);

        // 5. 配置 Claude（强制重新配置）
        sendProgress(emitter, "step", "5/5 配置 Claude");
        forceConfigureClaude(emitter, mcpPath, targetProjectPath);

        sendProgress(emitter, "done", "安装完成！");
        emitter.complete();
    }

    /**
     * 卸载已有的 MCP 配置
     */
    private void uninstallMcp(SseEmitter emitter, Path targetProjectPath) {
        boolean claudeCodeInstalled = checkClaudeCodeInstalled();

        if (claudeCodeInstalled) {
            try {
                String os = System.getProperty("os.name").toLowerCase();
                ProcessBuilder removePb;

                if (os.contains("win")) {
                    removePb = new ProcessBuilder("cmd", "/c", "claude mcp remove hisi-dev-tool");
                } else {
                    removePb = new ProcessBuilder("sh", "-c", "claude mcp remove hisi-dev-tool");
                }

                removePb.directory(targetProjectPath.toFile());
                removePb.redirectErrorStream(true);
                Process process = removePb.start();
                process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);

                sendProgress(emitter, "info", "已卸载旧版本 MCP 配置");
            } catch (Exception e) {
                sendProgress(emitter, "warning", "卸载旧版本时出错（可能未安装）: " + e.getMessage());
            }
        } else {
            // Claude Desktop: 删除配置文件中的 MCP 配置
            String configPath = getClaudeDesktopConfigPath();
            try {
                Path configFile = Paths.get(configPath);
                if (Files.exists(configFile)) {
                    String content = Files.readString(configFile);
                    if (content.contains("hisi-dev-tool")) {
                        // 简单处理：删除整个配置文件，下次启动会重新生成
                        Files.delete(configFile);
                        sendProgress(emitter, "info", "已删除旧的 Claude Desktop MCP 配置");
                    }
                }
            } catch (Exception e) {
                sendProgress(emitter, "warning", "清理 Claude Desktop 配置时出错: " + e.getMessage());
            }
        }
    }

    /**
     * 强制安装 Skills（覆盖已存在的文件）
     */
    private void forceInstallSkills(SseEmitter emitter, Path mcpPath) {
        String userHome = System.getProperty("user.home");
        Path skillsDir = Paths.get(userHome, ".claude", "skills");

        try {
            Files.createDirectories(skillsDir);
            Path sourceSkills = mcpPath.resolve("skills");

            if (Files.exists(sourceSkills)) {
                final long[] copiedCount = {0};
                Files.walk(sourceSkills)
                    .filter(p -> !Files.isDirectory(p))
                    .forEach(p -> {
                        try {
                            Path relative = sourceSkills.relativize(p);
                            Path target = skillsDir.resolve(relative.toString());
                            Files.createDirectories(target.getParent());
                            Files.copy(p, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            copiedCount[0]++;
                        } catch (IOException e) {
                            // ignore
                        }
                    });
                sendProgress(emitter, "success", "Skills 安装成功: 复制了 " + copiedCount[0] + " 个文件到 " + skillsDir);
            } else {
                sendProgress(emitter, "warning", "未找到 skills 目录，跳过");
            }
        } catch (Exception e) {
            sendProgress(emitter, "warning", "Skills 安装失败: " + e.getMessage());
        }
    }

    /**
     * 强制配置 Claude（删除旧配置后重新配置）
     */
    private void forceConfigureClaude(SseEmitter emitter, Path mcpPath, Path targetProjectPath) {
        boolean claudeCodeInstalled = checkClaudeCodeInstalled();

        String mcpJsPath = mcpPath.resolve("dist/index.js").toString();
        mcpJsPath = mcpJsPath.replace("\\", "/");

        if (claudeCodeInstalled) {
            forceConfigureClaudeCodeCli(emitter, mcpJsPath, targetProjectPath);
        } else {
            forceConfigureClaudeDesktop(emitter, mcpJsPath, targetProjectPath);
        }
    }

    /**
     * 强制配置 Claude Code CLI
     */
    private void forceConfigureClaudeCodeCli(SseEmitter emitter, String mcpJsPath, Path targetProjectPath) {
        try {
            sendProgress(emitter, "info", "配置 Claude Code CLI MCP...");
            sendProgress(emitter, "warning", "注意：Claude Code CLI 的 MCP 配置是按项目的");

            String targetDir = targetProjectPath.toString();
            String projectDirEnv = targetProjectPath.toString().replace("\\", "/");
            sendProgress(emitter, "info", "目标项目目录: " + targetDir);

            String os = System.getProperty("os.name").toLowerCase();

            // 添加 MCP 服务器（如果已存在会报错，但不影响）
            String addCommand = String.format(
                "claude mcp add hisi-dev-tool -e HISI_API_URL=http://localhost:8080 -e HISI_PROJECT_DIR=%s -- node %s",
                projectDirEnv,
                mcpJsPath
            );

            sendProgress(emitter, "info", "执行: " + addCommand);

            ProcessBuilder addPb;
            if (os.contains("win")) {
                addPb = new ProcessBuilder("cmd", "/c", addCommand);
            } else {
                addPb = new ProcessBuilder("sh", "-c", addCommand);
            }
            addPb.directory(targetProjectPath.toFile());
            addPb.redirectErrorStream(true);

            Process addProcess = addPb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(addProcess.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                    sendProgress(emitter, "log", line);
                }
            }

            int exitCode = addProcess.waitFor();
            if (exitCode == 0) {
                sendProgress(emitter, "success", "Claude Code CLI MCP 配置成功！");
                sendProgress(emitter, "info", "配置已添加到项目: " + targetDir);
            } else {
                // 可能是已存在，尝试更新
                sendProgress(emitter, "warning", "配置返回非零退出码: " + exitCode);
                sendProgress(emitter, "info", "如果提示已存在，MCP 配置仍然有效");
            }
        } catch (Exception e) {
            sendProgress(emitter, "error", "配置失败: " + e.getMessage());
        }
    }

    /**
     * 强制配置 Claude Desktop
     */
    private void forceConfigureClaudeDesktop(SseEmitter emitter, String mcpJsPath, Path targetProjectPath) {
        String configPath = getClaudeDesktopConfigPath();
        Path configFile = Paths.get(configPath);

        try {
            Files.createDirectories(configFile.getParent());

            String projectDir = targetProjectPath != null ? targetProjectPath.toString().replace("\\", "/") : "";
            String config = String.format(
                "{\n" +
                "  \"mcpServers\": {\n" +
                "    \"hisi-dev-tool\": {\n" +
                "      \"command\": \"node\",\n" +
                "      \"args\": [\"%s\"],\n" +
                "      \"env\": {\n" +
                "        \"HISI_API_URL\": \"http://localhost:8080\"\n" +
                "      }\n" +
                "    }\n" +
                "  }\n" +
                "}", mcpJsPath);

            Files.writeString(configFile, config);
            sendProgress(emitter, "success", "Claude Desktop 配置文件已创建: " + configPath);
            sendProgress(emitter, "info", "请重启 Claude Desktop 使配置生效");
        } catch (Exception e) {
            sendProgress(emitter, "error", "配置失败: " + e.getMessage());
        }
    }

    private boolean checkNodeInstalled() {
        try {
            Process process = new ProcessBuilder("node", "-v").redirectErrorStream(true).start();
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean runCommand(SseEmitter emitter, Path workDir, String command, String stepName) {
        try {
            sendProgress(emitter, "info", "执行: " + command);

            ProcessBuilder pb;
            String os = System.getProperty("os.name").toLowerCase();

            if (os.contains("win")) {
                pb = new ProcessBuilder("cmd", "/c", command);
            } else {
                pb = new ProcessBuilder("sh", "-c", command);
            }

            pb.directory(workDir.toFile());
            pb.redirectErrorStream(true);

            Process process = pb.start();

            // 读取输出
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // 过滤掉过于详细的日志
                    if (!line.contains("npm WARN") && !line.contains("npm notice")) {
                        sendProgress(emitter, "log", line);
                    }
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                sendError(emitter, stepName + " 失败，退出码: " + exitCode);
                return false;
            }

            sendProgress(emitter, "success", stepName + " 成功");
            return true;
        } catch (Exception e) {
            sendError(emitter, stepName + " 失败: " + e.getMessage());
            return false;
        }
    }

    private void installSkills(SseEmitter emitter, Path mcpPath) {
        String userHome = System.getProperty("user.home");
        Path skillsDir = Paths.get(userHome, ".claude", "skills");

        try {
            Files.createDirectories(skillsDir);
            Path sourceSkills = mcpPath.resolve("skills");

            if (Files.exists(sourceSkills)) {
                // 检查目标目录是否已有 skills 文件
                long existingCount = 0;
                if (Files.exists(skillsDir)) {
                    existingCount = Files.walk(skillsDir)
                        .filter(p -> !Files.isDirectory(p) && p.getFileName().toString().endsWith(".md"))
                        .count();
                }

                if (existingCount > 0) {
                    sendProgress(emitter, "success", "Skills 目录已有 " + existingCount + " 个文件，跳过复制");
                } else {
                    final long[] copiedCount = {0};
                    Files.walk(sourceSkills)
                        .filter(p -> !Files.isDirectory(p))
                        .forEach(p -> {
                            try {
                                Path relative = sourceSkills.relativize(p);
                                Path target = skillsDir.resolve(relative.toString());
                                Files.createDirectories(target.getParent());
                                Files.copy(p, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                                copiedCount[0]++;
                            } catch (IOException e) {
                                // ignore
                            }
                        });
                    sendProgress(emitter, "success", "Skills 安装成功: 复制了 " + copiedCount[0] + " 个文件到 " + skillsDir);
                }
            } else {
                sendProgress(emitter, "warning", "未找到 skills 目录，跳过");
            }
        } catch (Exception e) {
            sendProgress(emitter, "warning", "Skills 安装失败: " + e.getMessage());
        }
    }

    private void configureClaude(SseEmitter emitter, Path mcpPath, Path targetProjectPath) {
        // 检测使用哪种配置方式
        boolean claudeCodeInstalled = checkClaudeCodeInstalled();

        String mcpJsPath = mcpPath.resolve("dist/index.js").toString();
        // Windows 路径转正斜杠（claude mcp add 命令需要）
        mcpJsPath = mcpJsPath.replace("\\", "/");

        if (claudeCodeInstalled) {
            // 使用 Claude Code CLI 配置
            configureClaudeCodeCli(emitter, mcpJsPath, targetProjectPath);
        } else {
            // 使用 Claude Desktop 配置文件
            configureClaudeDesktop(emitter, mcpJsPath, targetProjectPath);
        }
    }

    /**
     * 使用 Claude Code CLI 配置 MCP
     * 注意：Claude Code CLI 的 MCP 是按项目配置的，需要在目标项目目录下执行
     * @param targetProjectPath 目标项目目录，MCP 将配置到这个项目
     */
    private void configureClaudeCodeCli(SseEmitter emitter, String mcpJsPath, Path targetProjectPath) {
        try {
            sendProgress(emitter, "info", "检测到 Claude Code CLI，正在配置...");
            sendProgress(emitter, "warning", "注意：Claude Code CLI 的 MCP 配置是按项目的");

            String targetDir = targetProjectPath.toString();
            sendProgress(emitter, "info", "目标项目目录: " + targetDir);

            // 先删除已存在的配置（如果有）
            ProcessBuilder removePb;
            String os = System.getProperty("os.name").toLowerCase();

            if (os.contains("win")) {
                removePb = new ProcessBuilder("cmd", "/c", "claude mcp remove hisi-dev-tool");
            } else {
                removePb = new ProcessBuilder("sh", "-c", "claude mcp remove hisi-dev-tool");
            }
            removePb.directory(targetProjectPath.toFile());
            removePb.redirectErrorStream(true);
            Process removeProcess = removePb.start();
            removeProcess.waitFor(); // 忽略删除结果

            // 添加 MCP 服务器
            String addCommand = String.format(
                "claude mcp add hisi-dev-tool -e HISI_API_URL=http://localhost:8080 -e HISI_PROJECT_DIR=%s -- node %s",
                targetProjectPath.toString().replace("\\", "/"),
                mcpJsPath
            );

            sendProgress(emitter, "info", "执行: " + addCommand);

            ProcessBuilder addPb;
            if (os.contains("win")) {
                addPb = new ProcessBuilder("cmd", "/c", addCommand);
            } else {
                addPb = new ProcessBuilder("sh", "-c", addCommand);
            }
            addPb.directory(targetProjectPath.toFile());
            addPb.redirectErrorStream(true);

            Process addProcess = addPb.start();

            // 读取输出
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(addProcess.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                    sendProgress(emitter, "log", line);
                }
            }

            int exitCode = addProcess.waitFor();
            if (exitCode == 0) {
                sendProgress(emitter, "success", "Claude Code CLI MCP 配置成功！");
                sendProgress(emitter, "info", "配置已添加到项目: " + targetDir);
                sendProgress(emitter, "info", "验证命令: 在项目目录下运行 'claude mcp list'");
                sendProgress(emitter, "warning", "重要：MCP 配置是按项目的，需要在每个项目目录下单独配置");
            } else {
                sendProgress(emitter, "warning", "配置失败，退出码: " + exitCode + "，请手动执行: " + addCommand);
            }
        } catch (Exception e) {
            sendProgress(emitter, "warning", "Claude Code CLI 配置失败: " + e.getMessage());
            sendProgress(emitter, "info", "请手动执行命令配置 MCP");
        }
    }

    /**
     * 使用 Claude Desktop 配置文件（兼容旧版本）
     */
    private void configureClaudeDesktop(SseEmitter emitter, String mcpJsPath, Path targetProjectPath) {
        String configPath = getClaudeDesktopConfigPath();
        Path configFile = Paths.get(configPath);

        try {
            // 检查是否已配置
            if (checkClaudeDesktopMcpConfigured(configPath)) {
                sendProgress(emitter, "success", "Claude Desktop MCP 已配置，跳过配置步骤");
                return;
            }

            Files.createDirectories(configFile.getParent());

            String projectDir = targetProjectPath != null ? targetProjectPath.toString().replace("\\", "/") : "";
            String config = String.format(
                "{\n" +
                "  \"mcpServers\": {\n" +
                "    \"hisi-dev-tool\": {\n" +
                "      \"command\": \"node\",\n" +
                "      \"args\": [\"%s\"],\n" +
                "      \"env\": {\n" +
                "        \"HISI_API_URL\": \"http://localhost:8080\"\n" +
                "      }\n" +
                "    }\n" +
                "  }\n" +
                "}", mcpJsPath);

            Files.writeString(configFile, config);
            sendProgress(emitter, "success", "Claude Desktop 配置文件已创建: " + configPath);
            sendProgress(emitter, "info", "请重启 Claude Desktop 使配置生效");
        } catch (Exception e) {
            sendProgress(emitter, "warning", "配置 Claude Desktop 失败: " + e.getMessage() + "，请手动配置");
        }
    }

    private void sendProgress(SseEmitter emitter, String type, String message) {
        try {
            emitter.send(SseEmitter.event().name(type).data(message));
        } catch (IOException ignored) {}
    }

    private void sendError(SseEmitter emitter, String message) {
        try {
            emitter.send(SseEmitter.event().name("error").data(message));
            emitter.complete();
        } catch (IOException ignored) {}
    }

    /**
     * 获取 Claude Desktop 配置模板
     */
    @GetMapping("/config-template")
    public Map<String, Object> getConfigTemplate() {
        Map<String, Object> config = new LinkedHashMap<>();
        Map<String, Object> mcpServers = new LinkedHashMap<>();

        Map<String, Object> hisiConfig = new LinkedHashMap<>();
        hisiConfig.put("command", "node");
        hisiConfig.put("args", Arrays.asList("C:/Users/你的用户名/projects/hisi-dev-tool-mcp/dist/index.js"));

        Map<String, String> env = new LinkedHashMap<>();
        env.put("HISI_API_URL", "http://localhost:8080");
        hisiConfig.put("env", env);

        mcpServers.put("hisi-dev-tool", hisiConfig);
        config.put("mcpServers", mcpServers);

        return config;
    }

    /**
     * 获取安装脚本
     */
    @GetMapping("/install-script")
    public Map<String, String> getInstallScript() {
        Map<String, String> scripts = new HashMap<>();
        scripts.put("windows", createWindowsInstallScript());
        scripts.put("unix", createUnixInstallScript());
        return scripts;
    }

    // === Private Helper Methods ===

    private Path determineMcpPath() {
        // 优先使用配置的路径
        if (mcpProjectPath != null && !mcpProjectPath.isEmpty()) {
            return Paths.get(mcpProjectPath);
        }

        // 尝试常见路径
        String userHome = System.getProperty("user.home");
        String[] possiblePaths = {
            userHome + "/projects/hisi-dev-tool-mcp",
            "C:/Users/" + System.getProperty("user.name") + "/projects/hisi-dev-tool-mcp",
            "../hisi-dev-tool-mcp",
            "./hisi-dev-tool-mcp"
        };

        for (String path : possiblePaths) {
            Path p = Paths.get(path);
            if (Files.exists(p) && Files.exists(p.resolve("package.json"))) {
                return p;
            }
        }

        return null;
    }

    private void addToZip(ZipOutputStream zipOut, Path basePath, String relativePath) throws IOException {
        Path filePath = basePath.resolve(relativePath);
        if (Files.exists(filePath)) {
            zipOut.putNextEntry(new ZipEntry(relativePath));
            Files.copy(filePath, zipOut);
            zipOut.closeEntry();
        }
    }

    private void addDirectoryToZip(ZipOutputStream zipOut, Path basePath, String dirName) throws IOException {
        Path dirPath = basePath.resolve(dirName);
        if (!Files.exists(dirPath)) return;

        Files.walk(dirPath)
            .filter(path -> !Files.isDirectory(path))
            .forEach(path -> {
                try {
                    String relativePath = dirName + "/" + dirPath.relativize(path).toString().replace("\\", "/");
                    zipOut.putNextEntry(new ZipEntry(relativePath));
                    Files.copy(path, zipOut);
                    zipOut.closeEntry();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
    }

    private void addInstallScript(ZipOutputStream zipOut, String filename, String content) throws IOException {
        zipOut.putNextEntry(new ZipEntry(filename));
        zipOut.write(content.getBytes("UTF-8"));
        zipOut.closeEntry();
    }

    private void addConfigTemplate(ZipOutputStream zipOut) throws IOException {
        String content = "{\n" +
            "  \"mcpServers\": {\n" +
            "    \"hisi-dev-tool\": {\n" +
            "      \"command\": \"node\",\n" +
            "      \"args\": [\"C:/Users/你的用户名/projects/hisi-mcp-server/dist/index.js\"],\n" +
            "      \"env\": {\n" +
            "        \"HISI_API_URL\": \"http://localhost:8080\"\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}";
        zipOut.putNextEntry(new ZipEntry("claude_desktop_config.json.template"));
        zipOut.write(content.getBytes("UTF-8"));
        zipOut.closeEntry();
    }

    private String createWindowsInstallScript() {
        return "@echo off\n" +
            "echo === HiSi DevTool MCP 安装脚本 ===\n" +
            "echo.\n" +
            "\n" +
            "REM 检查 Node.js\n" +
            "where node >nul 2>&1\n" +
            "if %errorlevel% neq 0 (\n" +
            "    echo [错误] 未找到 Node.js，请先安装 Node.js 18+\n" +
            "    pause\n" +
            "    exit /b 1\n" +
            ")\n" +
            "echo [OK] Node.js 已安装\n" +
            "\n" +
            "REM 安装依赖\n" +
            "echo.\n" +
            "echo 正在安装依赖...\n" +
            "call npm install\n" +
            "if %errorlevel% neq 0 (\n" +
            "    echo [错误] 安装依赖失败\n" +
            "    pause\n" +
            "    exit /b 1\n" +
            ")\n" +
            "echo [OK] 依赖安装完成\n" +
            "\n" +
            "REM 构建\n" +
            "echo.\n" +
            "echo 正在构建...\n" +
            "call npm run build\n" +
            "if %errorlevel% neq 0 (\n" +
            "    echo [错误] 构建失败\n" +
            "    pause\n" +
            "    exit /b 1\n" +
            ")\n" +
            "echo [OK] 构建完成\n" +
            "\n" +
            "REM 安装 Skills\n" +
            "echo.\n" +
            "echo 正在安装 Skills...\n" +
            "call install-skills.bat\n" +
            "echo [OK] Skills 安装完成\n" +
            "\n" +
            "echo.\n" +
            "echo === 安装完成 ===\n" +
            "echo 请按照文档配置 Claude Desktop\n" +
            "pause\n";
    }

    private String createUnixInstallScript() {
        return "#!/bin/bash\n" +
            "echo \"=== HiSi DevTool MCP 安装脚本 ===\"\n" +
            "echo\n" +
            "\n" +
            "# 检查 Node.js\n" +
            "if ! command -v node &> /dev/null; then\n" +
            "    echo \"[错误] 未找到 Node.js，请先安装 Node.js 18+\"\n" +
            "    exit 1\n" +
            "fi\n" +
            "echo \"[OK] Node.js 已安装\"\n" +
            "\n" +
            "# 安装依赖\n" +
            "echo\n" +
            "echo \"正在安装依赖...\"\n" +
            "npm install || { echo \"[错误] 安装依赖失败\"; exit 1; }\n" +
            "echo \"[OK] 依赖安装完成\"\n" +
            "\n" +
            "# 构建\n" +
            "echo\n" +
            "echo \"正在构建...\"\n" +
            "npm run build || { echo \"[错误] 构建失败\"; exit 1; }\n" +
            "echo \"[OK] 构建完成\"\n" +
            "\n" +
            "# 安装 Skills\n" +
            "echo\n" +
            "echo \"正在安装 Skills...\"\n" +
            "chmod +x install-skills.sh\n" +
            "./install-skills.sh\n" +
            "echo \"[OK] Skills 安装完成\"\n" +
            "\n" +
            "echo\n" +
            "echo \"=== 安装完成 ===\"\n" +
            "echo \"请按照文档配置 Claude Desktop\"\n";
    }

    private Map<String, Object> createToolInfo(String name, String description, List<String> params) {
        Map<String, Object> tool = new HashMap<>();
        tool.put("name", name);
        tool.put("description", description);
        tool.put("params", params);
        return tool;
    }

    private Map<String, Object> createSkillInfo(String name, String title, String trigger) {
        Map<String, Object> skill = new HashMap<>();
        skill.put("name", name);
        skill.put("title", title);
        skill.put("trigger", trigger);
        return skill;
    }
}