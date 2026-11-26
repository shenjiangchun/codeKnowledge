package com.huawei.hisi.piagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.Environment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 启动自检：pi 是否算“已安装”，以及没装上时会不会在流执行前补装。
 * 不真的跑 node/npm——安装本身由真机冷启动验证，这里锁死判据与补装的触发条件。
 */
@DisplayName("PiAgentInstaller 启动自检与补装")
class PiAgentInstallerTest {

    private static final String VERSION = "1.6.0";

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path dir;

    @Test
    @DisplayName("mcp.json 的服务条目只有启动命令：不写 env，更不能有环境变量引用")
    void mcpServerEntryHasOnlyCommandAndArgs() {
        Map<String, Object> entry = PiAgentInstaller.mcpServerEntry(Path.of("D:/x/hisi-mcp-server/dist/index.js"));

        assertThat(entry.keySet()).containsExactlyInAnyOrder("command", "args");
        assertThat(entry.toString()).as("pi 把空值当未设置且不支持默认值，只读流不带令牌时引用会让 MCP 连接失败")
                .doesNotContain("${");
    }

    @Test
    @DisplayName("版本一致且 CLI 入口在 → 已安装")
    void matchingVersionWithCliEntryIsInstalled() throws Exception {
        writePackageJson(VERSION);
        touch(cli());

        assertThat(PiAgentInstaller.isInstalled(packageJson(), cli(), VERSION, mapper)).isTrue();
    }

    @Test
    @DisplayName("有 package.json 但入口缺失（装到一半被打断）→ 不算已安装")
    void packageJsonWithoutCliEntryIsNotInstalled() throws Exception {
        writePackageJson(VERSION);

        assertThat(PiAgentInstaller.isInstalled(packageJson(), cli(), VERSION, mapper))
                .as("只看 package.json 会把半截安装当成已安装").isFalse();
    }

    @Test
    @DisplayName("入口在但版本与锁定版本不一致 → 不算已安装")
    void versionMismatchIsNotInstalled() throws Exception {
        writePackageJson("1.5.0");
        touch(cli());

        assertThat(PiAgentInstaller.isInstalled(packageJson(), cli(), VERSION, mapper)).isFalse();
    }

    @Test
    @DisplayName("什么都没装 → 不算已安装")
    void nothingInstalledIsNotInstalled() {
        assertThat(PiAgentInstaller.isInstalled(packageJson(), cli(), VERSION, mapper)).isFalse();
    }

    @Test
    @DisplayName("未就绪且总开关开启 → 流执行前重新触发安装")
    void notReadyTriggersInstallAgain() {
        PiAgentProperties properties = new PiAgentProperties();
        properties.setEnabled(true);
        PiAgentInstaller installer = spy(new PiAgentInstaller(properties, mock(Environment.class)));
        doNothing().when(installer).ensure();

        installer.ensureReady();

        verify(installer).ensure();
    }

    @Test
    @DisplayName("总开关关闭 → 不反复重试")
    void disabledNeverRetries() {
        PiAgentProperties properties = new PiAgentProperties();
        properties.setEnabled(false);
        PiAgentInstaller installer = spy(new PiAgentInstaller(properties, mock(Environment.class)));
        doNothing().when(installer).ensure();

        installer.ensureReady();

        verify(installer, never()).ensure();
    }

    private Path packageJson() {
        return dir.resolve("node_modules").resolve("@earendil-works").resolve("pi-coding-agent").resolve("package.json");
    }

    private Path cli() {
        return packageJson().resolveSibling("dist").resolve("bundle").resolve("cli.js");
    }

    private void writePackageJson(String version) throws Exception {
        Files.createDirectories(packageJson().getParent());
        Files.writeString(packageJson(), "{\"version\": \"" + version + "\"}");
    }

    private static void touch(Path file) throws Exception {
        Files.createDirectories(file.getParent());
        Files.createFile(file);
    }
}
