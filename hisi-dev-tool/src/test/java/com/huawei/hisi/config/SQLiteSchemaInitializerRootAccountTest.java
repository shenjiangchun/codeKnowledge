package com.huawei.hisi.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 管理员账号的初始口令不再写死在代码里（仓库公开，固定口令等于后门）：
 * 配置了就用配置的，没配置就随机生成，并把生成的那一个打印在启动日志里供首次登录。
 */
@DisplayName("SQLiteSchemaInitializer root account seeding")
class SQLiteSchemaInitializerRootAccountTest {

    private static final Pattern LOGGED = Pattern.compile("random password: (\\S+)");

    private final PasswordEncoder encoder = new BCryptPasswordEncoder();

    @Test
    @DisplayName("配置了初始口令：root 账号用它登录")
    void aConfiguredPasswordSeedsTheRootAccount() throws Exception {
        String hash = seed("configured-root-pw");

        assertThat(hash).isNotNull();
        assertThat(encoder.matches("configured-root-pw", hash)).isTrue();
    }

    @Test
    @DisplayName("没配置：生成随机口令，启动日志里打印的那一个能登录")
    void withoutConfigurationARandomPasswordIsGeneratedAndLogged() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(SQLiteSchemaInitializer.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            String hash = seed("  ");

            assertThat(encoder.matches("", hash)).isFalse();
            String logged = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .map(LOGGED::matcher)
                    .filter(Matcher::find)
                    .map(m -> m.group(1))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("启动日志里没有打印随机初始口令：" + appender.list));
            // 随机口令是 16 位 url-safe 字符，不再是过去写死的那个短数字串
            assertThat(logged).matches("[A-Za-z0-9_-]{16}");
            assertThat(encoder.matches(logged, hash)).as("日志里打印的口令应能登录").isTrue();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private String seed(String rootPassword) throws Exception {
        Path dbFile = Files.createTempFile("hisi-root-seed-", ".db");
        Files.deleteIfExists(dbFile);
        JdbcTemplate jdbc = new JdbcTemplate(
                new SingleConnectionDataSource("jdbc:sqlite:" + dbFile.toAbsolutePath(), true));
        new SQLiteSchemaInitializer(jdbc, encoder, rootPassword).initialize();
        return jdbc.queryForObject("SELECT password FROM sys_user WHERE username = 'root'", String.class);
    }
}
