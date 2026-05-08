package com.huawei.hisi.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.model.PromptTemplate;
import com.huawei.hisi.repository.PromptRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 提示词服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromptServiceImpl implements PromptService {

    private final PromptRepository promptRepository;
    private final ObjectMapper objectMapper;

    /** 变量格式: #{变量名} */
    private static final Pattern VARIABLE_PATTERN = Pattern.compile("#\\{([a-zA-Z_][a-zA-Z0-9_]*)\\}");

    @Override
    public List<PromptTemplate> getAllTemplates() {
        return promptRepository.findAllTemplates();
    }

    @Override
    public PromptTemplate getTemplate(String key) {
        return promptRepository.findTemplateByKey(key).orElse(null);
    }

    @Override
    public void updateTemplate(String key, String content, String variables) {
        promptRepository.updateTemplateContent(key, content, variables);
    }

    @Override
    public String render(String key, Map<String, String> variables) {
        PromptTemplate template = getTemplate(key);
        if (template == null) {
            log.warn("模板不存在: {}", key);
            return "";
        }
        return renderContent(template.getContent(), variables);
    }

    @Override
    public List<String> extractVariables(String content) {
        List<String> variables = new ArrayList<>();
        if (content == null) {
            return variables;
        }

        Matcher matcher = VARIABLE_PATTERN.matcher(content);
        while (matcher.find()) {
            String varName = matcher.group(1);
            if (!variables.contains(varName)) {
                variables.add(varName);
            }
        }
        return variables;
    }

    @Override
    public String renderContent(String content, Map<String, String> variables) {
        if (content == null || variables == null) {
            return content != null ? content : "";
        }

        String result = content;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            String placeholder = "#{" + entry.getKey() + "}";
            String value = entry.getValue() != null ? entry.getValue() : "";
            result = result.replace(placeholder, value);
        }

        // 处理未替换的变量（保持原样或替换为空）
        // 这里保持原样，让用户知道哪些变量没有被替换
        return result;
    }

    /**
     * 将变量列表转换为JSON字符串
     */
    public String variablesToJson(List<String> variables) {
        try {
            return objectMapper.writeValueAsString(variables);
        } catch (JsonProcessingException e) {
            log.error("变量列表转JSON失败: {}", e.getMessage());
            return "[]";
        }
    }

    /**
     * 从JSON字符串解析变量列表
     */
    @SuppressWarnings("unchecked")
    public List<String> jsonToVariables(String json) {
        if (json == null || json.isEmpty()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, List.class);
        } catch (JsonProcessingException e) {
            log.error("JSON解析变量列表失败: {}", e.getMessage());
            return List.of();
        }
    }
}
