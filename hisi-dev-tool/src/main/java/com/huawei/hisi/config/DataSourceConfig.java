package com.huawei.hisi.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import jakarta.annotation.PostConstruct;
import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Configuration
public class DataSourceConfig {

    @Value("${app.project_dir:}")
    public static String PROJECT_DIR;

    @Value("${app.project_dir:}")
    public void setProjectDir(String projectDir) {
        PROJECT_DIR = projectDir;
    }

    @Value("${spring.datasource.url:jdbc:sqlite:${user.home}/.hisi-devtool/devtool.db}")
    private String dbUrl;

    @PostConstruct
    public void ensureDirectory() throws IOException {
        // Extract file path from jdbc:sqlite: URL, stripping query params (?foreign_keys=on etc.)
        String path = dbUrl.replace("jdbc:sqlite:", "");
        int qIdx = path.indexOf('?');
        if (qIdx >= 0) path = path.substring(0, qIdx);
        if (!path.startsWith(":")) { // skip :memory:
            Path dbPath = Paths.get(path);
            Files.createDirectories(dbPath.getParent());
        }
    }

    @Bean
    public DataSource dataSource() {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl(dbUrl);
        return ds;
    }

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
