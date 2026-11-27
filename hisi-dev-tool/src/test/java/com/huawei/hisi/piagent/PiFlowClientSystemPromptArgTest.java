package com.huawei.hisi.piagent;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回归：技术方案节点的 pi 会话启动后约 1.7 秒退出，后端回退成「LLM不可用」占位输出。
 *
 * <p>根因：Windows 上 {@link ProcessBuilder} 把 argv 拼成命令行时，对含双引号的参数转义不完整。
 * 系统提示词正文里的 {@code "} 会打乱参数边界，把其中一段泄漏成独立参数——
 * 真机报 {@code Unknown option: -->|是|}、exitValue=1。是否触发取决于双引号出现的位置，
 * 所以别的节点「碰巧」能跑，改一个字就可能炸。
 *
 * <p>修复：提示词写进会话目录的文件，argv 只传路径（{@code --system-prompt <text|path>}，语义同为替换默认提示词）。
 */
@DisplayName("PiFlowClient：系统提示词不进命令行")
class PiFlowClientSystemPromptArgTest {

    /** 含双引号、反斜杠、中文、换行——真机上让 pi 崩掉的那类内容。 */
    private static final String PROMPT_WITH_QUOTES = String.join("\n",
            "你是技术方案助手。只输出纯 JSON。",
            "  \"mermaid\": \"flowchart TD\\n  A[有子项?] -->|是| B[看子项卷积]...\"",
            "字段 \"reasoning\" 用中文。");

    @Test
    @DisplayName("提示词写进会话目录的文件，返回路径；文件内容与原文逐字一致")
    void promptIsWrittenToAFileAndThePathIsReturned(@TempDir Path sessionDir) throws Exception {
        String arg = PiFlowClient.systemPromptArg(sessionDir, PROMPT_WITH_QUOTES);

        Path file = Path.of(arg);
        assertThat(file).exists().isRegularFile();
        assertThat(file.getParent()).isEqualTo(sessionDir);
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo(PROMPT_WITH_QUOTES);
        // 路径里绝不能出现提示词正文（那就等于又塞回命令行了）
        assertThat(arg).doesNotContain("mermaid").doesNotContain("\"");
    }

    @Test
    @DisplayName("空提示词不产生文件，原样返回（不改变「不替换提示词」的既有行为）")
    void emptyPromptIsPassedThroughUntouched(@TempDir Path sessionDir) {
        assertThat(PiFlowClient.systemPromptArg(sessionDir, null)).isEmpty();
        assertThat(PiFlowClient.systemPromptArg(sessionDir, "")).isEmpty();
        assertThat(sessionDir.resolve("system-prompt.md")).doesNotExist();
    }

    @Test
    @DisplayName("端到端：同一份含双引号的提示词，直接当 argv 传会让 pi 报 Unknown option；走文件路径则正常启动")
    void endToEnd_piAcceptsThePromptViaFileButNotViaArgv(@TempDir Path sessionDir) throws Exception {
        Path cli = installedCli();
        // 本机没装 pi 时标记为「跳过」（报告里可见），不能悄悄 return 让它假装通过
        Assumptions.assumeTrue(cli != null, "本机未安装 pi，跳过端到端用例");

        String viaFile = PiFlowClient.systemPromptArg(sessionDir, PROMPT_WITH_QUOTES);
        String viaArgv = PROMPT_WITH_QUOTES;

        String stderrViaArgv = launchAndCaptureStderr(cli, sessionDir, "probe-argv", viaArgv);
        String stderrViaFile = launchAndCaptureStderr(cli, sessionDir, "probe-file", viaFile);

        // 对照组：必须复现真机故障，否则这条端到端用例没有证明力
        assertThat(stderrViaArgv)
                .as("直接把含双引号的提示词放进 argv，应复现 Unknown option")
                .contains("Unknown option");
        // 修复组：不得再出现 Unknown option
        assertThat(stderrViaFile)
                .as("提示词走文件路径后，pi 不应再把它解析成选项")
                .doesNotContain("Unknown option");
    }

    /**
     * 本机已安装的 pi CLI 入口；没装返回 null。
     * 可用系统属性 {@code piagent.install-dir} 或环境变量 {@code PIAGENT_INSTALL_DIR} 指向安装目录
     * （测试跑在隔离副本里时那里没有 lib/pi）。不能用 -DargLine 传：项目用 JaCoCo，argLine 由它注入。
     */
    private static Path installedCli() {
        String base = System.getProperty("piagent.install-dir");
        if (base == null || base.isBlank()) {
            base = System.getenv("PIAGENT_INSTALL_DIR");
        }
        Path root = base != null && !base.isBlank() ? Path.of(base) : Path.of("lib", "pi").toAbsolutePath();
        Path cli = root.resolve("node_modules").resolve("@earendil-works").resolve("pi-coding-agent")
                .resolve("dist").resolve("bundle").resolve("cli.js");
        return Files.isRegularFile(cli) ? cli : null;
    }

    /** 用与 PiProcess 相同的方式（ProcessBuilder + argv 列表）启动 pi，不发送 prompt，只收集 stderr。 */
    private static String launchAndCaptureStderr(Path cli, Path sessionDir, String tag, String systemPromptArg)
            throws Exception {
        Path dir = sessionDir.resolve(tag);
        Files.createDirectories(dir);
        List<String> cmd = List.of("node", cli.toString(),
                "--mode", "rpc",
                "--session-id", "probe-" + tag,
                "--session-dir", dir.toString(),
                "--system-prompt", systemPromptArg,
                "--no-approve", "--no-context-files");
        ProcessBuilder pb = new ProcessBuilder(cmd);
        // 工作目录用 pi 的安装目录所在的后端根目录（…/lib/pi/node_modules/… 往上数到 lib 的父级），与真实运行一致
        Path workdir = cli;
        while (workdir != null && !"lib".equals(workdir.getFileName() == null ? "" : workdir.getFileName().toString())) {
            workdir = workdir.getParent();
        }
        pb.directory(workdir == null ? Path.of(".").toAbsolutePath().toFile() : workdir.getParent().toFile());
        pb.redirectErrorStream(false);
        Process p = pb.start();
        AtomicReference<String> stderr = new AtomicReference<>("");
        Thread reader = new Thread(() -> {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
            } catch (Exception ignored) { }
            stderr.set(sb.toString());
        });
        reader.setDaemon(true);
        reader.start();
        p.getOutputStream().close();
        boolean exited = p.waitFor(30, TimeUnit.SECONDS);
        if (!exited) {
            p.destroyForcibly();
        }
        reader.join(3000);
        return stderr.get();
    }
}
