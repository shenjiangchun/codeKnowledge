package com.huawei.hisi.apm.model;

import lombok.Builder;
import lombok.Data;

/**
 * Snapshot of a managed target process's state.
 * Returned by {@link com.huawei.hisi.apm.service.TargetProcessManager}
 * and delivered via the status-change callback.
 */
@Data
@Builder
public class TargetProcessInfo {

    private String sessionId;
    private String projectPath;
    private String serviceName;
    private int targetPort;
    private long pid;

    /** LAUNCHING, READY, RUNNING, STOPPED, ERROR */
    private String status;

    /** Exit code captured when the process has terminated; null while alive. */
    private Integer exitCode;
}
