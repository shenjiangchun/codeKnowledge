package com.huawei.hisi.handler;

import lombok.extern.slf4j.Slf4j;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import com.pty4j.WinSize;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Terminal WebSocket Handler
 * Supports structured message protocol for start/resume/input/resize actions
 */
@Slf4j
@Component
public class TerminalWebSocketHandler extends TextWebSocketHandler {

    @Value("${claude.working-directory:${user.dir}}")
    private String defaultWorkingDirectory;

    @Value("${claude.claude-path:claude}")
    private String claudePath;

    private final Map<WebSocketSession, PtyProcess> ptyProcessMap = new ConcurrentHashMap<>();
    private final Map<WebSocketSession, String> extractedSessionIds = new ConcurrentHashMap<>();
    private final Map<WebSocketSession, Boolean> claudeReadySent = new ConcurrentHashMap<>();

    // Pattern to detect when Claude CLI is ready
    private static final Pattern CLAUDE_READY_PATTERN = Pattern.compile(
        "(Welcome to Claude|Claude Code|^[>]\\s*$|╭─+╮|╰─+╯|What would you like)",
        Pattern.CASE_INSENSITIVE | Pattern.MULTILINE
    );

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        super.afterConnectionEstablished(session);
        sendMessage(session, Map.of("type", "ready"));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();

