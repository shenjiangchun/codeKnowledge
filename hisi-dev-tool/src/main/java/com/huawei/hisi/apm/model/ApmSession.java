package com.huawei.hisi.apm.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents an APM profiling session — one session per target JVM launch.
 * <p>
 * Lifecycle states:
 * CREATED → LAUNCHING → READY → RUNNING → COMPLETED | ERROR
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApmSession {

    /** Unique session identifier (UUID). */
    private String id;

    /** Absolute path of the project being profiled. */
    private String projectPath;

    /** Service name reported by the OTel agent. */
    private String serviceName;

    /** HTTP port the target application listens on. */
    private int targetPort;

    /** Current session status: CREATED, LAUNCHING, READY, RUNNING, COMPLETED, ERROR. */
    private String status;

    /** Epoch millis when the session was created. */
    private long createdAt;

    /** Epoch millis when the session finished (null while still active). */
    private Long finishedAt;
}
