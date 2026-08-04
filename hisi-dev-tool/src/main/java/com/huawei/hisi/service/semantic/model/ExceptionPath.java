package com.huawei.hisi.service.semantic.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 异常路径分析结果
 *
 * 用于表示异常传播路径分析的完整结果，包含异常类型、发生位置和所有可能的传播路径。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExceptionPath {

    /**
     * 异常类型（如 NullPointerException, SQLException 等）
     */
    private String exceptionType;

    /**
     * 异常发生位置（如类名.方法名格式）
     */
    private String location;

    /**
     * 所有可能的传播路径列表
     * 按概率从高到低排序
     */
    private List<PropagationPath> propagationPaths;

    /**
     * 分析时间戳（毫秒）
     */
    private Long analyzedAt;

    /**
     * 获取Top N高概率传播路径
     * @param n 返回的路径数量
     * @return 排序后的前N条传播路径
     */
    public List<PropagationPath> getTopPaths(int n) {
        if (propagationPaths == null || propagationPaths.isEmpty()) {
            return List.of();
        }
        return propagationPaths.stream()
                .limit(n)
                .toList();
    }

    /**
     * 获取最高概率的传播路径
     * @return 概率最高的传播路径，如果没有则返回null
     */
    public PropagationPath getMostLikelyPath() {
        if (propagationPaths == null || propagationPaths.isEmpty()) {
            return null;
        }
        return propagationPaths.get(0);
    }
}