        if (payload.trim().startsWith("{")) {
            try {
                JSONObject msg = JSON.parseObject(payload);
                String action = msg.getString("action");

                if (action == null) {
                    sendError(session, "Missing 'action' field in message");
                    return;
                }

                switch (action) {
                    case "start":
                        startClaudeProcess(session, null, msg.getString("workingDirectory"),
                                msg.getIntValue("cols"), msg.getIntValue("rows"), true,
                                msg.getString("initialPrompt"));
                        break;
                    case "resume":
                        startClaudeProcess(session, msg.getString("claudeSessionId"), msg.getString("workingDirectory"),
                                msg.getIntValue("cols"), msg.getIntValue("rows"), false,
                                msg.getString("initialPrompt"));
                        break;
                    case "continue":
                        continueRecentSession(session, msg.getString("workingDirectory"),
                                msg.getIntValue("cols"), msg.getIntValue("rows"),
                                msg.getString("initialPrompt"));
                        break;
                    case "input":
                        handleInput(session, msg.getString("data"));
                        break;
                    case "resize":
                        handleResize(session, msg.getIntValue("cols"), msg.getIntValue("rows"));
                        break;
                    case "ping":
                        sendMessage(session, Map.of("type", "pong"));
                        break;
                    default:
                        sendError(session, "Unknown action: " + action);
                }
            } catch (Exception e) {
                log.error("Failed to parse message: {}", e.getMessage());
                handleRawInput(session, payload);
            }
        } else {
            handleRawInput(session, payload);
        }
    }

    private void startClaudeProcess(WebSocketSession session, String claudeSessionId, String workingDirectory,
                                    int cols, int rows, boolean isNewSession, String initialPrompt) {
        if (ptyProcessMap.containsKey(session)) {
            sendError(session, "Process already running for this session");
            return;
        }

        int termCols = cols > 0 ? cols : 120;
        int termRows = rows > 0 ? rows : 30;

        try {
            String osName = System.getProperty("os.name").toLowerCase();
            String[] command = osName.contains("win") ? new String[]{"cmd.exe"} : new String[]{claudePath};

            String directory = (workingDirectory != null && !workingDirectory.isEmpty())
                ? workingDirectory : defaultWorkingDirectory;

            PtyProcessBuilder builder = new PtyProcessBuilder(command);
            builder.setDirectory(directory);
            Map<String, String> env = new HashMap<>(System.getenv());

            // 核心修复：设置终端类型和编码，支持 Claude CLI 完整 ANSI 转义码
            env.put("TERM", "xterm-256color");
            env.put("LANG", "en_US.UTF-8");
            env.put("LC_ALL", "en_US.UTF-8");

            // 修复 PATH 环境变量，确保 Node.js 可用
            if (osName.contains("win")) {
                enhancePathForWindows(env);
            }

            builder.setEnvironment(env);
            builder.setInitialColumns(termCols);
            builder.setInitialRows(termRows);

            // Windows 系统启用 ANSI 颜色支持和 ConPTY
            if (osName.contains("win")) {
                builder.setWindowsAnsiColorEnabled(true);
                builder.setUseWinConPty(true);
            }

            PtyProcess ptyProcess = builder.start();
            ptyProcessMap.put(session, ptyProcess);

            // Send command to start/resume Claude with optional initial prompt
            if (osName.contains("win")) {
                String claudeCommand;
                String promptArg = (initialPrompt != null && !initialPrompt.isEmpty())
                    ? " \"" + initialPrompt.replace("\"", "\\\"") + "\""
                    : "";

                if (isNewSession || claudeSessionId == null || claudeSessionId.isEmpty()) {
                    // New session: generate UUID and use --session-id
                    String newSessionId = java.util.UUID.randomUUID().toString();
                    extractedSessionIds.put(session, newSessionId);
                    claudeCommand = claudePath + " --session-id " + newSessionId + promptArg + "\r\n";
                    log.info("[Session] NEW session_id: {}, prompt: {}", newSessionId, initialPrompt != null ? "yes" : "no");
                    sendMessage(session, Map.of("type", "session_info", "claudeSessionId", newSessionId));
                } else {
                    // Resume existing session
                    claudeCommand = claudePath + " --resume " + claudeSessionId + promptArg + "\r\n";
                    log.info("[Session] RESUME session_id: {}, prompt: {}", claudeSessionId, initialPrompt != null ? "yes" : "no");
                }
                OutputStream os = ptyProcess.getOutputStream();
                os.write(claudeCommand.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            // Start output reader
            startOutputReader(session, ptyProcess);

        } catch (Exception e) {
            log.error("Failed to start Claude CLI: {}", e.getMessage());
            cleanupProcess(session);
            sendError(session, "Failed to start Claude CLI: " + e.getMessage());
            try { session.close(); } catch (IOException ignored) {}
        }
    }

    /**
     * 增强 Windows PATH 环境变量，确保 Node.js 可用
     */
    private void enhancePathForWindows(Map<String, String> env) {
        String currentPath = env.getOrDefault("PATH", "");
        StringBuilder pathBuilder = new StringBuilder();

        // 1. 先尝试通过 where 命令找到 node/npm
        try {
            String nodePath = findExecutablePath("node.exe");
            String npmPath = findExecutablePath("npm.cmd");
            String npxPath = findExecutablePath("npx.cmd");

            if (nodePath != null && !nodePath.isEmpty()) {
                String nodeDir = new java.io.File(nodePath).getParent();
                if (nodeDir != null && !currentPath.contains(nodeDir)) {
                    pathBuilder.append(nodeDir).append(";");
                    log.info("[Terminal] Added Node.js to PATH: {}", nodeDir);
                }
            }

            if (npmPath != null && !npmPath.isEmpty()) {
                String npmDir = new java.io.File(npmPath).getParent();
                if (npmDir != null && !currentPath.contains(npmDir)) {
                    pathBuilder.append(npmDir).append(";");
                    log.info("[Terminal] Added npm to PATH: {}", npmDir);
                }
            }

            if (npxPath != null && !npxPath.isEmpty()) {
                String npxDir = new java.io.File(npxPath).getParent();
                if (npxDir != null && !currentPath.contains(npxDir)) {
                    pathBuilder.append(npxDir).append(";");
                    log.info("[Terminal] Added npx to PATH: {}", npxDir);
                }
            }
        } catch (Exception e) {
            log.warn("[Terminal] Failed to find Node.js via where command: {}", e.getMessage());
        }

        // 2. 常见的 Node.js 安装路径
        String[] commonPaths = {
            "C:\\Program Files\\nodejs",
            "C:\\Program Files (x86)\\nodejs",
            System.getenv("APPDATA") + "\\npm",
            System.getProperty("user.home") + "\\AppData\\Roaming\\npm",
            System.getenv("LOCALAPPDATA") + "\\Programs\\Node.js",
            System.getenv("ProgramFiles") + "\\nodejs",
            System.getenv("ProgramFiles(x86)") + "\\nodejs"
        };

        for (String path : commonPaths) {
            if (path != null && !path.isEmpty() && !currentPath.contains(path)) {
                java.io.File dir = new java.io.File(path);
                if (dir.exists() && dir.isDirectory()) {
                    pathBuilder.append(path).append(";");
                    log.debug("[Terminal] Added common path to PATH: {}", path);
                }
            }
        }

        // 3. 从 claudePath 提取 npm 路径
        if (claudePath.contains("\\npm\\")) {
            String npmBasePath = claudePath.substring(0, claudePath.lastIndexOf("\\npm\\") + 5);
            if (!currentPath.contains(npmBasePath)) {
                pathBuilder.append(npmBasePath).append(";");
            }
        }

        // 4. 添加原有的 PATH
        pathBuilder.append(currentPath);

        String newPath = pathBuilder.toString();
        env.put("PATH", newPath);

        log.debug("[Terminal] Updated PATH length: {}", newPath.length());
    }

    /**
     * 通过 Windows where 命令查找可执行文件路径
     */
    private String findExecutablePath(String exeName) {
        try {
            ProcessBuilder pb = new ProcessBuilder("where.exe", exeName);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            String line = reader.readLine();
            process.waitFor();

            if (line != null && !line.isEmpty() && new java.io.File(line).exists()) {
                log.info("[Terminal] Found {} at: {}", exeName, line);
                return line;
            }
        } catch (Exception e) {
            log.debug("[Terminal] Failed to find {}: {}", exeName, e.getMessage());
        }
        return null;
    }

    private void continueRecentSession(WebSocketSession session, String workingDirectory, int cols, int rows, String initialPrompt) {
        if (ptyProcessMap.containsKey(session)) {
            sendError(session, "Process already running for this session");
            return;
        }

        try {
            String osName = System.getProperty("os.name").toLowerCase();
            String[] command = osName.contains("win") ? new String[]{"cmd.exe"} : new String[]{claudePath};
            String directory = (workingDirectory != null && !workingDirectory.isEmpty())
                ? workingDirectory : defaultWorkingDirectory;

            PtyProcessBuilder builder = new PtyProcessBuilder(command);
            builder.setDirectory(directory);
            Map<String, String> env = new HashMap<>(System.getenv());

            // 核心修复：设置终端类型和编码
            env.put("TERM", "xterm-256color");
            env.put("LANG", "en_US.UTF-8");
            env.put("LC_ALL", "en_US.UTF-8");

            // 修复 PATH 环境变量，确保 Node.js 可用
            if (osName.contains("win")) {
                enhancePathForWindows(env);
            }

            builder.setEnvironment(env);
            builder.setInitialColumns(cols > 0 ? cols : 120);
            builder.setInitialRows(rows > 0 ? rows : 30);

            // Windows 系统启用 ANSI 颜色支持和 ConPTY
            if (osName.contains("win")) {
                builder.setWindowsAnsiColorEnabled(true);
                builder.setUseWinConPty(true);
            }

            PtyProcess ptyProcess = builder.start();
            ptyProcessMap.put(session, ptyProcess);

            String promptArg = (initialPrompt != null && !initialPrompt.isEmpty())
                ? " \"" + initialPrompt.replace("\"", "\\\"") + "\""
                : "";
            log.info("[Session] CONTINUE recent session, prompt: {}", initialPrompt != null ? "yes" : "no");

            if (osName.contains("win")) {
                String claudeCommand = claudePath + " --continue" + promptArg + "\r\n";
                OutputStream os = ptyProcess.getOutputStream();
                os.write(claudeCommand.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            startOutputReader(session, ptyProcess);

        } catch (Exception e) {
            log.error("Failed to continue session: {}", e.getMessage());
            cleanupProcess(session);
            sendError(session, "Failed to continue session: " + e.getMessage());
        }
    }

    private void startOutputReader(WebSocketSession session, PtyProcess ptyProcess) {
        InputStream inputStream = ptyProcess.getInputStream();
        Thread outputThread = new Thread(() -> {
            byte[] buffer = new byte[4096];
            int len;
            try {
                while ((len = inputStream.read(buffer)) != -1) {
                    String output = new String(buffer, 0, len, StandardCharsets.UTF_8);
                    if (session.isOpen()) {
                        sendMessage(session, Map.of("type", "output", "data", output));
                        detectClaudeReady(session, output);
                    }
                }
            } catch (IOException e) {
                // Stream closed, normal when process exits
            } finally {
                PtyProcess proc = ptyProcessMap.get(session);
                if (proc != null && !proc.isAlive()) {
                    cleanupProcess(session);
                }
            }
        }, "pty-output-" + session.getId());
        outputThread.setDaemon(true);
        outputThread.start();
    }

    private void handleInput(WebSocketSession session, String data) throws IOException {
        PtyProcess process = ptyProcessMap.get(session);
        if (process == null) {
            sendError(session, "No active process for input");
            return;
        }
        try {
            OutputStream os = process.getOutputStream();
            os.write(data.getBytes(StandardCharsets.UTF_8));
            os.flush();
        } catch (IOException e) {
            sendError(session, "Terminal write failed");
        }
    }

    private void handleResize(WebSocketSession session, int cols, int rows) {
        PtyProcess pty = ptyProcessMap.get(session);
        if (pty != null) {
            try {
                pty.setWinSize(new WinSize(cols, rows));
            } catch (Exception ignored) {}
        }
    }

    private void handleRawInput(WebSocketSession session, String payload) throws IOException {
        PtyProcess ptyProcess = ptyProcessMap.get(session);
        if (ptyProcess != null && session.isOpen()) {
            try {
                OutputStream outputStream = ptyProcess.getOutputStream();
                outputStream.write(payload.getBytes(StandardCharsets.UTF_8));
                outputStream.flush();
            } catch (IOException e) {
                sendError(session, "Terminal write failed");
            }
        }
    }

    private void detectClaudeReady(WebSocketSession session, String output) {
        if (claudeReadySent.containsKey(session)) return;
        Matcher matcher = CLAUDE_READY_PATTERN.matcher(output);
        if (matcher.find()) {
            claudeReadySent.put(session, true);
            sendMessage(session, Map.of("type", "claude_ready"));
        }
    }

    private void sendMessage(WebSocketSession session, Map<String, Object> message) {
        if (session.isOpen()) {
            try {
                session.sendMessage(new TextMessage(JSON.toJSONString(message)));
            } catch (IOException e) {
                log.error("Failed to send message: {}", e.getMessage());
            }
        }
    }

    private void sendError(WebSocketSession session, String errorMessage) {
        sendMessage(session, Map.of("type", "error", "data", errorMessage));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        super.afterConnectionClosed(session, status);
        cleanupProcess(session);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        super.handleTransportError(session, exception);
        cleanupProcess(session);
    }

    private void cleanupProcess(WebSocketSession session) {
        PtyProcess ptyProcess = ptyProcessMap.remove(session);
        extractedSessionIds.remove(session);
        claudeReadySent.remove(session);
        if (ptyProcess != null) {
            try {
                ptyProcess.destroy();
            } catch (Exception ignored) {}
        }
    }
}