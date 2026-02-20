package com.worldmind.sandbox;

import com.worldmind.core.model.FileRecord;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Abstraction for container orchestration.
 * Implementations: DockerSandboxProvider (dev), CloudFoundrySandboxProvider (prod).
 */
public interface SandboxProvider {

    /**
     * Creates and starts a container for a Agent.
     * @return the container/sandbox ID
     */
    String openSandbox(AgentRequest request);

    /**
     * Blocks until the container exits or timeout is reached.
     * @return the container exit code (0 = success)
     */
    int waitForCompletion(String sandboxId, int timeoutSeconds);

    /**
     * Captures stdout/stderr logs from the container.
     */
    String captureOutput(String sandboxId);

    /**
     * Stops and removes the container.
     */
    void teardownSandbox(String sandboxId);

    /**
     * Detects file changes for a completed task.
     *
     * <p>Returns {@code null} to indicate "use default filesystem detection".
     * Providers that run in separate environments (e.g. CF) override this to
     * detect changes via git diff against the task branch.
     *
     * @param taskId unique task identifier
     * @param projectPath host path to the project directory
     * @return list of file changes, or {@code null} to fall back to filesystem detection
     */
    default List<FileRecord> detectChanges(String taskId, Path projectPath) {
        return null;
    }

    /**
     * Snapshots the project directory before agent execution.
     * Used by Docker provider where the worldmind container cannot directly
     * see the host filesystem that agents bind-mount.
     *
     * <p>Returns {@code null} to indicate "use default local filesystem snapshot".
     *
     * @param projectPath host path to the project directory
     * @return file snapshot map (path -> lastModified), or null for default behavior
     */
    default Map<String, Long> snapshotProjectFiles(Path projectPath) {
        return null;
    }

    /**
     * Detects file changes by comparing before/after snapshots via the provider.
     * Called after agent execution with the before-snapshot from
     * {@link #snapshotProjectFiles}.
     *
     * <p>Returns {@code null} to indicate "use default filesystem detection".
     *
     * @param beforeSnapshot snapshot from before execution
     * @param projectPath host path to the project directory
     * @return list of file changes, or null for default behavior
     */
    default List<FileRecord> detectChangesBySnapshot(Map<String, Long> beforeSnapshot, Path projectPath) {
        return null;
    }
}
