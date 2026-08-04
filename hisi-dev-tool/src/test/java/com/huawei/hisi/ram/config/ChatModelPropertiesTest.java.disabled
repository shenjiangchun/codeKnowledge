package com.huawei.hisi.ram.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:target/chat-model-props-test.db",
        "spring.datasource.hikari.maximum-pool-size=1"
})
@ActiveProfiles("test")
class ChatModelPropertiesTest {

    @Autowired
    ChatModelProperties props;

    @Test
    @DisplayName("chat-models.yml loads capability metadata")
    void loadsCapabilityMetadata() {
        var glm = props.getModels().get("glm-5.1");
        assertThat(glm).isNotNull();
        assertThat(glm.getMaxContext()).isEqualTo(202_752);
        assertThat(glm.getScenarioMaxTokens().get("chat")).isEqualTo(4096);
        assertThat(glm.getScenarioMaxTokens().get("summary")).isEqualTo(2048);
        assertThat(glm.getScenarioMaxTokens().get("long-form")).isEqualTo(8192);
    }

    @Test
    @DisplayName("defaultModelId returns first declared model when chat.default-model is unset")
    void defaultModelId_returnsFirstModel_whenNoExplicitDefault() {
        ChatModelProperties p = new ChatModelProperties();
        ChatModelProperties.ModelSpec spec = new ChatModelProperties.ModelSpec();
        // Use LinkedHashMap to make iteration order deterministic in the test.
        LinkedHashMap<String, ChatModelProperties.ModelSpec> models = new LinkedHashMap<>();
        models.put("glm-5.1", spec);
        models.put("glm-4-flash", new ChatModelProperties.ModelSpec());
        p.setModels(models);
        assertThat(p.defaultModelId()).isEqualTo("glm-5.1");
    }

    @Test
    @DisplayName("defaultModelId honors chat.default-model override")
    void defaultModelId_honorsExplicitDefault() {
        ChatModelProperties p = new ChatModelProperties();
        p.setDefaultModel("glm-4-flash");
        ChatModelProperties.ModelSpec spec = new ChatModelProperties.ModelSpec();
        p.setModels(Map.of("glm-5.1", spec, "glm-4-flash", new ChatModelProperties.ModelSpec()));
        assertThat(p.defaultModelId()).isEqualTo("glm-4-flash");
    }

    @Test
    @DisplayName("defaultModelId falls back to glm-5.1 when config is empty")
    void defaultModelId_fallsBackToLegacyConstant() {
        ChatModelProperties p = new ChatModelProperties();
        p.setModels(Map.of());
        assertThat(p.defaultModelId()).isEqualTo("glm-5.1");
    }
}
