package com.huawei.hisi.ram.sdk;

import com.huawei.hisi.ram.config.ChatModelProperties;
import com.huawei.hisi.ram.config.ChatModelProperties.ModelSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("SendOptions.forScenario unit tests")
class SendOptionsTest {

    private static ChatModelProperties propsWithGlm51Chat4096() {
        ChatModelProperties props = new ChatModelProperties();
        ModelSpec spec = new ModelSpec();
        spec.setScenarioMaxTokens(Map.of("chat", 4096));
        props.setModels(Map.of("glm-5.1", spec));
        return props;
    }

    @Test
    @DisplayName("forScenario('glm-5.1', 'chat') returns model=glm-5.1 and maxTokens=4096")
    void forScenario_chat_returns4096() {
        ChatModelProperties props = propsWithGlm51Chat4096();

        SendOptions opts = SendOptions.forScenario(props, "glm-5.1", "chat");

        assertThat(opts.model()).isEqualTo("glm-5.1");
        assertThat(opts.maxTokens()).isEqualTo(4096);
    }

    @Test
    @DisplayName("forScenario throws when model id is unknown")
    void forScenario_unknownModel_throws() {
        ChatModelProperties props = propsWithGlm51Chat4096();

        assertThatThrownBy(() -> SendOptions.forScenario(props, "does-not-exist", "chat"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("unknown model");
    }

    @Test
    @DisplayName("forScenario throws when scenario is unknown")
    void forScenario_unknownScenario_throws() {
        ChatModelProperties props = propsWithGlm51Chat4096();

        assertThatThrownBy(() -> SendOptions.forScenario(props, "glm-5.1", "no-such-scenario"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("unknown scenario");
    }
}
