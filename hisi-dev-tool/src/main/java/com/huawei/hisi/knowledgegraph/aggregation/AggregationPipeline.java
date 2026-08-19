package com.huawei.hisi.knowledgegraph.aggregation;

import com.huawei.hisi.knowledgegraph.aggregation.stage.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * 聚合管道编排器
 * 按顺序执行 6 个 Stage，每个 Stage 独立 checkpoint + 容错降级。
 * 某 Stage 失败不影响已有 Stage 的结果，也不阻断构建主流程。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AggregationPipeline {

    private final ModuleStatsAggregator moduleStatsAggregator;
    private final HotspotScorer hotspotScorer;
    private final ChurnCollector churnCollector;
    private final MultiDimensionCommunityDetector communityDetector;
    private final DomainNameGenerator domainNameGenerator;
    private final BuildModuleDependencyAggregator buildModuleDependencyAggregator;
    private final FreeLayerRoleResolver freeLayerRoleResolver;
    private final AggregationCheckpointManager checkpointManager;

    /**
     * 运行聚合管道
     *
     * @param projectPath     项目路径
     * @param mode            FULL 或 INCREMENTAL
     * @param rebuiltNodeIds  增量构建中重建的 nodeId 集合（FULL 模式下为 null）
     * @param changedFiles    增量构建中变更的文件路径列表（FULL 模式下为 null）
     */
    public void run(String projectPath, String mode,
                    Set<String> rebuiltNodeIds, List<String> changedFiles) {
        boolean isFull = "FULL".equals(mode);
        log.info("[Aggregation] 开始, projectPath={}, mode={}", projectPath, mode);

        // 从 rebuiltNodeIds 推导脏模块集合
        Set<String> dirtyPackageNames = isFull ? null : deriveDirtyPackages(projectPath, rebuiltNodeIds);

        // Stage 1: ModuleStats + DSM
        try {
            moduleStatsAggregator.aggregate(projectPath, dirtyPackageNames);
            checkpointManager.markSuccess(projectPath, "ModuleStats", String.valueOf(System.currentTimeMillis()));
        } catch (Exception e) {
            log.error("[Aggregation] Stage=ModuleStats 失败: {}", e.getMessage());
            checkpointManager.markFailed(projectPath, "ModuleStats", e.getMessage());
            return; // 后续 Stage 都依赖模块数据，无法继续
        }

        // Stage 2: 构建模块级依赖落库（不依赖 ModuleStats，失败不阻断）
        log.info("[Aggregation] → Stage=BuildModule 开始, projectPath={}", projectPath);
        try {
            buildModuleDependencyAggregator.aggregate(projectPath);
            log.info("[Aggregation] Stage=BuildModule 完成, projectPath={}", projectPath);
        } catch (Exception e) {
            log.error("[Aggregation] Stage=BuildModule 异常: {}", e.getMessage(), e);
        }

        // Stage 3: 热点风险评分（依赖 ModuleStats 的 inDegree）
        log.info("[Aggregation] → Stage=Hotspot 开始, projectPath={}, isFull={}",
            projectPath, isFull);
        try {
            hotspotScorer.score(projectPath, isFull ? null : deriveDirtyFilePaths(changedFiles));
            log.info("[Aggregation] Stage=Hotspot 完成, projectPath={}", projectPath);
        } catch (Exception e) {
            log.error("[Aggregation] Stage=Hotspot 异常: {}", e.getMessage(), e);
        }

        // Stage 4: Churn 变更频率
        log.info("[Aggregation] → Stage=Churn 开始, projectPath={}, changedFiles={}",
            projectPath, changedFiles != null ? changedFiles.size() : 0);
        try {
            churnCollector.collect(projectPath, changedFiles);
            log.info("[Aggregation] Stage=Churn 完成, projectPath={}", projectPath);
        } catch (Exception e) {
            log.error("[Aggregation] Stage=Churn 异常: {}", e.getMessage(), e);
        }

        // Stage 5: 社区检测
        log.info("[Aggregation] → Stage=Community 开始, projectPath={}", projectPath);
        try {
            communityDetector.detect(projectPath);
            log.info("[Aggregation] Stage=Community 完成, projectPath={}", projectPath);
        } catch (Exception e) {
            log.error("[Aggregation] Stage=Community 异常: {}", e.getMessage(), e);
            checkpointManager.markFailed(projectPath, "Community", e.getMessage());
            log.info("[Aggregation] 结束（部分完成）, projectPath={}", projectPath);
            return;
        }

        // Stage 6: LLM 领域命名
        log.info("[Aggregation] → Stage=DomainName 开始, projectPath={}", projectPath);
        try {
            boolean onlyIfDrifted = !isFull;
            domainNameGenerator.generate(projectPath, onlyIfDrifted);
            log.info("[Aggregation] Stage=DomainName 完成, projectPath={}", projectPath);
        } catch (Exception e) {
            log.error("[Aggregation] Stage=DomainName 异常: {}", e.getMessage(), e);
        }

        // Stage 7: LLM 补全游离节点层级（类级 + 包级）
        log.info("[Aggregation] → Stage=FreeLayerRole 开始, projectPath={}", projectPath);
        try {
            int resolved = freeLayerRoleResolver.resolve(projectPath);
            log.info("[Aggregation] Stage=FreeLayerRole 完成, resolved={}", resolved);
        } catch (Exception e) {
            log.error("[Aggregation] Stage=FreeLayerRole 异常: {}", e.getMessage(), e);
        }

        log.info("[Aggregation] 全部完成, projectPath={}", projectPath);
    }

    private Set<String> deriveDirtyPackages(String projectPath, Set<String> rebuiltNodeIds) {
        if (rebuiltNodeIds == null || rebuiltNodeIds.isEmpty()) return Set.of();
        // 从 nodeId 格式 "className.methodName.hash" 提取类名 → 包名
        return rebuiltNodeIds.stream()
            .map(id -> id.contains(".") ? id.substring(0, id.lastIndexOf('.')) : id)
            .map(cls -> cls.contains(".") ? cls.substring(0, cls.lastIndexOf('.')) : cls)
            .collect(java.util.stream.Collectors.toSet());
    }

    private Set<String> deriveDirtyFilePaths(List<String> changedFiles) {
        if (changedFiles == null) return Set.of();
        return Set.copyOf(changedFiles);
    }
}
