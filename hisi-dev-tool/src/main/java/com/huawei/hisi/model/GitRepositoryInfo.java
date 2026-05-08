package com.huawei.hisi.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Git repository information DTO
 * Represents a scanned or cloned git repository
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GitRepositoryInfo {

    /** Repository name (folder name) */
    private String name;

    /** Full path to repository */
    private String path;

    /** Current branch name */
    private String branch;

    /** Remote URL (if configured) */
    private String remoteUrl;

    /** Whether working tree is clean */
    private boolean clean;

    /** Source: "scanned" or "cloned" */
    private String source;

    /** Last commit message (optional) */
    private String lastCommitMessage;

    /** Last commit date (optional) */
    private String lastCommitDate;
}