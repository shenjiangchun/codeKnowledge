package com.huawei.hisi.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.model.PromptTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import jakarta.annotation.PostConstruct;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * 提示词模板数据访问层 (SQLite)
 * Table created by SQLiteSchemaInitializer.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class PromptRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @PostConstruct
    public void init() {
        initDefaultTemplates();
    }

    private void initDefaultTemplates() {
        // 默认模板数据
        String[][] defaultTemplates = {
            {"log-analysis", "日志分析", buildLogAnalysisTemplate(), "[\"errorMessage\",\"errorType\",\"stackTrace\",\"projectPath\"]", "分析错误日志，定位问题根因"},
            {"code-analysis", "代码分析", buildCodeAnalysisTemplate(), "[\"codeSnippet\",\"language\",\"projectContext\"]", "分析代码质量和潜在问题"},
            {"trace-analysis", "调用链分析", buildTraceAnalysisTemplate(), "[\"traceId\",\"entryPoint\",\"callChain\"]", "分析分布式调用链路问题"},
            {"impact-analysis", "影响分析", buildImpactAnalysisTemplate(), "[\"changedFile\",\"changedMethod\",\"changeType\",\"projectName\"]", "分析代码变更影响范围"},
            {"free-chat", "自由对话", buildFreeChatTemplate(), "[]", "自由对话，不限场景"}
        };

        for (String[] template : defaultTemplates) {
            if (!checkTemplateExists(template[0])) {
                saveTemplateRaw(template[0], template[1], template[2], template[3], template[4]);
                log.info("初始化默认模板: {}", template[0]);
            }
        }
    }

    private boolean checkTemplateExists(String templateKey) {
        String sql = "SELECT COUNT(*) FROM prompt_template WHERE template_key = ?";
        try {
            Integer count = jdbcTemplate.queryForObject(sql, Integer.class, templateKey);
            return count != null && count > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void saveTemplateRaw(String key, String name, String content, String variables, String description) {
        String sql = """
            INSERT INTO prompt_template (template_key, name, content, variables, description, updated_at)
            VALUES (?, ?, ?, ?, ?, strftime('%s','now'))
            """;
        try {
            jdbcTemplate.update(sql, key, name, content, variables, description);
        } catch (Exception e) {
            log.error("保存模板失败: {}", e.getMessage());
        }
    }

    // ==================== CRUD ====================

    /**
     * 保存模板 (upsert)
     */
    public void saveTemplate(PromptTemplate template) {
        String sql = """
            INSERT INTO prompt_template (template_key, name, content, variables, description, updated_at)
            VALUES (?, ?, ?, ?, ?, strftime('%s','now'))
            ON CONFLICT(template_key) DO UPDATE SET
                name = excluded.name,
                content = excluded.content,
                variables = excluded.variables,
                description = excluded.description,
                updated_at = strftime('%s','now')
            """;

        try {
            jdbcTemplate.update(sql,
                template.getTemplateKey(),
                template.getName(),
                template.getContent(),
                template.getVariables(),
                template.getDescription()
            );
            log.debug("模板保存成功: {}", template.getTemplateKey());
        } catch (Exception e) {
            log.error("保存模板失败: {}", e.getMessage());
            throw new RuntimeException("保存模板失败: " + e.getMessage(), e);
        }
    }

    /**
     * 根据key查找模板
     */
    public Optional<PromptTemplate> findTemplateByKey(String key) {
        String sql = "SELECT * FROM prompt_template WHERE template_key = ?";
        try {
            List<PromptTemplate> results = jdbcTemplate.query(sql, new TemplateRowMapper(), key);
            return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
        } catch (Exception e) {
            log.error("查询模板失败: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 查询所有模板
     */
    public List<PromptTemplate> findAllTemplates() {
        String sql = "SELECT * FROM prompt_template ORDER BY template_key";
        try {
            return jdbcTemplate.query(sql, new TemplateRowMapper());
        } catch (Exception e) {
            log.error("查询模板列表失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 更新模板内容
     */
    public void updateTemplateContent(String key, String content, String variables) {
        String sql = "UPDATE prompt_template SET content = ?, variables = ?, updated_at = strftime('%s','now') WHERE template_key = ?";
        try {
            jdbcTemplate.update(sql, content, variables, key);
        } catch (Exception e) {
            log.error("更新模板内容失败: {}", e.getMessage());
        }
    }

    // ==================== Default Templates ====================

    private String buildLogAnalysisTemplate() {
        return """
你是一个专业的日志分析和代码诊断专家。

## 可用工具
- search_methods: 搜索项目中的方法定义
- find_callers: 查找调用某个方法的上游代码
- find_callees: 查找某个方法调用的下游代码
- list_classes: 列出项目中的类
- list_projects: 列出可用项目
- list_uris: 列出项目的 URI 端点
- query_error_logs: 查询错误日志
- analyze_interface: 分析接口定义

## 项目信息
#{projectPath}

## 错误摘要
```
#{errorMessage}
```

## 异常类型
#{errorType}

## 调用栈
```
#{stackTrace}
```

## 分析步骤
1. 定位错误源：从堆栈信息中找到第一个项目代码位置
2. 分析根本原因：检查错误位置的代码逻辑
3. 评估影响范围：找出所有受影响的调用方
4. 提供修复建议：给出具体的代码修改方案

请输出：
- 错误类型
- 根本原因
- 受影响的代码
- 修复建议
- 影响范围
""";
    }

    private String buildCodeAnalysisTemplate() {
        return """
你是一个专业的代码分析专家。

## 项目上下文
#{projectContext}

## 编程语言
#{language}

## 代码片段
```
#{codeSnippet}
```

请分析这段代码：
1. 代码质量和最佳实践
2. 潜在问题和风险
3. 性能优化建议
4. 安全隐患检查
5. 改进建议
""";
    }

    private String buildTraceAnalysisTemplate() {
        return """
你是一个专业的分布式系统调用链分析专家。

## Trace ID
#{traceId}

## 入口点
#{entryPoint}

## 调用链
#{callChain}

请分析这个调用链：
1. 调用链路梳理
2. 性能瓶颈识别
3. 异常节点定位
4. 优化建议
""";
    }

    private String buildImpactAnalysisTemplate() {
        return """
你是一个专业的代码影响分析专家。

## 项目名称
#{projectName}

## 变更文件
#{changedFile}

## 变更方法
#{changedMethod}

## 变更类型
#{changeType}

请分析这个变更的影响：
1. 直接影响的模块和方法
2. 间接影响的调用链
3. 可能的兼容性问题
4. 测试覆盖建议
5. 部署风险评估
""";
    }

    private String buildFreeChatTemplate() {
        return """
你是一个专业的软件开发助手，可以帮助用户解决各种开发相关的问题。

请根据用户的问题提供专业、准确的回答。可以涉及：
- 代码编写和调试
- 架构设计和优化
- 技术选型建议
- 问题排查和解决
- 最佳实践指导

如果需要分析具体代码或项目，请使用可用的工具函数。
""";
    }

    // ==================== Row Mapper ====================

    private static class TemplateRowMapper implements RowMapper<PromptTemplate> {
        @Override
        public PromptTemplate mapRow(ResultSet rs, int rowNum) throws SQLException {
            PromptTemplate template = new PromptTemplate();
            template.setTemplateKey(rs.getString("template_key"));
            template.setName(rs.getString("name"));
            template.setContent(rs.getString("content"));
            template.setVariables(rs.getString("variables"));
            template.setDescription(rs.getString("description"));
            long updatedEpoch = rs.getLong("updated_at");
            template.setUpdatedAt(updatedEpoch > 0 ?
                LocalDateTime.ofInstant(Instant.ofEpochSecond(updatedEpoch), ZoneId.systemDefault()) : null);
            return template;
        }
    }
}
