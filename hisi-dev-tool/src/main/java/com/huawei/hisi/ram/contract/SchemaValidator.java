package com.huawei.hisi.ram.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Validates RAM 4-stage contract payloads against JSON Schema (draft-07)
 * files under {@code classpath:schemas/ram/{name}.json}.
 *
 * <p>Validation failures do NOT throw — they are returned as a {@link ValidationResult}.
 * Only an unknown schema name or schema load error throws {@link IllegalArgumentException}.</p>
 */
@Component
public class SchemaValidator {

    private static final String SCHEMA_PATH_TEMPLATE = "schemas/ram/%s.json";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JsonSchemaFactory factory =
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
    private final Map<String, JsonSchema> schemaCache = new ConcurrentHashMap<>();

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
        JsonSchema schema = schemaCache.computeIfAbsent(schemaName, this::loadSchema);

        JsonNode node = objectMapper.valueToTree(payload == null ? Map.of() : payload);
        Set<ValidationMessage> messages = schema.validate(node);

        List<String> missingFields = new ArrayList<>();
        List<String> violations = new ArrayList<>();
        for (ValidationMessage msg : messages) {
            String type = msg.getType();
            if ("required".equalsIgnoreCase(type)) {
                String field = extractMissingField(msg);
                missingFields.add(field != null ? field : msg.getMessage());
            } else {
                violations.add(msg.getMessage());
            }
        }
        boolean passed = missingFields.isEmpty() && violations.isEmpty();
        return new ValidationResult(passed, missingFields, violations);
    }

    private JsonSchema loadSchema(String schemaName) {
        String resourcePath = String.format(SCHEMA_PATH_TEMPLATE, schemaName);
        ClassPathResource resource = new ClassPathResource(resourcePath);
        if (!resource.exists()) {
            throw new IllegalArgumentException(
                    "Unknown RAM schema: " + schemaName + " (looked for " + resourcePath + ")");
        }
        try (InputStream in = resource.getInputStream()) {
            return factory.getSchema(in);
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "Failed to load RAM schema: " + schemaName, e);
        }
    }

    /**
     * networknt's "required" message typically looks like:
     * "$.foo: required property 'project_paths' not found".
     * We extract the property name in single quotes when possible.
     */
    private String extractMissingField(ValidationMessage msg) {
        // Try arguments first — they often hold the missing property name directly.
        Object[] args = msg.getArguments();
        if (args != null && args.length > 0 && args[0] != null) {
            String first = String.valueOf(args[0]);
            if (!first.isBlank()) {
                return first;
            }
        }
        String text = msg.getMessage();
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
