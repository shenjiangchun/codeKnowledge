package com.huawei.hisi.mergeanalysis.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

/**
 * 需要能被 Jackson 反序列化：节点重跑时上游输出从事件表读回，是 {@code Map} 而非内存对象，
 * {@code ImpactAnalysisDagNode} 会 {@code convertValue(map, DiffResult.class)}。
 * {@code @Builder} 会隐藏默认构造器，所以这里显式补回无参/全参构造器。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DiffResult {
    private String sourceBranch;
    private String targetBranch;
    private int totalFiles;
    private int totalAdditions;
    private int totalDeletions;
    private List<FileDiff> files;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FileDiff {
        private String filePath;
        private String changeType; // ADD, MODIFY, DELETE, RENAME
        private int additions;
        private int deletions;
        private String patch; // unified diff text
    }
}
