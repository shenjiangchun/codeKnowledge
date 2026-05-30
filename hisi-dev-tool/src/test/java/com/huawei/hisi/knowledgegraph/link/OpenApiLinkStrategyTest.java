package com.huawei.hisi.knowledgegraph.link;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatNoException;

class OpenApiLinkStrategyTest {

    @Test
    @DisplayName("link completes without error (placeholder implementation)")
    void link_logsAndCompletes() {
        var strategy = new OpenApiLinkStrategy();
        assertThatNoException().isThrownBy(() -> strategy.link(List.of("/workspace/monorepo")));
    }
}
