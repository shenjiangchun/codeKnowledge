package com.huawei.hisi.piagent;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * pi 进程在启动阶段崩溃时，原因必须能在日志里看到。
 *
 * <p>以前 pi 的 stderr 被并入 stdout，而 stdout 上只认 JSON，崩溃信息被当成「无法解析的行」降成 DEBUG 吞掉，
 * 调用方只剩一句「管道已结束」（技术方案节点两次复现，无从定位）。
 * 这里用一个真实的子进程模拟：先往 stderr 写原因、再以非零码退出，不往 stdout 写任何东西。
 */
@DisplayName("PiProcess：子进程异常退出时能留下原因")
class PiProcessCrashDiagnosticsTest {

    @TempDir
    Path tmp;

    /** 一段极小的 Java 程序：stderr 写两行后以 rc=3 退出。用单文件源码模式运行，避免依赖平台 shell 语法。 */
    private List<String> crashingCommand(String... stderrLines) throws Exception {
        StringBuilder src = new StringBuilder();
        src.append("public class Crash {\n  public static void main(String[] a) throws Exception {\n");
        for (String line : stderrLines) {
            src.append("    System.err.println(\"").append(line.replace("\"", "\\\"")).append("\");\n");
        }
        src.append("    System.err.flush();\n    System.exit(3);\n  }\n}\n");
        Path file = tmp.resolve("Crash.java");
        Files.writeString(file, src);
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return List.of(javaBin, file.toString());
    }

    private static ListAppender<ILoggingEvent> captureLogs() {
        Logger logger = (Logger) LoggerFactory.getLogger(PiProcess.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    /** 只拆掉这个 appender，不动 logger 上的其它 appender。 */
    private static void releaseLogs(ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(PiProcess.class)).detachAppender(appender);
        appender.stop();
    }

    @Test
    @DisplayName("子进程写 stderr 后非零退出：退出码与 stderr 尾部出现在 WARN 日志里")
    void crashReasonIsLoggedWithExitCode() throws Exception {
        ListAppender<ILoggingEvent> logs = captureLogs();
        try {
            PiProcess pi = new PiProcess(crashingCommand("Error: something broke in pi startup", "at pi.loadConfig()"),
                    tmp, Map.of(), ev -> {});
            // 等子进程退出、读取线程把 stdout 关闭事件处理完
            pi.prompt("hi").handle((r, ex) -> null);
            assertThatThrownBy(() -> pi.prompt("hi").get(15, TimeUnit.SECONDS))
                    .as("子进程已退出，再往它的 stdin 写必须失败，而不是永远挂着")
                    .isInstanceOf(java.util.concurrent.ExecutionException.class);

            List<String> warnings = logs.list.stream()
                    .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
            assertThat(warnings).as("应有一条记录进程退出的 WARN").isNotEmpty();
            String all = String.join("\n", warnings);
            assertThat(all).contains("exitValue=3");
            assertThat(all).contains("something broke in pi startup");
            assertThat(all).contains("at pi.loadConfig()");
        } finally {
            releaseLogs(logs);
        }
    }

    @Test
    @DisplayName("子进程什么都没写就退出：日志明确说 stderr 无输出，而不是空白")
    void silentCrashSaysStderrWasEmpty() throws Exception {
        ListAppender<ILoggingEvent> logs = captureLogs();
        try {
            PiProcess pi = new PiProcess(crashingCommand(), tmp, Map.of(), ev -> {});
            assertThatThrownBy(() -> pi.prompt("hi").get(15, TimeUnit.SECONDS))
                    .isInstanceOf(java.util.concurrent.ExecutionException.class);

            List<String> warnings = logs.list.stream()
                    .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
            assertThat(String.join("\n", warnings)).contains("stderr 无输出").contains("exitValue=3");
        } finally {
            releaseLogs(logs);
        }
    }
}
