package com.huawei.hisi.knowledgegraph.codegraph;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * codegraph sidecar 服务
 *
 * <p>以子进程方式启动 codegraph CLI（Node 实现）对目标项目执行
 * {@code init -v <projectPath>}，等待其完成后捕获 stdout/stderr/exitCode。
 * 产出的 SQLite 数据库位于 {@code <projectPath>/.codegraph/codegraph.db}，
 * 后续由 {@code CodegraphSqliteReader} 读取并转换到 Neo4j。</p>
 *
 * <p><b>依赖前提</b>：宿主机 {@code node} 可执行文件需在 PATH 中可用
 * （不在本服务内 hard-code 节点路径，避免不同环境 Node 安装位置差异）。
 * codegraph dist 目录通过 {@code codegraph.dist-path} 配置，默认
 * {@code lib/codegraph}（相对路径以 Spring Boot 工作目录为基准解析）。</p>
 *
 * <p><b>进程模型</b>：参考 {@code GitStatusService#executeGitCommand}：
 * ProcessBuilder + {@code redirectErrorStream(true)} 合并 stderr 到 stdout，
 * 用 BufferedReader 持续读取避免缓冲死锁，{@code waitFor(timeout, SECONDS)}
 * 控制最长执行时间，超时则 {@code destroyForcibly()} 并抛出 IOException。</p>
 */
@Service
@Slf4j
public class CodegraphSidecarService {

    /** codegraph CLI 入口脚本（相对 dist 目录） */
    private static final String CLI_ENTRY = "bin/codegraph.js";

    /** 关闭 codegraph 遥测上报的环境变量 */
    private static final String ENV_TELEMETRY_KEY = "CODEGRAPH_TELEMETRY";
    private static final String ENV_TELEMETRY_OFF = "0";

    /** 产出数据库相对项目根的路径 */
    private static final String OUTPUT_DB_RELATIVE = ".codegraph/codegraph.db";

    private final String distPath;
    private final long timeoutSeconds;

    public CodegraphSidecarService(
            @Value("${codegraph.dist-path:lib/codegraph}") String distPath,
            @Value("${codegraph.timeout-seconds:600}") long timeoutSeconds) {
        this.distPath = distPath;
        this.timeoutSeconds = timeoutSeconds;
    }

    /**
     * 对目标项目执行 codegraph init（含索引）。
     *
     * @param projectPath 待分析项目根目录
     * @return exitCode、stdout 合并输出、产出 db 路径
     * @throws IllegalArgumentException 项目路径为空或目录不存在
     * @throws IOException              CLI 入口缺失、进程超时或非零退出
     */
    public CodegraphRunResult run(String projectPath) throws IOException {
        if (projectPath == null || projectPath.isBlank()) {
            throw new IllegalArgumentException("projectPath 不能为空");
        }
        File projectDir = new File(projectPath);
        if (!projectDir.isDirectory()) {
            throw new IllegalArgumentException("项目目录不存在: " + projectPath);
        }

        File codegraphJs = resolveCodegraphEntry();
        // init 命令同时初始化并索引（-i 已废弃，索引默认开启）
        String[] command = {"node", codegraphJs.getAbsolutePath(), "init", "-v", projectPath};
        log.info("codegraph 执行: {}", String.join(" ", command));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        pb.environment().put(ENV_TELEMETRY_KEY, ENV_TELEMETRY_OFF);

        Process process = pb.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }

        boolean finished;
        try {
            finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("codegraph 等待被中断: " + projectPath, e);
        }
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("codegraph 执行超时（>" + timeoutSeconds + "s）: " + projectPath);
        }
        int exitCode = process.exitValue();
        String stdout = output.toString();
        String outputDbPath = new File(projectDir, OUTPUT_DB_RELATIVE).getAbsolutePath();

        if (exitCode != 0) {
            String tail = stdout.length() > 500
                    ? stdout.substring(stdout.length() - 500) : stdout;
            log.error("codegraph 执行失败 exitCode={} 输出尾部={}", exitCode, tail);
            throw new IOException("codegraph 执行失败 exitCode=" + exitCode + " 输出=" + tail);
        }

        log.info("codegraph 执行成功，产出 db: {}", outputDbPath);
        return new CodegraphRunResult(exitCode, stdout, outputDbPath);
    }

    /**
     * 解析 codegraph CLI 入口脚本路径并校验存在性。
     * distPath 为相对路径时以 Spring Boot 工作目录为基准解析。
     */
    private File resolveCodegraphEntry() throws IOException {
        File distDir = new File(distPath);
        File entry = new File(distDir, CLI_ENTRY);
        if (!Files.isRegularFile(entry.toPath())) {
            throw new IOException("codegraph CLI 入口不存在: " + entry.getAbsolutePath()
                    + "，请检查 codegraph.dist-path 配置");
        }
        return entry;
    }

    /**
     * codegraph 执行结果。
     *
     * @param exitCode       进程退出码（成功为 0）
     * @param stdout         stdout+stderr 合并输出
     * @param outputDbPath   产出 SQLite 数据库绝对路径
     */
    public record CodegraphRunResult(int exitCode, String stdout, String outputDbPath) {
    }
}
