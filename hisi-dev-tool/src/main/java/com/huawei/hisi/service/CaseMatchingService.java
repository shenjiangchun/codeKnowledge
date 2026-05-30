package com.huawei.hisi.service;

import com.huawei.hisi.model.DiagnosisCase;

import java.util.List;
import java.util.Optional;

/**
 * 案例匹配服务接口
 * 用于历史诊断案例的存储和相似度匹配
 */
public interface CaseMatchingService {

    /**
     * 保存诊断案例
     *
     * @param diagnosisCase 诊断案例
     * @return 保存后的案例（含生成的ID）
     */
    DiagnosisCase saveCase(DiagnosisCase diagnosisCase);

    /**
     * 匹配相似案例
     *
     * @param context 诊断上下文（错误类型、消息、堆栈等）
     * @param limit   返回数量限制
     * @return 相似案例列表（按相似度排序）
     */
    List<DiagnosisCase> matchSimilarCases(DiagnosisContext context, int limit);

    /**
     * 根据ID查找案例
     *
     * @param id 案例ID
     * @return 案例Optional
     */
    Optional<DiagnosisCase> findById(String id);

    /**
     * 根据错误类型查找案例
     *
     * @param errorType 错误类型
     * @return 案例列表
     */
    List<DiagnosisCase> findByErrorType(String errorType);

    /**
     * 验证案例
     *
     * @param id       案例ID
     * @param feedback 用户反馈（验证有效/无效）
     */
    void verifyCase(String id, String feedback);

    /**
     * 获取热门案例（使用频率高）
     *
     * @param projectPath 项目路径
     * @param limit       返回数量限制
     * @return 热门案例列表
     */
    List<DiagnosisCase> getHotCases(String projectPath, int limit);

    /**
     * 增加案例使用次数
     *
     * @param id 案例ID
     */
    void incrementUsageCount(String id);

    /**
     * 删除案例
     *
     * @param id 案例ID
     */
    void deleteCase(String id);

    /**
     * 获取案例总数
     *
     * @return 案例总数
     */
    long getCaseCount();

    /**
     * 诊断上下文
     */
    class DiagnosisContext {
        private String errorType;
        private String errorMessage;
        private String stackTrace;
        private String projectPath;
        private List<String> relatedClasses;

        public DiagnosisContext() {}

        public DiagnosisContext(String errorType, String errorMessage, String stackTrace) {
            this.errorType = errorType;
            this.errorMessage = errorMessage;
            this.stackTrace = stackTrace;
        }

        public String getErrorType() { return errorType; }
        public void setErrorType(String errorType) { this.errorType = errorType; }
        public String getErrorMessage() { return errorMessage; }
        public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
        public String getStackTrace() { return stackTrace; }
        public void setStackTrace(String stackTrace) { this.stackTrace = stackTrace; }
        public String getProjectPath() { return projectPath; }
        public void setProjectPath(String projectPath) { this.projectPath = projectPath; }
        public List<String> getRelatedClasses() { return relatedClasses; }
        public void setRelatedClasses(List<String> relatedClasses) { this.relatedClasses = relatedClasses; }
    }
}