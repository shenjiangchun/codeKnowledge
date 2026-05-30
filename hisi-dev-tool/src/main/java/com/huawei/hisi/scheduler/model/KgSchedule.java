package com.huawei.hisi.scheduler.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KgSchedule {
    private Long id;
    private String projectPath;
    private String cronExpression;
    private String taskType; // FULL or INCREMENTAL
    @Builder.Default
    private boolean enabled = true;
    private Long lastRunAt;
    private Long nextRunAt;
    private Long createdAt;
}
