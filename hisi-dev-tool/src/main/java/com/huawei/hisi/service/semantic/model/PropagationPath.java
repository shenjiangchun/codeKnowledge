package com.huawei.hisi.service.semantic.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 异常传播路径
 *
 * 表示一条从异常抛出点到当前位置的传播路径，包含调用链、概率等信息。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PropagationPath {

    /**
     * 异常抛出的源方法（完整签名，如 com.example.UserService.login）
     */
    private String sourceMethod;

    /**
     * 调用链（从源方法到当前位置的方法调用序列）
     * 第一个元素是异常抛出方法，最后一个元素是当前位置方法
     */
    private List<String> callChain;

    /**
     * 该传播路径的概率（0.0-1.0）
     * 根据调用链长度、异常类型匹配度等因素计算
     */
    private Double probability;

    /**
     * 异常抛出时的代码行号（如果可获取）
     */
    private Integer lineNumber;

    /**
     * 异常抛出点所在的类名
     */
    private String sourceClassName;

    /**
     * 异常抛出点所在的文件名
     */
    private String sourceFileName;

    /**
     * 该路径是否经过try-catch块
     */
    private Boolean hasTryCatch;

    /**
     * 捕获异常的处理方式（如果经过try-catch）
     * 如：rethrow, log, ignore, wrap
     */
    private String catchBehavior;

    /**
     * 获取调用链深度（调用链长度）
     * @return 调用链中的方法数量
     */
    public int getChainDepth() {
        if (callChain == null) {
            return 0;
        }
        return callChain.size();
    }

    /**
     * 判断是否为直接抛出（调用链长度为1）
     * @return true表示异常直接在当前方法抛出
     */
    public boolean isDirectThrow() {
        return getChainDepth() == 1;
    }

    /**
     * 格式化调用链为可读字符串
     * @return 格式化的调用链字符串，如 "A.method() -> B.method() -> C.method()"
     */
    public String formatCallChain() {
        if (callChain == null || callChain.isEmpty()) {
            return "";
        }
        return String.join(" -> ", callChain);
    }
}