package com.huawei.hisi.agent.model;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 交叉验证信息DTO
 * 用于存储多Agent交叉验证的结果
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
public class AgentCrossValidationInfo {

    /**
     * 交叉验证是否通过
     */
    @Builder.Default
    public boolean passed = false;

    /**
     * Agent一致性比率
     */
    @Builder.Default
    public double agreementRate = 0.0;

    /**
     * 平均置信度
     */
    @Builder.Default
    public double averageConfidence = 0.0;

    /**
     * 置信度标准差
     */
    @Builder.Default
    public double confidenceStdDev = 0.0;

    /**
     * 详细信息列表
     */
    @Builder.Default
    public List<String> details = new ArrayList<>();

    /**
     * 添加详细信息
     */
    public void addDetail(String detail) {
        if (details == null) {
            details = new ArrayList<>();
        }
        details.add(detail);
    }
}