package com.huawei.hisi.service.impact.model;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Change request model for impact prediction analysis.
 * Represents a code change request that needs impact analysis.
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChangeRequest {

    /**
     * Full qualified class name that contains the change
     */
    @NotBlank(message = "Class name cannot be empty")
    private String className;

    /**
     * Method name that is being changed
     */
    @NotBlank(message = "Method name cannot be empty")
    private String methodName;

    /**
     * Project identifier
     */
    private String projectId;

    /**
     * Change type: ADD/MODIFY/DELETE/REFACTOR
     */
    @Builder.Default
    private ChangeType changeType = ChangeType.MODIFY;

    /**
     * Description of the change
     */
    private String description;

    /**
     * File path relative to project root
     */
    private String filePath;

    /**
     * Start line number of the change
     */
    private Integer startLine;

    /**
     * End line number of the change
     */
    private Integer endLine;

    /**
     * Original code snippet (before change)
     */
    private String originalCode;

    /**
     * New code snippet (after change)
     */
    private String newCode;

    /**
     * Additional context for analysis
     */
    private List<String> context;

    /**
     * Change type enumeration
     */
    public enum ChangeType {
        /**
         * New method/class added
         */
        ADD,

        /**
         * Existing method/class modified
         */
        MODIFY,

        /**
         * Method/class deleted
         */
        DELETE,

        /**
         * Method/class refactored (name change, signature change)
         */
        REFACTOR
    }
}