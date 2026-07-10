package com.huawei.hisi.fixengine.agent;

import com.huawei.hisi.apm.service.locator.LlmClient;
import com.huawei.hisi.ram.model.AgentEvent;
import com.huawei.hisi.ram.repository.AgentEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

/**
 * AI agent that generates a source-code fix for a method that throws an exception.
 * Reads its prompt template from {@code classpath:prompt/fix-prompt.txt}.
 */
@Slf4j
@Component
public class FixAgent {

    /** Max recent events pulled as context for follow-up questions. */
    private static final int FOLLOW_UP_CONTEXT_WINDOW = 10;

    private final LlmClient llm;
    private final AgentEventRepository agentEventRepository;

    public FixAgent(LlmClient llm, AgentEventRepository agentEventRepository) {
        this.llm = llm;
        this.agentEventRepository = agentEventRepository;
    }

    /**
     * Generate a fixed version of the target method.
     *
     * @param methodSignature  FQN of the method under fix
     * @param exceptionType    simple class name of the exception
     * @param exceptionMessage exception message
     * @param entryParams      serialized entry-point parameters
     * @param sourceCode       current source of the method
     * @return the corrected method source code
     */
    public String generateFix(String methodSignature,
                              String exceptionType,
                              String exceptionMessage,
                              String entryParams,
                              String sourceCode) {
        String template = loadTemplate();

        String userPrompt = template
                .replace("{methodSignature}", nz(methodSignature))
                .replace("{exceptionType}", nz(exceptionType))
                .replace("{exceptionMessage}", nz(exceptionMessage))
                .replace("{entryParams}", nz(entryParams))
                .replace("{sourceCode}", nz(sourceCode));

        String systemPrompt = "You are a senior Java engineer. "
                + "Output only the corrected Java method source code, no explanations.";

        log.info("[FixAgent] generating fix for {} exception={}", methodSignature, exceptionType);
        String result = llm.chat(systemPrompt, userPrompt);
        log.info("[FixAgent] generated {} chars", result.length());
        return stripCodeFences(result);
    }

    // ------------------------------------------------------------------

    /**
     * Handle a follow-up question from the user about an in-progress fix session.
     * Pulls the most recent events as context and asks the LLM. Read-only:
     * does not touch the fix flow's state machine.
     */
    public String handleFollowUp(long chatSessionId, String userMessage) {
        List<AgentEvent> recent = agentEventRepository.findBySessionId(chatSessionId);
        List<AgentEvent> tail = recent.size() > FOLLOW_UP_CONTEXT_WINDOW
                ? recent.subList(recent.size() - FOLLOW_UP_CONTEXT_WINDOW, recent.size())
                : recent;

        StringBuilder ctx = new StringBuilder();
        for (AgentEvent e : tail) {
            ctx.append(e.getType()).append(": ").append(e.getPayload()).append('\n');
        }

        String systemPrompt = "You are assisting with an in-progress Java auto-fix. "
                + "Use the recent events as context. Be concise. "
                + "If the fix flow is still running, tell the user the current step.";
        String userPrompt = "Recent events:\n" + ctx + "\n\nUser question: " + userMessage;

        log.info("[FixAgent] handleFollowUp sid={} events={} msg.len={}",
                chatSessionId, tail.size(), userMessage.length());
        return llm.chat(systemPrompt, userPrompt);
    }

    // ------------------------------------------------------------------

    private String loadTemplate() {
        try (var reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource("prompt/fix-prompt.txt").getInputStream(),
                StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining("\n"));
        } catch (Exception e) {
            log.warn("[FixAgent] template not found, using fallback");
            return DEFAULT_TEMPLATE;
        }
    }

    private static String stripCodeFences(String text) {
        if (text == null) return "";
        String trimmed = text.strip();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline > 0) {
                trimmed = trimmed.substring(firstNewline + 1);
            }
            if (trimmed.endsWith("```")) {
                trimmed = trimmed.substring(0, trimmed.length() - 3);
            }
        }
        return trimmed.strip();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static final String DEFAULT_TEMPLATE = """
            你是 Java 资深工程师。修复以下方法中的异常。

            ## 目标方法
            {methodSignature}

            ## 异常
            类型：{exceptionType}
            message：{exceptionMessage}

            ## 当前源代码
            ```java
            {sourceCode}
            ```

            输出修改后的完整方法源代码。
            """;
}
