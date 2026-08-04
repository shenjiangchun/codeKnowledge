package com.huawei.hisi.agent.model;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent验证信息DTO
 * 用于存储验证过程中的中间结果
 *
 * 从 VerificationAgent 内部类提取为独立DTO
 *
 * @author HiAPM Plugin Team
 * @version 2.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentVerificationInfo {

    /**
     * 置信度级别枚举
     */
    public enum ConfidenceLevel {
        VERY_LOW,   // 极低置信度 - 结果不可信，需重新诊断
        LOW,        // 低置信度 - 结果可能不准确
        MEDIUM,     // 中等置信度 - 结果有一定可信度
        HIGH,       // 高置信度 - 结果可信
        VERY_HIGH   // 极高置信度 - 多Agent一致验证通过
    }

    /**
     * 验证是否成功
     */
    @Builder.Default
    public boolean success = false;

    /**
     * 验证分数（0-100）
     */
    @Builder.Default
    public int validationScore = 0;

    /**
     * 置信度级别
     */
    @Builder.Default
    public ConfidenceLevel confidenceLevel = ConfidenceLevel.MEDIUM;

    /**
     * 验证是否通过
     */
    @Builder.Default
    public boolean isValid = false;

    /**
     * StackTrace验证是否通过
     */
    @Builder.Default
    public boolean stackTraceValid = false;

    /**
     * Analysis验证是否通过
     */
    @Builder.Default
    public boolean analysisValid = false;

    /**
     * 结论是否一致
     */
    @Builder.Default
    public boolean conclusionConsistent = false;

    /**
     * 交叉验证是否通过
     */
    @Builder.Default
    public boolean crossValidationPassed = false;

    /**
     * Agent一致性比率
     */
    @Builder.Default
    public double agreementRate = 0.0;

    /**
     * 已验证的代码列表
     */
    @Builder.Default
    public List<String> validatedCode = new ArrayList<>();

    /**
     * 警告列表
     */
    @Builder.Default
    public List<String> warnings = new ArrayList<>();

    /**
     * 问题列表
     */
    public List<String> issues;

    /**
     * 强项列表（验证通过的点）
     */
    public List<String> strengths;

    /**
     * 建议列表
     */
    public List<String> recommendations;

    /**
     * 验证结论
     */
    public String conclusion;

    /**
     * 添加已验证代码
     */
    public void addValidatedCode(String code) {
        if (validatedCode == null) {
            validatedCode = new ArrayList<>();
        }
        validatedCode.add(code);
    }

    /**
     * 添加警告
     */
    public void addWarning(String warning) {
        if (warnings == null) {
            warnings = new ArrayList<>();
        }
        warnings.add(warning);
    }

    /**
     * 添加问题
     */
    public void addIssue(String issue) {
        if (issues == null) {
            issues = new ArrayList<>();
        }
        issues.add(issue);
    }

    /**
     * 添加强项
     */
    public void addStrength(String strength) {
        if (strengths == null) {
            strengths = new ArrayList<>();
        }
        strengths.add(strength);
    }

    /**
     * 添加建议
     */
    public void addRecommendation(String recommendation) {
        if (recommendations == null) {
            recommendations = new ArrayList<>();
        }
        recommendations.add(recommendation);
    }
}