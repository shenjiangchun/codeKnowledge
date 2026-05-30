package com.huawei.hisi.knowledgegraph.exception;

/**
 * Thrown when a Git working directory has uncommitted changes.
 */
public class WorkingDirDirtyException extends RuntimeException {

    public WorkingDirDirtyException(String workingDirectory) {
        super("Working directory is not clean: " + workingDirectory);
    }
}
