package com.huawei.hisi.piagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.Environment;

import java.nio.file.Files;
import java.nio.file.Path;

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
    @DisplayName("包版本与锁定版本一致、CLI 入口在 → 已安装")
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
