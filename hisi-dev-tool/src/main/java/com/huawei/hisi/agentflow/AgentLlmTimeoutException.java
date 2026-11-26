package com.huawei.hisi.agentflow;

/**
 * 一次 LLM 调用超过了给定时限仍未返回。
 *
 * <p>继承 {@link IllegalStateException}：对调用方而言它与「调用失败」同类，都走确定性兜底；
 * 单独成类是为了在日志与测试里把它和真正的模型错误区分开。
 */
public class AgentLlmTimeoutException extends IllegalStateException {

    public AgentLlmTimeoutException(String message) {
        super(message);
    }
}
