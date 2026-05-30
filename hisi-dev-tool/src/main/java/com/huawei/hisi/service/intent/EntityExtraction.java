package com.huawei.hisi.service.intent;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

/**
 * 实体提取结果
 * 从用户输入中提取的关键实体信息
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EntityExtraction {

    /**
     * 异常类型（如 NullPointerException, TimeoutException）
     */
    private String exceptionType;

    /**
     * 类名（完整类名或简名）
     */
    private String className;

    /**
     * 方法名
     */
    private String methodName;

    /**
     * 文件路径
     */
    private String filePath;

    /**
     * 行号（如果有）
     */
    private Integer lineNumber;

    /**
     * 错误关键词
     */
    @Builder.Default
    private List<String> errorKeywords = new ArrayList<>();

    /**
     * 时间范围（如果有）
     */
    private String timeRange;

    /**
     * 关注领域（用户指定关注的内容）
     */
    private String focusArea;

    /**
     * 其他实体（扩展字段）
     */
    @Builder.Default
    private Map<String, String> additionalEntities = new HashMap<>();

    /**
     * 是否包含堆栈信息
     */
    @Builder.Default
    private boolean hasStackTrace = false;

    /**
     * 是否包含代码片段
     */
    @Builder.Default
    private boolean hasCodeSnippet = false;

    // ============== 转换方法 ==============

    /**
     * 转换为Map格式
     */
    public Map<String, String> toMap() {
        Map<String, String> map = new HashMap<>();

        if (exceptionType != null) {
            map.put("errorType", exceptionType);
        }
        if (className != null) {
            map.put("className", className);
        }
        if (methodName != null) {
            map.put("methodName", methodName);
        }
        if (filePath != null) {
            map.put("filePath", filePath);
        }
        if (lineNumber != null) {
            map.put("lineNumber", String.valueOf(lineNumber));
        }
        if (focusArea != null) {
            map.put("focusArea", focusArea);
        }
        if (timeRange != null) {
            map.put("timeRange", timeRange);
        }

        if (additionalEntities != null) {
            map.putAll(additionalEntities);
        }

        return map;
    }

    /**
     * 判断是否为空提取结果
     */
    public boolean isEmpty() {
        return exceptionType == null && className == null && methodName == null
                && filePath == null && focusArea == null
                && (errorKeywords == null || errorKeywords.isEmpty());
    }

    /**
     * 判断是否有足够的实体信息
     */
    public boolean hasEnoughInfo() {
        return exceptionType != null || className != null || methodName != null;
    }
}