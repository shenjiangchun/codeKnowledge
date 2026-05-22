package com.huawei.hisi.ram.registry;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class AgentRegistryTest {

    private static AgentRegistry registry;

    @BeforeAll
    static void setUp() {
        registry = AgentRegistry.fromYaml("ram/agents.yaml");
    }

    @Test
    void registry_loadsAll4AgentsFromYaml() {
        assertEquals(4, registry.all().size());
    }

    @Test
    void registry_findById_returnsManifest() {
        Optional<AgentManifest> opt = registry.findById("impact-v1");
        assertTrue(opt.isPresent());
        assertEquals("claude-opus-4-6", opt.get().getModel().getPreferred());
    }

    @Test
    void registry_findByTag_planning_returnsClarifyAndImplement() {
        List<AgentManifest> result = registry.findByTag("planning");
        assertEquals(2, result.size());
        List<String> ids = result.stream().map(AgentManifest::getAgentId).sorted().toList();
        assertEquals(List.of("clarify-v1", "implement-v1"), ids);
    }

    @Test
    void registry_findByCapability_knowledgeGraph_returnsImpact() {
        List<AgentManifest> result = registry.findByCapability("knowledge-graph");
        assertEquals(1, result.size());
        assertEquals("impact-v1", result.get(0).getAgentId());
    }
}
