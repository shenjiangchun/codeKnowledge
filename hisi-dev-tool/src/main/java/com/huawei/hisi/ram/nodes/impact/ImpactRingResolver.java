package com.huawei.hisi.ram.nodes.impact;

import com.huawei.hisi.ram.kg.KgMcpClient;
import com.huawei.hisi.ram.kg.dto.Bridge;
import com.huawei.hisi.ram.kg.dto.Entry;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Resolves the outer {@link ImpactRing} (upstream/downstream/bridges/cross-service). */
@Component
public class ImpactRingResolver {

    private static final int DOWNSTREAM_DEPTH = 3;

    private final KgMcpClient kg;

    public ImpactRingResolver(KgMcpClient kg) {
        this.kg = kg;
    }

    public ImpactRing resolve(ModifiedRing modified, String projectPath) {
        if (modified == null) {
            return new ImpactRing(List.of(), List.of(), List.of(), List.of());
        }
        List<Entry> upstream = new ArrayList<>();
        List<Entry> downstream = new ArrayList<>();
        List<Bridge> bridges = new ArrayList<>();

        for (String nodeId : modified.allNodeIds()) {
            if (nodeId == null) continue;
            ClassMethod cm = parseClassMethod(nodeId);
            if (cm != null) {
                List<Entry> aff = kg.affecting(cm.className(), cm.methodName(), projectPath);
                if (aff != null) upstream.addAll(aff);
            }
            List<Entry> down = kg.downstream(nodeId, projectPath, DOWNSTREAM_DEPTH);
            if (down != null) downstream.addAll(down);
            List<Bridge> br = kg.bridges(nodeId, projectPath);
            if (br != null) bridges.addAll(br);
        }

        // Cross-service expansion via Feign / MQ chains.
        Set<String> feignTargets = new LinkedHashSet<>();
        Set<String> mqTopics = new LinkedHashSet<>();
        for (Bridge b : bridges) {
            if (b == null || b.bridgeType() == null) continue;
            String type = b.bridgeType().toUpperCase();
            if (type.contains("FEIGN") && b.target() != null) feignTargets.add(b.target());
            if (type.contains("MQ") && b.target() != null) mqTopics.add(b.target());
        }
        List<Bridge> crossService = new ArrayList<>();
        for (String svc : feignTargets) {
            List<Bridge> chain = kg.feignChain(svc, projectPath);
            if (chain != null) crossService.addAll(chain);
        }
        for (String topic : mqTopics) {
            List<Bridge> chain = kg.mqChain(topic, projectPath);
            if (chain != null) crossService.addAll(chain);
        }
        return new ImpactRing(upstream, downstream, crossService, bridges);
    }

    /**
     * Best-effort parse of a {@code com.foo.Bar#method} or {@code com.foo.Bar.method}
     * style nodeId into class and method components. Returns {@code null} when not parseable.
     */
    static ClassMethod parseClassMethod(String nodeId) {
        if (nodeId == null) return null;
        int hash = nodeId.indexOf('#');
        if (hash > 0 && hash < nodeId.length() - 1) {
            String cls = nodeId.substring(0, hash);
            String mth = nodeId.substring(hash + 1);
            int paren = mth.indexOf('(');
            if (paren > 0) mth = mth.substring(0, paren);
            return new ClassMethod(cls, mth);
        }
        int lastDot = nodeId.lastIndexOf('.');
        if (lastDot > 0 && lastDot < nodeId.length() - 1) {
            String cls = nodeId.substring(0, lastDot);
            String mth = nodeId.substring(lastDot + 1);
            if (cls.contains(".")) {
                return new ClassMethod(cls, mth);
            }
        }
        return null;
    }

    record ClassMethod(String className, String methodName) {}
}
