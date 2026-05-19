package com.huawei.hisi.apm.service;

import com.huawei.hisi.apm.config.ApmConfig;
import com.huawei.hisi.apm.model.TargetProcessInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Manages the lifecycle of target JVM processes launched with the
 * OpenTelemetry Java agent attached.
 *
 * <p>Each process is keyed by a session ID and tracked in a thread-safe
 * {@link ConcurrentHashMap}. The manager handles:
 * <ul>
 *   <li>Build-system detection (Maven / Gradle / JAR fallback)</li>
 *   <li>OS-aware command construction (Windows / Unix)</li>
 *   <li>OTel agent environment variable injection</li>
 *   <li>Stdout capture into a bounded ring buffer</li>
 *   <li>Port-based readiness polling</li>
 *   <li>Graceful + forced shutdown</li>
 * </ul>
 */
@Service
@Slf4j
public class TargetProcessManager {

    private static final int OUTPUT_BUFFER_MAX_LINES = 500;
    private static final int AUTO_PORT_START = 18080;
    private static final int READINESS_POLL_INTERVAL_MS = 500;
    private static final int PORT_CONNECT_TIMEOUT_MS = 500;

    private final ApmConfig apmConfig;
    private final OtelAgentManager otelAgentManager;
    private final int serverPort;

    private final ConcurrentHashMap<String, ManagedProcess> processes = new ConcurrentHashMap<>();

    public TargetProcessManager(ApmConfig apmConfig,
                                OtelAgentManager otelAgentManager,
                                @Value("${server.port:8080}") int serverPort) {
        this.apmConfig = apmConfig;
        this.otelAgentManager = otelAgentManager;
        this.serverPort = serverPort;
    }

    // ---------------------------------------------------------------
    //  Inner class: ManagedProcess
    // ---------------------------------------------------------------

    /**
     * Holds all state for a single managed target process.
     */
    private static class ManagedProcess {
        private final Process process;
        private final String sessionId;
        private final int port;
        private volatile String status;
        private final LinkedList<String> outputBuffer = new LinkedList<>();
        private final Thread readerThread;
        private final Thread readinessThread;

        ManagedProcess(Process process, String sessionId, int port,
                       String status, Thread readerThread, Thread readinessThread) {
            this.process = process;
            this.sessionId = sessionId;
            this.port = port;
            this.status = status;
            this.readerThread = readerThread;
            this.readinessThread = readinessThread;
        }

        synchronized void addOutputLine(String line) {
            outputBuffer.addLast(line);
            while (outputBuffer.size() > OUTPUT_BUFFER_MAX_LINES) {
                outputBuffer.removeFirst();
            }
        }

        synchronized List<String> getOutputLines(int maxLines) {
            int fromIndex = Math.max(0, outputBuffer.size() - maxLines);
            return List.copyOf(outputBuffer.subList(fromIndex, outputBuffer.size()));
        }
    }

    // ---------------------------------------------------------------
    //  Public API
    // ---------------------------------------------------------------

