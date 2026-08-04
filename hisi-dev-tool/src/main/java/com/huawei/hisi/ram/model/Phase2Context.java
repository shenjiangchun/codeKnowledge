package com.huawei.hisi.ram.model;

import com.huawei.hisi.ram.kg.dto.Bridge;
import com.huawei.hisi.ram.kg.dto.CallTreeNode;
import com.huawei.hisi.ram.kg.dto.Entry;
import com.huawei.hisi.ram.kg.dto.MethodBodyInfo;
import com.huawei.hisi.ram.kg.dto.Seed;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Context object for phase 2 deep analysis.
 * Holds all KG data collected for a user's follow-up question.
 *
 * <p>Phase 2 analysis provides deeper, question-specific investigation
 * based on initial project overview results.</p>
 */
public final class Phase2Context {

    private final String projectPath;
    private final String question;
    private final List<String> keywords;
    private final DomainHint domainHint;
    private final List<Seed> coreMethods;
    private final List<Entry> upstreamChains;  // affecting() returns List<Entry> (flat caller list)
    private final List<CallTreeNode> downstreamChains;  // calleesTree() returns CallTreeNode (tree structure)
    private final List<Entry> rootEntries;
    private final List<MethodBodyInfo> methodBodies;
    private final List<Bridge> bridgePoints;

    private Phase2Context(Builder builder) {
        this.projectPath = builder.projectPath;
        this.question = builder.question;
        this.keywords = Collections.unmodifiableList(new ArrayList<>(builder.keywords));
        this.domainHint = builder.domainHint != null ? builder.domainHint : DomainHint.inferDomain(question);
        this.coreMethods = Collections.unmodifiableList(new ArrayList<>(builder.coreMethods));
        this.upstreamChains = Collections.unmodifiableList(new ArrayList<>(builder.upstreamChains));
        this.downstreamChains = Collections.unmodifiableList(new ArrayList<>(builder.downstreamChains));
        this.rootEntries = Collections.unmodifiableList(new ArrayList<>(builder.rootEntries));
        this.methodBodies = Collections.unmodifiableList(new ArrayList<>(builder.methodBodies));
        this.bridgePoints = Collections.unmodifiableList(new ArrayList<>(builder.bridgePoints));
    }

    public String projectPath() {
        return projectPath;
    }

    public String question() {
        return question;
    }

    public List<String> keywords() {
        return keywords;
    }

    public DomainHint domainHint() {
        return domainHint;
    }

    public List<Seed> coreMethods() {
        return coreMethods;
    }

    public List<Entry> upstreamChains() {
        return upstreamChains;
    }

    public List<CallTreeNode> downstreamChains() {
        return downstreamChains;
    }

    public List<Entry> rootEntries() {
        return rootEntries;
    }

    public List<MethodBodyInfo> methodBodies() {
        return methodBodies;
    }

    public List<Bridge> bridgePoints() {
        return bridgePoints;
    }

    /**
     * Create a new builder with required fields.
     */
    public static Builder builder(String projectPath, String question) {
        return new Builder(projectPath, question);
    }

    /**
     * Builder for Phase2Context.
     */
    public static class Builder {
        private final String projectPath;
        private final String question;
        private List<String> keywords = List.of();
        private DomainHint domainHint;
        private List<Seed> coreMethods = List.of();
        private List<Entry> upstreamChains = List.of();
        private List<CallTreeNode> downstreamChains = List.of();
        private List<Entry> rootEntries = List.of();
        private List<MethodBodyInfo> methodBodies = List.of();
        private List<Bridge> bridgePoints = List.of();

        public Builder(String projectPath, String question) {
            this.projectPath = projectPath;
            this.question = question;
        }

        public Builder keywords(List<String> keywords) {
            this.keywords = keywords != null ? keywords : List.of();
            return this;
        }

        public Builder domainHint(DomainHint domainHint) {
            this.domainHint = domainHint;
            return this;
        }

        public Builder coreMethods(List<Seed> coreMethods) {
            this.coreMethods = coreMethods != null ? coreMethods : List.of();
            return this;
        }

        public Builder upstreamChains(List<Entry> upstreamChains) {
            this.upstreamChains = upstreamChains != null ? upstreamChains : List.of();
            return this;
        }

        public Builder downstreamChains(List<CallTreeNode> downstreamChains) {
            this.downstreamChains = downstreamChains != null ? downstreamChains : List.of();
            return this;
        }

        public Builder rootEntries(List<Entry> rootEntries) {
            this.rootEntries = rootEntries != null ? rootEntries : List.of();
            return this;
        }

        public Builder methodBodies(List<MethodBodyInfo> methodBodies) {
            this.methodBodies = methodBodies != null ? methodBodies : List.of();
            return this;
        }

        public Builder bridgePoints(List<Bridge> bridgePoints) {
            this.bridgePoints = bridgePoints != null ? bridgePoints : List.of();
            return this;
        }

        public Phase2Context build() {
            return new Phase2Context(this);
        }
    }
}