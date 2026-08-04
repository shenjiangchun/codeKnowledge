package com.huawei.hisi.knowledgegraph.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Unified generation task model.
 * Maps to the "generation_task" SQLite table.
 * Supports both KG and VECTOR task types.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GenerationTask {

    private Long id;
    private String taskType;       // "KG" or "VECTOR"
    private String projectPath;
    private String status;         // PENDING, RUNNING, COMPLETED, FAILED
    private Integer progress;      // processed count
    private Integer totalCount;
    private Integer successCount;
    private Integer failCount;
    private String errorMessage;
    private Long startedAt;        // epoch seconds
    private Long finishedAt;       // epoch seconds
    private Long createdAt;        // epoch seconds
}
