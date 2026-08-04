package com.huawei.hisi.knowledgegraph.exception;

/**
 * Thrown when no generation checkpoint exists for a project.
 */
public class NoCheckpointException extends RuntimeException {

    public NoCheckpointException(String projectPath) {
        super("No generation checkpoint found for project: " + projectPath);
    }
}
