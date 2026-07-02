package com.huawei.hisi.ram.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

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
}