    /**
     * Launch a target process with the OTel agent attached.
     *
     * @param sessionId   unique session identifier
     * @param projectPath absolute path to the project root
     * @param serviceName OTel service name
     * @param targetPort  port for the target application (0 = auto-assign)
     * @param callback    receives status change notifications
     * @return process info snapshot after launch
     * @throws IOException if agent resolution or process start fails
     */
    public TargetProcessInfo launch(String sessionId,
                                    String projectPath,
                                    String serviceName,
                                    int targetPort,
                                    Consumer<TargetProcessInfo> callback) throws IOException {

        if (processes.containsKey(sessionId)) {
            throw new IllegalStateException("Session already has a running process: " + sessionId);
        }

        // 1. Resolve OTel agent
        String agentPath = otelAgentManager.ensureAgentAvailable();

        // 2. Resolve port
        int resolvedPort = targetPort > 0 ? targetPort : allocatePort();

        // 3. Build command
        List<String> command = buildCommand(projectPath);
        log.info("[TargetProcess] Session {} command: {}", sessionId, command);

        // 4. Configure process builder
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(new File(projectPath));
        pb.redirectErrorStream(true);

        Map<String, String> env = pb.environment();
        env.put("JAVA_TOOL_OPTIONS", "-javaagent:" + agentPath);
        env.put("OTEL_SERVICE_NAME", serviceName);
        env.put("OTEL_EXPORTER_OTLP_PROTOCOL", "http/json");
        env.put("OTEL_EXPORTER_OTLP_ENDPOINT", "http://localhost:" + serverPort);
        env.put("OTEL_TRACES_EXPORTER", "otlp");
        env.put("OTEL_METRICS_EXPORTER", "none");
        env.put("OTEL_LOGS_EXPORTER", "none");
        env.put("SERVER_PORT", String.valueOf(resolvedPort));

        // 5. Start process
        Process process = pb.start();
        long pid = process.pid();
        log.info("[TargetProcess] Session {} started PID {} on port {}", sessionId, pid, resolvedPort);

        // 6. Create managed process (threads added below)
        ManagedProcess mp = new ManagedProcess(process, sessionId, resolvedPort,
                "LAUNCHING", null, null);

        // We need to create threads that reference mp, so we use a holder pattern
        // and set them after construction via reflection-free approach:
        // Actually, we build them here and store the full ManagedProcess with threads.
        Thread readerThread = createReaderThread(sessionId, process, mp, callback, projectPath, serviceName);
        Thread readinessThread = createReadinessThread(sessionId, resolvedPort, mp, callback, projectPath, serviceName, pid);

        // Replace with properly threaded version
        ManagedProcess fullMp = new ManagedProcess(process, sessionId, resolvedPort,
                "LAUNCHING", readerThread, readinessThread);
        // Copy any output already captured
        processes.put(sessionId, fullMp);

        // Notify callback
        TargetProcessInfo info = buildInfo(sessionId, projectPath, serviceName, resolvedPort, pid, "LAUNCHING");
        notifyCallback(callback, info);

        // 7. Start daemon threads
        readerThread.start();
        readinessThread.start();

        return info;
    }

