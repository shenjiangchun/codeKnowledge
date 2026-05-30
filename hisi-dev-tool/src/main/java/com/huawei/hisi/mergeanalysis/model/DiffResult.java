package com.huawei.hisi.mergeanalysis.model;

import lombok.Builder;
import lombok.Data;
import java.util.List;

@Data
@Builder
public class DiffResult {
    private String sourceBranch;
    private String targetBranch;
    private int totalFiles;
    private int totalAdditions;
    private int totalDeletions;
    private List<FileDiff> files;

    @Data
    @Builder
    public static class FileDiff {
        private String filePath;
        private String changeType; // ADD, MODIFY, DELETE, RENAME
        private int additions;
        private int deletions;
        private String patch; // unified diff text
    }
}
