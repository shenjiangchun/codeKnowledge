package com.huawei.hisi.fixengine.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.apm.service.locator.LlmClient;
import com.huawei.hisi.fixengine.model.TestGenInput;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

/**
 * AI agent that generates a Mockito unit test to reproduce a target exception.
 * Reads its prompt template from {@code classpath:prompt/test-gen-prompt.txt}.
 */
@Slf4j
@Component
public class TestGenAgent {

    private final LlmClient llm;
    private final ObjectMapper objectMapper;

    public TestGenAgent(LlmClient llm, ObjectMapper objectMapper) {
        this.llm = llm;
        this.objectMapper = objectMapper;
    }

    /**
     * Generate test source code for the given input.
     *
     * @param input test-generation context
     * @return Java source of the generated test class
     */
    public String generate(TestGenInput input) {
        String template = loadTemplate();
        String userPrompt = fillTemplate(template, input);

        String systemPrompt = "You are a senior Java test engineer. "
                + "Output only valid Java source code, no markdown fences or explanations.";

        log.info("[TestGenAgent] generating test for {} sig={}",
                input.getExceptionType(), input.getTestMethodSignature());

        String result = llm.chat(systemPrompt, userPrompt);
        log.info("[TestGenAgent] generated {} chars", result.length());
        return stripCodeFences(result);
    }

    /**
     * Attempt to fix a previously generated test that fails to compile or run.
     *
     * @param originalCode the failing test source
     * @param errorMessage compiler / test error output
     * @return corrected Java source
     */
    public String fixTest(String originalCode, String errorMessage) {
        String systemPrompt = "You are a senior Java test engineer. "
                + "Fix the compilation or runtime error in the test below. "
                + "Output only the corrected Java source code.";

        String userPrompt = "## Failing test code\n```java\n" + originalCode + "\n```\n\n"
                + "## Error\n" + errorMessage + "\n\n"
                + "Return the corrected Java source code only.";

        log.info("[TestGenAgent] fixing test, error.len={}", errorMessage.length());
        String result = llm.chat(systemPrompt, userPrompt);
        return stripCodeFences(result);
    }

    // ------------------------------------------------------------------

    private String loadTemplate() {
        try (var reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource("prompt/test-gen-prompt.txt").getInputStream(),
                StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining("\n"));
        } catch (Exception e) {
            log.warn("[TestGenAgent] template not found, using fallback");
            return DEFAULT_TEMPLATE;
        }
    }

    private String fillTemplate(String template, TestGenInput input) {
        String spansJson;
        String paramsJson;
        try {
            spansJson = objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(input.getSpans());
            paramsJson = objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(input.getEntryParams());
        } catch (Exception e) {
            spansJson = String.valueOf(input.getSpans());
            paramsJson = String.valueOf(input.getEntryParams());
        }

        return template
                .replace("{exceptionType}", nz(input.getExceptionType()))
                .replace("{exceptionMessage}", nz(input.getExceptionMessage()))
                .replace("{testMethodSignature}", nz(input.getTestMethodSignature()))
                .replace("{entryParamsJson}", paramsJson)
                .replace("{spansJson}", spansJson)
                .replace("{testMethodName}", nz(input.getTestMethodName()));
    }

    private static String stripCodeFences(String text) {
        if (text == null) return "";
        String trimmed = text.strip();
        // remove ```java ... ``` or ``` ... ```
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
            你是一个 Java 测试工程师。基于以下信息，生成一个 Mockito 纯单测。

            ## 目标
            - @ExtendWith(MockitoExtension.class)
            - 复现目标异常：{exceptionType}: {exceptionMessage}

            ## 被测方法
            {testMethodSignature}

            ## 入参
            {entryParamsJson}

            ## 调用链
            {spansJson}

            ## 测试方法名
            {testMethodName}

            ## 输出要求
            只输出 Java 源代码。
            """;
}
