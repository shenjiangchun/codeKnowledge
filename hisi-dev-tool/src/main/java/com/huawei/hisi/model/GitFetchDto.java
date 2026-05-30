package com.huawei.hisi.model;

public class GitFetchDto {
    private String repoUrl;
    private String branch;

    // 构造函数
    public GitFetchDto() {}

    public GitFetchDto(String repoUrl, String branch) {
        this.repoUrl = repoUrl;
        this.branch = branch;
    }

    // Getters and Setters
    public String getRepoUrl() {
        return repoUrl;
    }

    public void setRepoUrl(String repoUrl) {
        this.repoUrl = repoUrl;
    }

    public String getBranch() {
        return branch;
    }

    public void setBranch(String branch) {
        this.branch = branch;
    }
}