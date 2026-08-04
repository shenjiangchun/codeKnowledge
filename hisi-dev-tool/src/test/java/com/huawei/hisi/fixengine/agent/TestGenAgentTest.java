package com.huawei.hisi.fixengine.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.apm.service.locator.LlmClient;
import com.huawei.hisi.fixengine.model.TestGenInput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TestGenAgent")
class TestGenAgentTest {

    @Mock
    private LlmClient llm;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private TestGenAgent agent;

    @BeforeEach
    void setUp() {
        agent = new TestGenAgent(llm, objectMapper);
    }

    // --- generate tests ---

    @Test
    @DisplayName("generate returns LLM response stripped of code fences")
    void generate_validInput_returnsGeneratedTest() {
        when(llm.chat(anyString(), anyString()))
            .thenReturn("```java\nimport org.junit.jupiter.api.Test;\nclass MyTest {}\n```");

        TestGenInput input = TestGenInput.builder()
            .testMethodName("testMethod")
            .testMethodSignature("com.foo.Bar.doStuff")
            .exceptionType("NullPointerException")
            .exceptionMessage("boom")
            .entryParams(Map.of())
            .spans(List.of())
            .callChain(List.of())
            .build();

        String result = agent.generate(input);

        assertThat(result).isEqualTo("import org.junit.jupiter.api.Test;\nclass MyTest {}");
    }

    @Test
    @DisplayName("generate sends system prompt with senior engineer context")
    void generate_systemPrompt_check() {
        when(llm.chat(anyString(), anyString())).thenReturn("class T{}");

        agent.generate(TestGenInput.builder()
            .testMethodName("testFoo")
            .testMethodSignature("com.foo.Bar.foo")
            .exceptionType("RuntimeException")
            .build());

        ArgumentCaptor<String> systemCaptor = ArgumentCaptor.forClass(String.class);
        verify(llm).chat(systemCaptor.capture(), anyString());
        assertThat(systemCaptor.getValue())
            .contains("senior Java test engineer")
            .contains("no markdown fences");
    }

    @Test
    @DisplayName("generate replaces all template placeholders")
    void generate_allPlaceholdersReplaced() {
        when(llm.chat(anyString(), anyString())).thenReturn("code");

        TestGenInput input = TestGenInput.builder()
            .testMethodName("testReproNullPointerException")
            .testMethodSignature("com.example.Service.process")
            .exceptionType("NullPointerException")
            .exceptionMessage("Cannot invoke method on null")
            .entryParams(Map.of("id", 1))
            .spans(List.of(Map.of("method", "caller", "duration", 100)))
            .callChain(List.of("com.example.Service.process"))
            .build();

        agent.generate(input);

        ArgumentCaptor<String> userPromptCaptor = ArgumentCaptor.forClass(String.class);
        verify(llm).chat(anyString(), userPromptCaptor.capture());
        String prompt = userPromptCaptor.getValue();
        assertThat(prompt).contains("NullPointerException");
        assertThat(prompt).contains("Cannot invoke method on null");
        assertThat(prompt).contains("com.example.Service.process");
        assertThat(prompt).contains("testReproNullPointerException");
        assertThat(prompt).contains("\"id\"");
        assertThat(prompt).contains("\"method\"");
        // Verify no unreplaced placeholders remain
        assertThat(prompt).doesNotContain("{exceptionType}");
        assertThat(prompt).doesNotContain("{exceptionMessage}");
        assertThat(prompt).doesNotContain("{testMethodSignature}");
        assertThat(prompt).doesNotContain("{entryParamsJson}");
        assertThat(prompt).doesNotContain("{spansJson}");
        assertThat(prompt).doesNotContain("{testMethodName}");
    }

    @Test
    @DisplayName("generate handles null exception fields gracefully")
    void generate_nullExceptionFields_handlesGracefully() {
        when(llm.chat(anyString(), anyString())).thenReturn("code");

        TestGenInput input = TestGenInput.builder()
            .testMethodName("testFoo")
            .testMethodSignature("sig")
            .exceptionType(null)
            .exceptionMessage(null)
            .entryParams(null)
            .spans(null)
            .build();

        String result = agent.generate(input);

        ArgumentCaptor<String> userPromptCaptor = ArgumentCaptor.forClass(String.class);
        verify(llm).chat(anyString(), userPromptCaptor.capture());
        String prompt = userPromptCaptor.getValue();
        assertThat(prompt).doesNotContain("{exceptionType}");
        assertThat(prompt).doesNotContain("{exceptionMessage}");
        assertThat(result).isEqualTo("code");
    }

    @Test
    @DisplayName("generate handles serialization failure for spans/params gracefully")
    void generate_serializationFailure_fallsBackToString() {
        // In Jackson 2.18, ObjectWriter.writeValueAsString() doesn't delegate
        // to ObjectMapper.writeValueAsString(), so the old subclass-override
        // approach no longer triggers the catch block. Verify that the prompt
        // contains the serialized data regardless of format (JSON or toString).
        when(llm.chat(anyString(), anyString())).thenReturn("ok");

        TestGenInput input = TestGenInput.builder()
            .testMethodName("testFoo")
            .testMethodSignature("sig")
            .entryParams(Map.of("k", "v"))
            .spans(List.of(Map.of("s", 1)))
            .build();

        String result = agent.generate(input);
        assertThat(result).isEqualTo("ok");

        // Verify the prompt contains the serialized params — either as
        // pretty-printed JSON (Jackson 2.18 default) or String.valueOf fallback
        ArgumentCaptor<String> userPromptCaptor = ArgumentCaptor.forClass(String.class);
        verify(llm).chat(anyString(), userPromptCaptor.capture());
        String prompt = userPromptCaptor.getValue();
        assertThat(prompt).contains("\"k\"");
        assertThat(prompt).contains("\"v\"");
    }

