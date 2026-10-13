package com.huawei.hisi.knowledgegraph.link;

import com.huawei.hisi.neo4j.repository.Neo4jMethodNodeRepository;
import com.huawei.hisi.neo4j.repository.Neo4jMethodNodeRepository.OutboundHttpCall;
import com.huawei.hisi.neo4j.repository.Neo4jMethodNodeRepository.HttpEntryInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Links services via HTTP/REST call patterns (e.g., RestTemplate, WebClient, Feign).
 * Matches outbound HTTP calls to HTTP entry points by normalizing URL paths.
 */
@Component
@Order(1)
@RequiredArgsConstructor
@Slf4j
public class HttpRestLinkStrategy implements LinkStrategy {

    private final Neo4jMethodNodeRepository methodNodeRepository;

    private static final Pattern COLON_PARAM = Pattern.compile(":[^/]+");
    private static final Pattern BRACE_PARAM = Pattern.compile("\\{[^}]+}");
    private static final Pattern FLASK_PARAM = Pattern.compile("<[^>]+>");

    @Override
    public List<Map<String, Object>> link(List<String> projectPaths) {
        List<OutboundHttpCall> outbounds = methodNodeRepository.findOutboundHttpCalls(projectPaths);
        List<HttpEntryInfo> entries = methodNodeRepository.findHttpEntries(projectPaths);

        if (outbounds.isEmpty() || entries.isEmpty()) {
            log.info("[HttpRestLink] No outbound calls ({}) or entries ({}) found for projectPaths: {}",
                outbounds.size(), entries.size(), projectPaths);
            return List.of();
        }

        Map<String, List<HttpEntryInfo>> entryIndex = new HashMap<>();
        for (HttpEntryInfo entry : entries) {
            String normalizedKey = normalizeEntryKey(entry.getEntryKey());
            entryIndex.computeIfAbsent(normalizedKey, k -> new ArrayList<>()).add(entry);
        }

        List<Map<String, Object>> relations = new ArrayList<>();
        for (OutboundHttpCall outbound : outbounds) {
            String normalizedUrl = normalizeUrl(outbound.getTargetEndpoint());
            String httpMethod = outbound.getHttpMethod() != null ? outbound.getHttpMethod().toUpperCase() : "";

            String lookupKey = httpMethod + " " + normalizedUrl;
            List<HttpEntryInfo> matches = entryIndex.get(lookupKey);

            if (matches == null || matches.isEmpty()) {
                for (Map.Entry<String, List<HttpEntryInfo>> e : entryIndex.entrySet()) {
                    if (e.getKey().endsWith(" " + normalizedUrl)) {
                        matches = e.getValue();
                        break;
                    }
                }
            }

            if (matches != null) {
                for (HttpEntryInfo match : matches) {
                    if (outbound.getCallerProjectPath().equals(match.getProjectPath())) {
                        continue;
                    }
                    Map<String, Object> rel = new HashMap<>();
                    rel.put("callerId", outbound.getCallerNodeId());
                    rel.put("calleeId", match.getMethodNodeId());
                    rel.put("callType", "EXTERNAL_CALL");
                    rel.put("callLine", outbound.getCallLine() != null ? outbound.getCallLine() : 0);
                    rel.put("bridgeType", "HTTP");
                    rel.put("targetEndpoint", outbound.getTargetEndpoint());
                    relations.add(rel);
                }
            }
        }

        if (!relations.isEmpty()) {
            log.info("[HttpRestLink] Matched {} EXTERNAL_CALL edges for projectPaths: {}", relations.size(), projectPaths);
        } else {
            log.info("[HttpRestLink] No matches found for projectPaths: {}", projectPaths);
        }
        return relations;
    }

    /**
     * Normalize a URL path: replace all placeholder styles with {}, strip trailing slash, lowercase.
     */
    static String normalizeUrl(String url) {
        if (url == null || url.isEmpty()) {
            return "";
        }
        String normalized = url.toLowerCase().trim();
        if (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        normalized = FLASK_PARAM.matcher(normalized).replaceAll("{}");
        normalized = COLON_PARAM.matcher(normalized).replaceAll("{}");
        normalized = BRACE_PARAM.matcher(normalized).replaceAll("{}");
        return normalized;
    }

    /**
     * Normalize an entryKey like "GET /users/{id}" to "GET /users/{}".
     */
    static String normalizeEntryKey(String entryKey) {
        if (entryKey == null || entryKey.isEmpty()) {
            return "";
        }
        int spaceIdx = entryKey.indexOf(' ');
        if (spaceIdx < 0) {
            return normalizeUrl(entryKey);
        }
        String method = entryKey.substring(0, spaceIdx).toUpperCase();
        String path = entryKey.substring(spaceIdx + 1);
        return method + " " + normalizeUrl(path);
    }
}
