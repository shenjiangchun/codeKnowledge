package com.huawei.hisi.knowledgegraph.link;

import com.huawei.hisi.neo4j.repository.Neo4jMethodNodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
@Order(2)
@RequiredArgsConstructor
@Slf4j
public class MqLinkStrategy implements LinkStrategy {

    private static final String MQ_PREFIX = "MQ:";
    private final Neo4jMethodNodeRepository methodNodeRepository;

    @Override
    public void link(List<String> projectPaths) {
        List<Neo4jMethodNodeRepository.MqProducerCall> producers =
            methodNodeRepository.findMqProducerCalls(projectPaths);
        List<Neo4jMethodNodeRepository.MqConsumerEntry> consumers =
            methodNodeRepository.findMqConsumerEntries(projectPaths);

        if (producers.isEmpty() || consumers.isEmpty()) {
            log.info("[MqLink] No producers ({}) or consumers ({}) for projectPaths: {}",
                producers.size(), consumers.size(), projectPaths);
            return;
        }

        // Build index: normalized topic -> list of consumers
        Map<String, List<Neo4jMethodNodeRepository.MqConsumerEntry>> consumerIndex = new HashMap<>();
        for (var consumer : consumers) {
            String topic = normalizeTopic(consumer.getEntryKey());
            consumerIndex.computeIfAbsent(topic, k -> new ArrayList<>()).add(consumer);
        }

        // Match producers to consumers
        List<Map<String, Object>> relations = new ArrayList<>();
        for (var producer : producers) {
            String topic = producer.getTopic();
            if (topic == null || topic.isEmpty()) {
                continue;
            }

            List<Neo4jMethodNodeRepository.MqConsumerEntry> matches = consumerIndex.get(topic);
            if (matches == null) {
                continue;
            }

            for (var match : matches) {
                // Skip same-service
                if (producer.getCallerProjectPath().equals(match.getProjectPath())) {
                    continue;
                }

                Map<String, Object> rel = new HashMap<>();
                rel.put("callerId", producer.getCallerNodeId());
                rel.put("calleeId", match.getMethodNodeId());
                rel.put("callType", "EXTERNAL_CALL");
                rel.put("callLine", producer.getCallLine() != null ? producer.getCallLine() : 0);
                rel.put("bridgeType", "MQ");
                rel.put("targetEndpoint", topic);
                relations.add(rel);
            }
        }

        if (!relations.isEmpty()) {
            methodNodeRepository.createCallRelations(relations);
            log.info("[MqLink] Created {} EXTERNAL_CALL edges for projectPaths: {}",
                relations.size(), projectPaths);
        } else {
            log.info("[MqLink] No matches found for projectPaths: {}", projectPaths);
        }
    }

    /**
     * Normalize topic from MQ entryKey.
     * EntryKey format: "MQ:className.methodName:topic"
     * We need to extract just the topic part.
     */
    static String normalizeTopic(String entryKey) {
        if (entryKey == null || entryKey.isEmpty()) {
            return "";
        }
        // Strip "MQ:" prefix
        String stripped = entryKey.startsWith(MQ_PREFIX)
                ? entryKey.substring(MQ_PREFIX.length())
                : entryKey;
        // Extract topic: everything after the last colon
        // Format after stripping: "className.methodName:topic"
        int lastColon = stripped.lastIndexOf(':');
        if (lastColon >= 0 && lastColon < stripped.length() - 1) {
            return stripped.substring(lastColon + 1);
        }
        // Fallback: return stripped value (no colon found, treat entire string as topic)
        return stripped;
    }
}
