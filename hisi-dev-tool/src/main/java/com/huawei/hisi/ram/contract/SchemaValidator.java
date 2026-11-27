package com.huawei.hisi.ram.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Error;
import com.networknt.schema.ExecutionContext;
import com.networknt.schema.Result;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.dialect.Dialects;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Validates RAM 4-stage contract payloads against JSON Schema (draft-07)
 * files under {@code classpath:schemas/ram/{name}.json}.
 *
 * <p>Validation failures do NOT throw — they are returned as a {@link ValidationResult}.
 * Only an unknown schema name or schema load error throws {@link IllegalArgumentException}.</p>
 *
 * <p>注意：这里用的是 json-schema-validator 2.x API（1.x 的 {@code JsonSchemaFactory} 在 2.0 已移除；
 * 升级原因：Spring AI MCP SDK 依赖 2.0，与本项目旧版 1.5 冲突）。</p>
 */
@Component
public class SchemaValidator {

    private static final String SCHEMA_PATH_TEMPLATE = "schemas/ram/%s.json";

    /**
     * Resolve a logical schema name to a classpath resource path.
     * Names containing '/' are treated as directory-qualified (e.g. {@code "arch/root-cause-analysis"}
     * → {@code schemas/arch/root-cause-analysis.json}); bare names keep the legacy
     * {@code schemas/ram/} default for backward compatibility.
     */
    private static String resolveResourcePath(String schemaName) {
        if (schemaName.contains("/")) {
            return "schemas/" + schemaName + ".json";
        }
        return String.format(SCHEMA_PATH_TEMPLATE, schemaName);
    }

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SchemaRegistry registry = SchemaRegistry.withDefaultDialect(Dialects.getDraft7());
    private final Map<String, Schema> schemaCache = new ConcurrentHashMap<>();

    /**
     * Validate a payload against the named schema.
     *
     * @param schemaName logical schema name, e.g. {@code "clarify.output"}
     * @param payload    payload to validate, typically a {@code Map<String,Object>}
     *                   deserialized from JSON
     * @return non-null {@link ValidationResult}; never throws on validation failure
     * @throws IllegalArgumentException if the schema does not exist on the classpath
     */
    public ValidationResult validate(String schemaName, Map<String, Object> payload) {
        Schema schema = schemaCache.computeIfAbsent(schemaName, this::loadSchema);

        JsonNode node = objectMapper.valueToTree(payload == null ? Map.of() : payload);
        // ExecutionContext 承载本次校验的错误收集，每次调用新建（跨线程共享会串数据）
        ExecutionContext executionContext = new ExecutionContext();
        schema.validate(executionContext, node);
        List<Error> errors = new Result(executionContext).getErrors();

        List<String> missingFields = new ArrayList<>();
        List<String> violations = new ArrayList<>();
        for (Error error : errors) {
            if ("required".equalsIgnoreCase(error.getMessageKey())) {
                String field = extractMissingField(error);
                missingFields.add(field != null ? field : error.getMessage());
            } else {
                violations.add(error.getMessage());
            }
        }
        boolean passed = missingFields.isEmpty() && violations.isEmpty();
        return new ValidationResult(passed, missingFields, violations);
    }

    private Schema loadSchema(String schemaName) {
        String resourcePath = resolveResourcePath(schemaName);
        // 显式用本类自己的类加载器：不带的话走「当前线程的上下文类加载器」，而 DAG 节点跑在 ForkJoinPool.commonPool 的
        // 工作线程上——在 Spring Boot 可执行 jar 里，这些线程的上下文类加载器看不到 BOOT-INF/classes，schema 会时有时无
        ClassPathResource resource = new ClassPathResource(resourcePath, SchemaValidator.class.getClassLoader());
        if (!resource.exists()) {
            throw new IllegalArgumentException(
                    "Unknown RAM schema: " + schemaName + " (looked for " + resourcePath + ")");
        }
        try (InputStream in = resource.getInputStream()) {
            return registry.getSchema(in);
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "Failed to load RAM schema: " + schemaName, e);
        }
    }

    /**
     * networknt 的 "required" 消息形如：{@code required property 'project_paths' not found}。
     * 优先取错误参数，其次从消息文本的单引号里取字段名。
     */
    private String extractMissingField(Error error) {
        Object[] args = error.getArguments();
        if (args != null && args.length > 0 && args[0] != null) {
            String first = String.valueOf(args[0]);
            if (!first.isBlank()) {
                return first;
            }
        }
        String text = error.getMessage();
        if (text == null) {
            return null;
        }
        int start = text.indexOf('\'');
        int end = start >= 0 ? text.indexOf('\'', start + 1) : -1;
        if (start >= 0 && end > start) {
            return text.substring(start + 1, end);
        }
        return text;
    }
}
