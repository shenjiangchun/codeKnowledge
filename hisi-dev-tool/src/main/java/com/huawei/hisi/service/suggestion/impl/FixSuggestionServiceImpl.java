package com.huawei.hisi.service.suggestion.impl;

import com.huawei.hisi.service.suggestion.FixSuggestionService;
import com.huawei.hisi.service.suggestion.model.FixSuggestion;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 修复建议服务实现
 * 根据诊断结果生成可执行的修复建议
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FixSuggestionServiceImpl implements FixSuggestionService {

    private static final Map<String, FixSuggestion> STANDARD_TEMPLATES = new HashMap<>();

    static {
        // NullPointerException 修复模板
        STANDARD_TEMPLATES.put("NullPointerException", FixSuggestion.builder()
                .type(FixSuggestion.SuggestionType.CODE_FIX)
                .title("Null检查修复")
                .description("添加null检查以避免NullPointerException")
                .confidence(0.9)
                .priority(1)
                .steps(List.of(
                        FixSuggestion.FixStep.builder()
                                .stepNumber(1)
                                .description("在调用方法前检查对象是否为null")
                                .codeSnippet("if (object != null) {\n    object.method();\n}")
                                .build(),
                        FixSuggestion.FixStep.builder()
                                .stepNumber(2)
                                .description("或使用Optional包装")
                                .codeSnippet("Optional.ofNullable(object).ifPresent(Object::method);")
                                .build()
                ))
                .build());

        // ArrayIndexOutOfBoundsException 修复模板
        STANDARD_TEMPLATES.put("ArrayIndexOutOfBoundsException", FixSuggestion.builder()
                .type(FixSuggestion.SuggestionType.CODE_FIX)
                .title("数组边界检查")
                .description("添加数组边界检查")
                .confidence(0.95)
                .priority(1)
                .steps(List.of(
                        FixSuggestion.FixStep.builder()
                                .stepNumber(1)
                                .description("在访问数组前检查索引范围")
                                .codeSnippet("if (index >= 0 && index < array.length) {\n    array[index];\n}")
                                .build()
                ))
                .build());

        // ClassNotFoundException 修复模板
        STANDARD_TEMPLATES.put("ClassNotFoundException", FixSuggestion.builder()
                .type(FixSuggestion.SuggestionType.DEPENDENCY_UPDATE)
                .title("类依赖检查")
                .description("检查类路径或添加缺失的依赖")
                .confidence(0.85)
                .priority(2)
                .steps(List.of(
                        FixSuggestion.FixStep.builder()
                                .stepNumber(1)
                                .description("检查类的完整限定名是否正确")
                                .build(),
                        FixSuggestion.FixStep.builder()
                                .stepNumber(2)
                                .description("检查是否需要添加Maven/Gradle依赖")
                                .build()
                ))
                .build());

        // SQLException 修复模板
        STANDARD_TEMPLATES.put("SQLException", FixSuggestion.builder()
                .type(FixSuggestion.SuggestionType.CODE_FIX)
                .title("SQL执行修复")
                .description("检查SQL语句和数据库连接")
                .confidence(0.8)
                .priority(2)
                .steps(List.of(
                        FixSuggestion.FixStep.builder()
                                .stepNumber(1)
                                .description("验证SQL语句语法")
                                .build(),
                        FixSuggestion.FixStep.builder()
                                .stepNumber(2)
                                .description("检查数据库连接状态")
                                .build(),
                        FixSuggestion.FixStep.builder()
                                .stepNumber(3)
                                .description("确保正确的事务管理")
                                .build()
                ))
                .build());
    }

    @Override
    public List<FixSuggestion> generateSuggestions(FixSuggestionRequest request) {
        List<FixSuggestion> suggestions = new ArrayList<>();

        // 1. 从标准模板获取基础建议
        if (request.getErrorType() != null) {
            FixSuggestion template = getStandardFixTemplate(request.getErrorType());
            if (template != null) {
                suggestions.add(template);
            }
        }

        // 2. 根据错误消息生成特定建议
        if (request.getErrorMessage() != null) {
            suggestions.addAll(generateFromErrorMessage(request));
        }

        // 3. 根据根因分析生成深度建议
        if (request.getRootCause() != null) {
            suggestions.addAll(generateFromRootCause(request));
        }

        // 按优先级排序
        suggestions.sort(Comparator.comparingInt(FixSuggestion::getPriority));

        log.info("Generated {} fix suggestions for errorType={}", suggestions.size(), request.getErrorType());
        return suggestions;
    }

    @Override
    public FixSuggestion getStandardFixTemplate(String errorType) {
        return STANDARD_TEMPLATES.get(errorType);
    }

    @Override
    public boolean validateSuggestion(FixSuggestion suggestion) {
        if (suggestion == null) {
            return false;
        }
        if (suggestion.getTitle() == null || suggestion.getTitle().isEmpty()) {
            return false;
        }
        if (suggestion.getConfidence() < 0 || suggestion.getConfidence() > 1) {
            return false;
        }
        return suggestion.getPriority() >= 1 && suggestion.getPriority() <= 5;
    }

    /**
     * 从错误消息生成建议
     */
    private List<FixSuggestion> generateFromErrorMessage(FixSuggestionRequest request) {
        List<FixSuggestion> suggestions = new ArrayList<>();
        String message = request.getErrorMessage().toLowerCase();

        // 检测常见错误模式
        if (message.contains("cannot find symbol") || message.contains("cannot resolve")) {
            suggestions.add(FixSuggestion.builder()
                    .type(FixSuggestion.SuggestionType.CODE_FIX)
                    .title("符号解析问题")
                    .description("检查导入语句和类名拼写")
                    .confidence(0.85)
                    .priority(2)
                    .steps(List.of(
                            FixSuggestion.FixStep.builder()
                                    .stepNumber(1)
                                    .description("检查是否缺少import语句")
                                    .build(),
                            FixSuggestion.FixStep.builder()
                                    .stepNumber(2)
                                    .description("验证类名或方法名拼写")
                                    .build()
                    ))
                    .build());
        }

        if (message.contains("timeout") || message.contains("timed out")) {
            suggestions.add(FixSuggestion.builder()
                    .type(FixSuggestion.SuggestionType.PERFORMANCE_FIX)
                    .title("超时问题")
                    .description("检查网络连接或增加超时时间")
                    .confidence(0.8)
                    .priority(2)
                    .steps(List.of(
                            FixSuggestion.FixStep.builder()
                                    .stepNumber(1)
                                    .description("检查网络连接状态")
                                    .build(),
                            FixSuggestion.FixStep.builder()
                                    .stepNumber(2)
                                    .description("考虑增加超时配置")
                                    .build()
                    ))
                    .build());
        }

        return suggestions;
    }

    /**
     * 从根因分析生成建议
     */
    private List<FixSuggestion> generateFromRootCause(FixSuggestionRequest request) {
        List<FixSuggestion> suggestions = new ArrayList<>();
        String rootCause = request.getRootCause().toLowerCase();

        if (rootCause.contains("uninitialized") || rootCause.contains("未初始化")) {
            suggestions.add(FixSuggestion.builder()
                    .type(FixSuggestion.SuggestionType.CODE_FIX)
                    .title("初始化问题")
                    .description("确保变量在使用前正确初始化")
                    .confidence(0.9)
                    .priority(1)
                    .className(request.getClassName())
                    .methodName(request.getMethodName())
                    .build());
        }

        if (rootCause.contains("concurrent") || rootCause.contains("thread") || rootCause.contains("并发")) {
            suggestions.add(FixSuggestion.builder()
                    .type(FixSuggestion.SuggestionType.CODE_FIX)
                    .title("并发问题")
                    .description("检查线程安全和同步机制")
                    .confidence(0.85)
                    .priority(1)
                    .steps(List.of(
                            FixSuggestion.FixStep.builder()
                                    .stepNumber(1)
                                    .description("检查共享资源的访问方式")
                                    .build(),
                            FixSuggestion.FixStep.builder()
                                    .stepNumber(2)
                                    .description("考虑使用同步机制或并发工具类")
                                    .build()
                    ))
                    .build());
        }

        return suggestions;
    }
}