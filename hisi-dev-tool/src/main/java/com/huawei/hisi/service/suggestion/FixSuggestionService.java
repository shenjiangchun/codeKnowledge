package com.huawei.hisi.service.suggestion;

import com.huawei.hisi.service.suggestion.model.FixSuggestion;

import java.util.List;

/**
 * 修复建议服务接口
 * 用于根据诊断结果生成可执行的修复建议
 */
public interface FixSuggestionService {

    /**
     * 根据诊断上下文生成修复建议
     *
     * @param request 修复建议请求
     * @return 修复建议列表
     */
    List<FixSuggestion> generateSuggestions(FixSuggestionRequest request);

    /**
     * 根据错误类型获取标准修复模板
     *
     * @param errorType 错误类型
     * @return 标准修复建议
     */
    FixSuggestion getStandardFixTemplate(String errorType);

    /**
     * 验证修复建议是否有效
     *
     * @param suggestion 修复建议
     * @return 是否有效
     */
    boolean validateSuggestion(FixSuggestion suggestion);

    /**
     * 修复建议请求
     */
    class FixSuggestionRequest {
        private String errorType;
        private String errorMessage;
        private String stackTrace;
        private String className;
        private String methodName;
        private String sourceCode;
        private String rootCause;
        private String projectId;

        public FixSuggestionRequest() {}

        public String getErrorType() { return errorType; }
        public void setErrorType(String errorType) { this.errorType = errorType; }
        public String getErrorMessage() { return errorMessage; }
        public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
        public String getStackTrace() { return stackTrace; }
        public void setStackTrace(String stackTrace) { this.stackTrace = stackTrace; }
        public String getClassName() { return className; }
        public void setClassName(String className) { this.className = className; }
        public String getMethodName() { return methodName; }
        public void setMethodName(String methodName) { this.methodName = methodName; }
        public String getSourceCode() { return sourceCode; }
        public void setSourceCode(String sourceCode) { this.sourceCode = sourceCode; }
        public String getRootCause() { return rootCause; }
        public void setRootCause(String rootCause) { this.rootCause = rootCause; }
        public String getProjectId() { return projectId; }
        public void setProjectId(String projectId) { this.projectId = projectId; }
    }
}