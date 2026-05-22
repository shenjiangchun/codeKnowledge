package com.huawei.hisi.ram.registry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
public class AgentRegistry {

    private final String classpathResource;
    private Map<String, AgentManifest> manifests = Collections.emptyMap();

    public AgentRegistry(@Value("${ram.registry.path:ram/agents.yaml}") String classpathResource) {
        this.classpathResource = classpathResource;
    }

    private AgentRegistry(List<AgentManifest> agents) {
        this.classpathResource = null;
        this.manifests = indexById(agents);
    }

    @PostConstruct
    void init() {
        if (classpathResource != null) {
            this.manifests = loadFromClasspath(classpathResource);
        }
    }

    public static AgentRegistry fromYaml(String classpathResource) {
        AgentRegistry r = new AgentRegistry(List.of());
        r.manifests = loadFromClasspath(classpathResource);
        return r;
    }

    private static Map<String, AgentManifest> loadFromClasspath(String classpathResource) {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(classpathResource)) {
            if (in == null) {
                throw new IllegalStateException("Agent manifest not found on classpath: " + classpathResource);
            }
            AgentManifestFile file = mapper.readValue(in, AgentManifestFile.class);
            List<AgentManifest> agents = file.getAgents() == null ? List.of() : file.getAgents();
            return indexById(agents);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load agent manifest: " + classpathResource, e);
        }
    }

    private static Map<String, AgentManifest> indexById(List<AgentManifest> agents) {
        Map<String, AgentManifest> map = new LinkedHashMap<>();
        for (AgentManifest a : agents) {
            map.put(a.getAgentId(), a);
        }
        return Collections.unmodifiableMap(map);
    }

    public Optional<AgentManifest> findById(String agentId) {
        return Optional.ofNullable(manifests.get(agentId));
    }

    public List<AgentManifest> findByTag(String tag) {
        return manifests.values().stream()
                .filter(m -> m.getTags() != null && m.getTags().contains(tag))
                .collect(Collectors.toList());
    }

    public List<AgentManifest> findByCapability(String capability) {
        return manifests.values().stream()
                .filter(m -> m.getCapabilities() != null && m.getCapabilities().contains(capability))
                .collect(Collectors.toList());
    }

    public Collection<AgentManifest> all() {
        return manifests.values();
    }

    private static class AgentManifestFile {
        private List<AgentManifest> agents;

        public List<AgentManifest> getAgents() {
            return agents;
        }

        public void setAgents(List<AgentManifest> agents) {
            this.agents = agents;
        }
    }
}
