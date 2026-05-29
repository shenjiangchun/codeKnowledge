package com.huawei.hisi.agent.model;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent分析信息DTO
 * 用于存储分析过程中的中间结果
 *
 * 从 AnalysisAgent 内部类提取为独立DTO
 *
 * @author HiAPM Plugin Team
 * @version 2.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentAnalysisInfo {

    /**
     * 严重程度枚举
     */
    public enum Severity {
        LOW,       // 低风险 - 仅影响非核心功能
        MEDIUM,    // 中风险 - 影响部分核心功能
        HIGH,      // 高风险 - 影响主要业务流程
        CRITICAL   // 严重风险 - 导致系统不可用或数据丢失
    }

    /**
     * 分析是否成功
     */
    @Builder.Default
    public boolean success = false;

    /**
     * 异常类型
     */
    public String exceptionType;

    /**
     * 风险评分（0-100）
     */
    @Builder.Default
    public int riskScore = 30;

    /**
     * 严重程度
     */
    @Builder.Default
    public Severity severity = Severity.MEDIUM;

    /**
     * 影响范围描述
     */
    @Builder.Default
    public String impactScope = "待分析";

    /**
     * 受影响模块列表
     */
    @Builder.Default
    public List<String> affectedModules = new ArrayList<>();

    /**
     * 分析结论
     */
    public String conclusion;

    /**
     * 风险因素列表
     */
    public List<String> riskFactors;

    /**
     * 建议列表
     */
    public List<String> recommendations;

    /**
     * 添加受影响模块
     */
    public void addAffectedModule(String module) {
        if (affectedModules == null) {
            affectedModules = new ArrayList<>();
        }
        affectedModules.add(module);
    }

    /**
     * 添加风险因素
     */
    public void addRiskFactor(String factor) {
        if (riskFactors == null) {
            riskFactors = new ArrayList<>();
        }
        riskFactors.add(factor);
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