package com.huawei.hisi.service;

import com.huawei.hisi.neo4j.model.LogChunkNode;
import com.huawei.hisi.neo4j.repository.Neo4jLogChunkRepository;
import com.huawei.hisi.neo4j.service.EmbeddingService;
import com.huawei.hisi.repository.ErrorEmbeddingMapRepository;
import com.huawei.hisi.repository.ErrorEmbeddingMapRepository.ErrorEmbeddingMapEntity;
import com.huawei.hisi.repository.LogAnalysisRepository;
import com.huawei.hisi.repository.LogAnalysisRepository.LogAnalysisReportEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 分析流水线服务
 * 编排日志根因分析的四节点流水线
 *
 * Task 12: Analysis pipeline orchestrator
 *
 * 流水线节点：
 * 1. 解析 (parsing) - 错误信息提取
 * 2. 去重 (deduplicating) - 指纹+向量检测
 * 3. 分析 (analyzing) - LLM根因定位
 * 4. 完成 (completed) - 输出结果
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisPipelineService {

    private final LogAnalysisRepository reportRepository;
    private final FingerprintService fingerprintService;
    private final EmbeddingService embeddingService;
    private final Neo4jLogChunkRepository logChunkRepository;
    private final ErrorEmbeddingMapRepository embeddingMapRepository;
    private final CostLimiter costLimiter;

    /**
     * 执行分析流水线
     */
    public void executePipeline(Long reportId) {
        LogAnalysisReportEntity report = reportRepository.findById(reportId);
        if (report == null) {
            log.warn("报告不存在 (reportId={})", reportId);
            return;
        }

        try {
            // Step 1: Parse error
            updateStatus(reportId, "parsing", 25);
            log.info("[Pipeline] Step 1: 解析错误信息 (reportId={})", reportId);
            // Parsing is already done in FingerprintService

            // Step 2: Deduplicate
            updateStatus(reportId, "deduplicating", 50);
            log.info("[Pipeline] Step 2: 去重检测 (fingerprint={})", report.getErrorFingerprint());
            // Fingerprint deduplication is handled in submitForAnalysis

            // Step 3: Vector similarity (if not duplicate)
            if (!costLimiter.allowCall()) {
                log.warn("[Pipeline] Cost limit reached, skipping vector analysis");
                updateStatus(reportId, "completed", 100);
                return;
            }

            updateStatus(reportId, "analyzing", 75);
            log.info("[Pipeline] Step 3: 向量分析 (reportId={})", reportId);

            // Generate embedding and search for similar logs
            String logText = report.getLogMessage() + "\n" + report.getLogStackTrace();
            float[] embedding = embeddingService.generateEmbedding(logText);
            List<Double> embeddingList = toDoubleList(embedding);

            // Save LogChunk node to Neo4j
            LogChunkNode chunk = LogChunkNode.builder()
                .nodeId("log-" + reportId)
                .errorType(report.getErrorType())
                .message(report.getLogMessage())
                .stackTrace(report.getLogStackTrace())
                .fingerprint(report.getErrorFingerprint())
                .embedding(embeddingList)
                .reportId(reportId)
                .build();
            logChunkRepository.save(chunk);

            // Search for similar logs
            List<Map<String, Object>> similarLogs = logChunkRepository.findSimilarByVector(
                embeddingList, report.getSimilarityThreshold() != null ? report.getSimilarityThreshold() : 0.85, 5
            );

            // Record similarity matches
            for (Map<String, Object> similar : similarLogs) {
                ErrorEmbeddingMapEntity map = new ErrorEmbeddingMapEntity();
                map.setReportId(reportId);
                map.setEmbeddingId((String) similar.get("nodeId"));
                map.setSimilarityScore((Double) similar.get("score"));
                map.setMatchedReportId((Long) similar.get("reportId"));
                embeddingMapRepository.save(map);
            }

            // Step 4: Complete
            updateStatus(reportId, "completed", 100);
            log.info("[Pipeline] Step 4: 分析完成 (reportId={}, similarCount={})", reportId, similarLogs.size());

            // Note: LLM root cause analysis would be triggered here in future phases
            // Currently using basic analysis via existing mechanisms

        } catch (Exception e) {
            log.error("[Pipeline] 流水线执行失败 (reportId={})", reportId, e);
            updateStatus(reportId, "failed", 100);
            costLimiter.recordFailure();
        }
    }

    private void updateStatus(Long reportId, String status, int progress) {
        reportRepository.updateAnalysisStatus(reportId, status);
        log.debug("[Pipeline] 状态更新 (reportId={}, status={}, progress={})", reportId, status, progress);
    }

    private List<Double> toDoubleList(float[] embedding) {
        List<Double> list = new java.util.ArrayList<>();
        for (float f : embedding) {
            list.add((double) f);
        }
        return list;
    }
}