    /**
     * Gracefully shut down a target process, falling back to forced kill
     * if the process does not exit within the configured grace period.
     */
    public void shutdown(String sessionId) {
        ManagedProcess mp = processes.get(sessionId);
        if (mp == null) {
            return;
        }

        log.info("[TargetProcess] Shutting down session {}", sessionId);
        mp.status = "STOPPED";
        mp.process.destroy();

        try {
            boolean exited = mp.process.waitFor(
                    apmConfig.getTargetShutdownGraceSeconds(), TimeUnit.SECONDS);
            if (!exited) {
                log.warn("[TargetProcess] Session {} did not exit gracefully, forcing", sessionId);
                mp.process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            mp.process.destroyForcibly();
            Thread.currentThread().interrupt();
        }

        processes.remove(sessionId);
        log.info("[TargetProcess] Session {} removed", sessionId);
    }

    /**
     * Check whether the process for a session is still alive.
     */
    public boolean isRunning(String sessionId) {
        ManagedProcess mp = processes.get(sessionId);
        return mp != null && mp.process.isAlive();
    }

    /**
     * Return the last N lines from the process stdout ring buffer.
     *
     * @param sessionId session to query
     * @param maxLines  maximum number of lines to return
     * @return list of output lines (most recent last), empty if session not found
     */
    public List<String> getOutputLines(String sessionId, int maxLines) {
        ManagedProcess mp = processes.get(sessionId);
        if (mp == null) {
            return Collections.emptyList();
        }
        return mp.getOutputLines(maxLines);
    }

    /**
     * Return a snapshot of process info for a session.
     */
    public TargetProcessInfo getProcessInfo(String sessionId) {
        ManagedProcess mp = processes.get(sessionId);
        if (mp == null) {
            return null;
        }
        long pid = mp.process.isAlive() ? mp.process.pid() : -1;
        return TargetProcessInfo.builder()
                .sessionId(sessionId)
                .targetPort(mp.port)
                .pid(pid)
                .status(mp.status)
                .build();
    }

    /**
     * Shut down all managed processes on application shutdown.
     */
    @PreDestroy
    public void shutdownAll() {
        log.info("[TargetProcess] Shutting down all {} managed processes", processes.size());
        List<String> sessionIds = new ArrayList<>(processes.keySet());
        for (String sessionId : sessionIds) {
            shutdown(sessionId);
        }
    }

    // ---------------------------------------------------------------
    //  Build command detection
    // ---------------------------------------------------------------

    private List<String> buildCommand(String projectPath) {
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        File projectDir = new File(projectPath);

        // Maven
        if (new File(projectDir, "pom.xml").exists()) {
            return buildMavenCommand(projectDir, isWindows);
        }

        // Gradle
        if (new File(projectDir, "build.gradle").exists()
                || new File(projectDir, "build.gradle.kts").exists()) {
            return buildGradleCommand(projectDir, isWindows);
        }

        // Fallback: look for executable JAR
        return buildJarCommand(projectDir, isWindows);
    }

    private List<String> buildMavenCommand(File projectDir, boolean isWindows) {
        if (isWindows) {
            File mvnw = new File(projectDir, "mvnw.cmd");
            String mvn = mvnw.exists() ? mvnw.getAbsolutePath() : "mvn";
            return List.of("cmd", "/c", mvn, "spring-boot:run");
        }
        File mvnw = new File(projectDir, "mvnw");
        String mvn = mvnw.exists() ? "./mvnw" : "mvn";
        return List.of("sh", "-c", mvn + " spring-boot:run");
    }

    private List<String> buildGradleCommand(File projectDir, boolean isWindows) {
        if (isWindows) {
            File gradlew = new File(projectDir, "gradlew.bat");
            String gradle = gradlew.exists() ? gradlew.getAbsolutePath() : "gradle";
            return List.of("cmd", "/c", gradle, "bootRun");
        }
        File gradlew = new File(projectDir, "gradlew");
        String gradle = gradlew.exists() ? "./gradlew" : "gradle";
        return List.of("sh", "-c", gradle + " bootRun");
    }

    private List<String> buildJarCommand(File projectDir, boolean isWindows) {
        File jar = findJar(projectDir);
        if (jar == null) {
            throw new IllegalStateException(
                    "Cannot detect build system for: " + projectDir.getAbsolutePath()
                            + " — no pom.xml, build.gradle, or executable JAR found");
        }
        String jarPath = jar.getAbsolutePath();
        if (isWindows) {
            return List.of("cmd", "/c", "java", "-jar", jarPath);
        }
        return List.of("sh", "-c", "java -jar " + jarPath);
    }

    /**
     * Search for an executable JAR in common build output directories.
     */
    private File findJar(File projectDir) {
        // Check Maven target/
        File targetDir = new File(projectDir, "target");
        File jar = findFirstJar(targetDir);
        if (jar != null) {
            return jar;
        }

        // Check Gradle build/libs/
        File libsDir = new File(projectDir, "build/libs");
        return findFirstJar(libsDir);
    }

    private File findFirstJar(File dir) {
        if (!dir.isDirectory()) {
            return null;
        }
        File[] jars = dir.listFiles((d, name) ->
                name.endsWith(".jar") && !name.endsWith("-sources.jar") && !name.endsWith("-javadoc.jar"));
        if (jars != null && jars.length > 0) {
            return jars[0];
        }
        return null;
    }

    // ---------------------------------------------------------------
    //  Port allocation
    // ---------------------------------------------------------------

    private int allocatePort() {
        // Try deterministic ports starting from AUTO_PORT_START
        for (int port = AUTO_PORT_START; port < AUTO_PORT_START + 100; port++) {
            if (!isPortInUse(port)) {
                return port;
            }
        }

        // Fallback to OS-assigned random port
        try (ServerSocket ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot allocate a free port", e);
        }
    }

    private boolean isPortInUse(int port) {
        try (ServerSocket ss = new ServerSocket(port)) {
            ss.setReuseAddress(true);
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    // ---------------------------------------------------------------
    //  Readiness detection
    // ---------------------------------------------------------------

    private boolean isPortOpen(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", port), PORT_CONNECT_TIMEOUT_MS);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    // ---------------------------------------------------------------
    //  Daemon threads
    // ---------------------------------------------------------------

    private Thread createReaderThread(String sessionId, Process process,
                                      ManagedProcess mp,
                                      Consumer<TargetProcessInfo> callback,
                                      String projectPath, String serviceName) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // Write to the actual ManagedProcess stored in the map
                    ManagedProcess current = processes.get(sessionId);
                    if (current != null) {
                        current.addOutputLine(line);
                    }
                }
            } catch (IOException e) {
                log.debug("[TargetProcess] Reader for session {} ended: {}", sessionId, e.getMessage());
            }

            // Process has exited
            ManagedProcess current = processes.get(sessionId);
            if (current != null && !"STOPPED".equals(current.status)) {
                int exitCode = process.exitValue();
                String finalStatus = exitCode == 0 ? "STOPPED" : "ERROR";
                current.status = finalStatus;
                long pid = -1;
                try {
                    pid = process.pid();
                } catch (UnsupportedOperationException ignored) {
                    // pid not available after exit on some platforms
                }
                notifyCallback(callback,
                        buildInfo(sessionId, projectPath, serviceName, current.port, pid, finalStatus));
                log.info("[TargetProcess] Session {} process exited with code {}", sessionId, exitCode);
            }
        }, "apm-reader-" + sessionId);
        thread.setDaemon(true);
        return thread;
    }

    private Thread createReadinessThread(String sessionId, int port,
                                         ManagedProcess mp,
                                         Consumer<TargetProcessInfo> callback,
                                         String projectPath, String serviceName,
                                         long pid) {
        int timeoutSeconds = apmConfig.getTargetReadyTimeoutSeconds();
        Thread thread = new Thread(() -> {
            long deadline = System.currentTimeMillis() + (timeoutSeconds * 1000L);
            while (System.currentTimeMillis() < deadline) {
                // Check if process is still alive
                ManagedProcess current = processes.get(sessionId);
                if (current == null || !current.process.isAlive()) {
                    log.warn("[TargetProcess] Session {} process died before becoming ready", sessionId);
                    return;
                }

                if (isPortOpen(port)) {
                    current.status = "READY";
                    notifyCallback(callback,
                            buildInfo(sessionId, projectPath, serviceName, port, pid, "READY"));
                    log.info("[TargetProcess] Session {} is ready on port {}", sessionId, port);
                    return;
                }

                try {
                    Thread.sleep(READINESS_POLL_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }

            // Timeout reached
            ManagedProcess current = processes.get(sessionId);
            if (current != null && "LAUNCHING".equals(current.status)) {
                current.status = "ERROR";
                notifyCallback(callback,
                        buildInfo(sessionId, projectPath, serviceName, port, pid, "ERROR"));
                log.warn("[TargetProcess] Session {} readiness timeout after {}s", sessionId, timeoutSeconds);
            }
        }, "apm-readiness-" + sessionId);
        thread.setDaemon(true);
        return thread;
    }

    // ---------------------------------------------------------------
    //  Helpers
    // ---------------------------------------------------------------

    private TargetProcessInfo buildInfo(String sessionId, String projectPath,
                                        String serviceName, int port,
                                        long pid, String status) {
        return TargetProcessInfo.builder()
                .sessionId(sessionId)
                .projectPath(projectPath)
                .serviceName(serviceName)
                .targetPort(port)
                .pid(pid)
                .status(status)
                .build();
    }

    private void notifyCallback(Consumer<TargetProcessInfo> callback, TargetProcessInfo info) {
        if (callback == null) {
            return;
        }
        try {
            callback.accept(info);
        } catch (Exception e) {
            log.warn("[TargetProcess] Status callback failed for session {}: {}",
                    info.getSessionId(), e.getMessage());
        }
    }
}