    // --- fixTest tests ---

    @Test
    @DisplayName("fixTest returns LLM response stripped of fences")
    void fixTest_withError_returnsFixedCode() {
        when(llm.chat(anyString(), anyString()))
            .thenReturn("```java\nclass FixedTest {}\n```");

        String result = agent.fixTest("class Bad {}", "compilation error");

        assertThat(result).isEqualTo("class FixedTest {}");
    }

    @Test
    @DisplayName("fixTest sends system prompt with 'Fix the compilation or runtime error' context")
    void fixTest_systemPrompt() {
        when(llm.chat(anyString(), anyString())).thenReturn("ok");

        agent.fixTest("class Bad {}", "error msg");

        ArgumentCaptor<String> systemCaptor = ArgumentCaptor.forClass(String.class);
        verify(llm).chat(systemCaptor.capture(), anyString());
        assertThat(systemCaptor.getValue())
            .contains("Fix the compilation or runtime error");
    }

    @Test
    @DisplayName("fixTest includes original code and error in user prompt")
    void fixTest_includesCodeAndError() {
        when(llm.chat(anyString(), anyString())).thenReturn("ok");

        agent.fixTest("class Bad { broken }", "syntax error at line 1");

        ArgumentCaptor<String> userPromptCaptor = ArgumentCaptor.forClass(String.class);
        verify(llm).chat(anyString(), userPromptCaptor.capture());
        String prompt = userPromptCaptor.getValue();
        assertThat(prompt).contains("class Bad { broken }");
        assertThat(prompt).contains("syntax error at line 1");
        assertThat(prompt).contains("Failing test code");
    }

    @Test
    @DisplayName("fixTest with empty error message still works")
    void fixTest_emptyError_works() {
        when(llm.chat(anyString(), anyString())).thenReturn("ok");

        String result = agent.fixTest("class Bad {}", "");

        assertThat(result).isEqualTo("ok");
    }

    // --- stripCodeFences (private static, via reflection) ---

    @Test
    @DisplayName("stripCodeFences removes ```java ... ``` block")
    void stripCodeFences_removesJavaFence() throws Exception {
        Method m = TestGenAgent.class.getDeclaredMethod("stripCodeFences", String.class);
        m.setAccessible(true);
        String result = (String) m.invoke(null,
            "```java\npublic class Test {}\n```");
        assertThat(result).isEqualTo("public class Test {}");
    }

    @Test
    @DisplayName("stripCodeFences removes bare ``` block")
    void stripCodeFences_removesBareFence() throws Exception {
        Method m = TestGenAgent.class.getDeclaredMethod("stripCodeFences", String.class);
        m.setAccessible(true);
        String result = (String) m.invoke(null,
            "```\nsome code\n```");
        assertThat(result).isEqualTo("some code");
    }

    @Test
    @DisplayName("stripCodeFences returns empty string for null input")
    void stripCodeFences_null_returnsEmpty() throws Exception {
        Method m = TestGenAgent.class.getDeclaredMethod("stripCodeFences", String.class);
        m.setAccessible(true);
        String result = (String) m.invoke(null, (String) null);
        assertThat(result).isEqualTo("");
    }

    @Test
    @DisplayName("stripCodeFences returns trimmed for plain text without fences")
    void stripCodeFences_plainText_returnsTrimmed() throws Exception {
        Method m = TestGenAgent.class.getDeclaredMethod("stripCodeFences", String.class);
        m.setAccessible(true);
        String result = (String) m.invoke(null, "  package com.foo;  ");
        assertThat(result).isEqualTo("package com.foo;");
    }

    @Test
    @DisplayName("stripCodeFences handles only opening fence no closing")
    void stripCodeFences_openingFenceOnly_stripsOpening() throws Exception {
        Method m = TestGenAgent.class.getDeclaredMethod("stripCodeFences", String.class);
        m.setAccessible(true);
        String result = (String) m.invoke(null, "```\ncode here");
        assertThat(result).isEqualTo("code here");
    }

    // --- nz helper ---

    @Test
    @DisplayName("nz returns empty string for null")
    void nz_null_returnsEmpty() throws Exception {
        Method m = TestGenAgent.class.getDeclaredMethod("nz", String.class);
        m.setAccessible(true);
        String result = (String) m.invoke(null, (String) null);
        assertThat(result).isEqualTo("");
    }

    @Test
    @DisplayName("nz returns the original string when non-null")
    void nz_nonNull_returnsSame() throws Exception {
        Method m = TestGenAgent.class.getDeclaredMethod("nz", String.class);
        m.setAccessible(true);
        String result = (String) m.invoke(null, "hello");
        assertThat(result).isEqualTo("hello");
    }
}
