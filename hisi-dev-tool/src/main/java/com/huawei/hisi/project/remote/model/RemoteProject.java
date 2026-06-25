package com.huawei.hisi.project.remote.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RemoteProject {
    private Long id;
    private String name;
    private String gitUrl;
    private String username;
    private String encryptedPassword;
    @Builder.Default
    private String branch = "main";
    private String localPath;
    @Builder.Default
    private String cloneStatus = "PENDING";
    private String cloneError;
    private Long lastSyncAt;
    private Long createdAt;

    // Auth type fields
    @Builder.Default
    private String authType = "PASSWORD"; // PASSWORD / SSH_KEY / TOKEN
    private String sshKeyPath; // SSH private key file path
    private String encryptedToken; // AES encrypted token

    // Group association
    private Long groupId;
}
