package com.huawei.hisi.service.impact;

import com.huawei.hisi.service.impact.model.ChangeRequest;

import java.nio.file.Path;
import java.util.List;

/**
 * Change parser interface for parsing code changes.
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
public interface ChangeParser {

    /**
     * Parse a file change and extract change details.
     *
     * @param filePath path to the changed file
     * @param originalContent original file content (before change)
     * @param newContent new file content (after change)
     * @return list of parsed change requests
     */
    List<ChangeRequest> parseFileChange(Path filePath, String originalContent, String newContent);

    /**
     * Parse method changes within a file.
     *
     * @param filePath path to the file
     * @param content file content
     * @param className full qualified class name
     * @return list of method changes detected
     */
    List<MethodChange> parseMethodChanges(Path filePath, String content, String className);

    /**
     * Parse a single method change.
     *
     * @param className full qualified class name
     * @param methodName method name
     * @param originalCode original method code
     * @param newCode new method code
     * @return parsed change request
     */
    ChangeRequest parseMethodChange(String className, String methodName, String originalCode, String newCode);

    /**
     * Get change type based on diff analysis.
     *
     * @param original original content
     * @param newContent new content
     * @return detected change type
     */
    ChangeRequest.ChangeType detectChangeType(String original, String newContent);

    /**
     * Method change model.
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    class MethodChange {
        /**
         * Method name
         */
        private String methodName;

        /**
         * Method signature
         */
        private String methodSignature;

        /**
         * Change type
         */
        private ChangeRequest.ChangeType changeType;

        /**
         * Start line number
         */
        private int startLine;

        /**
         * End line number
         */
        private int endLine;

        /**
         * Original code snippet
         */
        private String originalCode;

        /**
         * New code snippet
         */
        private String newCode;

        /**
         * Number of lines changed
         */
        private int linesChanged;
    }
}