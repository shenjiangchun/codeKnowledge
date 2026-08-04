package com.huawei.hisi.apm.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Persisted APM test case — stores request configuration for replay.
 * <p>
 * Each test case is bound to a project path and optionally to a specific
 * KG entry point node. Headers, params and body are stored as JSON strings.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApmTestCase {

    /** Auto-incremented primary key. */
    private Long id;

    /** Human-readable name chosen by the user. */
    private String name;

    /** Absolute project path this test case belongs to. */
    private String projectPath;

    /** Optional KG entry point node ID. */
    private String entryNodeId;

    /** HTTP method: GET, POST, PUT, DELETE, PATCH. */
    private String method;

    /** Request URL path (e.g., /api/users/{id}). */
    private String url;

    /** JSON-encoded request headers map. */
    private String headers;

    /** JSON-encoded query/path parameter list. */
    private String params;

    /** Raw request body string. */
    private String body;

    /** Epoch seconds when created. */
    private Long createdAt;

    /** Epoch seconds when last updated. */
    private Long updatedAt;
}
