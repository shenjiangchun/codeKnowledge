package com.huawei.hisi.piagent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.agentflow.AgentLlmClient;
import com.huawei.hisi.agentflow.AgentLlmTimeoutException;
import com.huawei.hisi.piagent.context.JsonPayload;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

/**
 * 让「一次性的结构化 LLM 调用」也走 pi：{@link AgentLlmClient} 的 pi 实现。
 *
 * <p>各业务流（工作台的方案 / Spec / 任务 / 改动方案、日志 v2 的假设 / 综合 / 校验）只依赖
 * {@link AgentLlmClient}，底层换成 pi 只改这里。每次调用是一个独立的 pi 进程（实测启动约 1.2 秒，
 * 相对模型本身的几秒到几十秒可以接受），清单为 {@link FlowToolManifest#ONE_SHOT}：不带任何工具、不连 MCP、
 * 关闭模型思考——模型只负责按提示词作答。
 *
 * <p>沿用旧适配器对外可见的行为：pi 未就绪时 {@link #isAvailable()} 为假，由调用方走确定性降级；
 * 单次调用有时限，超时抛 {@link AgentLlmTimeoutException}；没有拿到可解析的 JSON 时重试一次，仍不行才抛异常。
 */
@Slf4j
@Primary
@Component
public class PiAgentLlmClient implements AgentLlmClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PiFlowClient piClient;
    private final PiAgentInstaller installer;
    private final PiAgentProperties properties;
    private final int callTimeoutSeconds;

    public PiAgentLlmClient(PiFlowClient piClient,
                            PiAgentInstaller installer,
                            PiAgentProperties properties,
                            @Value("${agentflow.llm.call-timeout-seconds:300}") int callTimeoutSeconds) {
        this.piClient = piClient;
        this.installer = installer;
        this.properties = properties;
        this.callTimeoutSeconds = callTimeoutSeconds;
    }

    @Override
    public boolean isAvailable() {
        // 启动时没装上不算定局：调用方拿这个当开关，缺了就先补装，再回答
        installer.ensureReady();
        return installer.ready() && properties.flowEnabled(FlowToolManifest.ONE_SHOT);
    }

    @Override
    public String completeText(String systemPrompt, String userPrompt, int maxTokens) {
        long start = System.currentTimeMillis();
        String text = call(systemPrompt, userPrompt, start);
        if (text.isBlank()) {
            log.warn("[AgentLlm] 首次调用返回空正文（{}ms），重试一次", System.currentTimeMillis() - start);
            text = call(systemPrompt, userPrompt, start);
        }
        log.info("[AgentLlm] 文本调用完成: {}ms，输出 {} 字符", System.currentTimeMillis() - start, text.length());
        return text;
    }

    @Override
    public Map<String, Object> completeJson(String systemPrompt, String userPrompt, int maxTokens) {
        long start = System.currentTimeMillis();
        String text = call(systemPrompt, userPrompt, start);
        Map<String, Object> json = firstObject(text);
        if (json == null) {
            // 输出不是 JSON（多半是写成了大白话）：带着上一次的输出让它只改成 JSON，而不是从头重写结论
            log.warn("[AgentLlm] 首次调用没有返回 JSON（{}ms，输出 {} 字符），要求改成 JSON 重试一次",
                    System.currentTimeMillis() - start, text.length());
            text = call(systemPrompt + "\n\n注意：只输出一个 JSON 对象，不要任何解释、前后缀或代码围栏。",
                    userPrompt + "\n\n---\n你上一次的输出不是合法 JSON，请在保持内容不变的前提下只输出 JSON：\n" + text, start);
            json = firstObject(text);
        }
        log.info("[AgentLlm] JSON 调用完成: {}ms", System.currentTimeMillis() - start);
        if (json == null) {
            throw new IllegalStateException("LLM 未返回可解析的 JSON");
        }
        return json;
    }

    /** 发一次 pi 调用并返回正文；时限、中断、模型报错都在这里变成异常。 */
    private String call(String systemPrompt, String userPrompt, long start) {
        if (!isAvailable()) {
            throw new IllegalStateException("pi 未就绪，无法执行一次性调用");
        }
        long deadlineAt = System.currentTimeMillis() + callTimeoutSeconds * 1000L;
        PiFlowClient.PiResult result = piClient.run(FlowToolManifest.ONE_SHOT, "llm-" + shortId(), workDir(), null,
                systemPrompt, userPrompt, null,
                () -> Thread.currentThread().isInterrupted() || System.currentTimeMillis() > deadlineAt);
        if (Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException("LLM 调用被中断");
        }
        if (System.currentTimeMillis() > deadlineAt && !"agent_settled".equals(result.stopReason())) {
            throw new AgentLlmTimeoutException("LLM 调用超过 " + callTimeoutSeconds + " 秒未返回");
        }
        if ("timeout".equals(result.stopReason())) {
            throw new AgentLlmTimeoutException("LLM 调用超过 pi 会话时限未返回");
        }
        if (result.note() != null && result.finalText().isBlank()) {
            throw new IllegalStateException("LLM 调用失败：" + result.note());
        }
        return result.finalText();
    }

    /** 正文里第一个能解析成 JSON 对象的片段（最外层优先）；整段都不是时返回 null。 */
    static Map<String, Object> firstObject(String text) {
        for (String candidate : JsonPayload.objectCandidates(text)) {
            try {
                Map<String, Object> parsed = MAPPER.readValue(candidate, new TypeReference<Map<String, Object>>() {});
                if (!parsed.isEmpty()) {
                    return parsed;
                }
            } catch (Exception ignored) {
                // 不是 JSON，试下一个片段
            }
        }
        return null;
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 12);
    }

    /** 一次性调用的 pi 工作目录：不指向任何被分析的项目，用 pi 的配置目录。 */
    private Path workDir() {
        try {
            Path dir = installer.agentDir().resolve("scratch");
            Files.createDirectories(dir);
            return dir;
        } catch (Exception e) {
            return installer.agentDir();
        }
    }
}
