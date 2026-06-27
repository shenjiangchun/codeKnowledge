// hisi-dev-tool/src/test/java/com/huawei/hisi/ram/phase2v2/DynamicToolRegistryTest.java
package com.huawei.hisi.ram.phase2v2;

import com.huawei.hisi.ram.phase2v2.model.ChainComplexity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DynamicToolRegistryTest {

    @Test
    void getToolsForSimpleChain_returnsMinimalSet() {
        DynamicToolRegistry registry = new DynamicToolRegistry();
        List<String> tools = registry.getTools(ChainComplexity.SIMPLE);

        assertThat(tools).containsExactlyInAnyOrder(
            "KG_MCP", "Read", "Grep", "Glob", "Artifacts"
        );
    }

    @Test
    void getToolsForCrossService_addsWebFetch() {
        DynamicToolRegistry registry = new DynamicToolRegistry();
        List<String> tools = registry.getTools(ChainComplexity.CROSS_SERVICE);

        assertThat(tools).contains("WebFetch");
        assertThat(tools).hasSize(6); // SIMPLE + WebFetch
    }

    @Test
    void getToolsForDomainAnalysis_addsBash() {
        DynamicToolRegistry registry = new DynamicToolRegistry();
        List<String> tools = registry.getTools(ChainComplexity.DOMAIN_ANALYSIS);

        assertThat(tools).contains("Bash");
        assertThat(tools).hasSize(7); // CROSS_SERVICE + Bash
    }

    @Test
    void getToolsForVerification_addsAgent() {
        DynamicToolRegistry registry = new DynamicToolRegistry();
        List<String> tools = registry.getTools(ChainComplexity.VERIFICATION);

        assertThat(tools).contains("Agent");
        assertThat(tools).hasSize(8); // DOMAIN_ANALYSIS + Agent
    }

    @Test
    void simpleChain_doesNotContainHigherLevelTools() {
        DynamicToolRegistry registry = new DynamicToolRegistry();
        List<String> tools = registry.getTools(ChainComplexity.SIMPLE);

        assertThat(tools).doesNotContain("WebFetch", "Bash", "Agent");
    }

    @Test
    void crossService_doesNotContainBashOrAgent() {
        DynamicToolRegistry registry = new DynamicToolRegistry();
        List<String> tools = registry.getTools(ChainComplexity.CROSS_SERVICE);

        assertThat(tools).doesNotContain("Bash", "Agent");
    }

    @Test
    void domainAnalysis_doesNotContainAgent() {
        DynamicToolRegistry registry = new DynamicToolRegistry();
        List<String> tools = registry.getTools(ChainComplexity.DOMAIN_ANALYSIS);

        assertThat(tools).doesNotContain("Agent");
    }
}