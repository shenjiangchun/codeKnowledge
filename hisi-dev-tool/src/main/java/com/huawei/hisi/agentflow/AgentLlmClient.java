package com.huawei.hisi.agentflow;

import java.util.Map;

/**
 * v2 agent 流使用的最小 LLM 接口。
 *
 * <p>各业务流只依赖这个接口，不直接依赖具体的模型客户端；底层客户端替换时只改适配器。
 */
public interface AgentLlmClient {

    /** 模型是否已配置可用。不可用时调用方应走确定性降级，而不是让流程失败。 */
    boolean isAvailable();

    /** 自由文本补全，返回模型输出的原文。 */
    String completeText(String systemPrompt, String userPrompt, int maxTokens);

    /** 要求模型输出 JSON 对象并解析为 Map；解析失败时抛异常。 */
    Map<String, Object> completeJson(String systemPrompt, String userPrompt, int maxTokens);
}
