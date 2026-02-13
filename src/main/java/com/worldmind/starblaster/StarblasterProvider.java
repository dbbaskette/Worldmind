package com.worldmind.starblaster;

import com.worldmind.core.model.FileRecord;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Abstraction for container orchestration.
 * Implementations: DockerStarblasterProvider (dev), CloudFoundryStarblasterProvider (prod).
 */
public interface StarblasterProvider {

    /**
     * Creates and starts a container for a Centurion.
     * @return the container/starblaster ID
     */
    String openStarblaster(StarblasterRequest request);

    /**
     * Blocks until the container exits or timeout is reached.
     * @return the container exit code (0 = success)
     */
    int waitForCompletion(String starblasterId, int timeoutSeconds);

    /**
     * Captures stdout/stderr logs from the container.
     */
    String captureOutput(String starblasterId);

    /**
     * Stops and removes the container.
     */
    void teardownStarblaster(String starblasterId);

    /**
     * Detects file changes for a completed directive.
     *
     * <p>Returns {@code null} to indicate "use default filesystem detection".
     * Providers that run in separate environments (e.g. CF) override this to
     * detect changes via git diff against the directive branch.
     *
     * @param directiveId unique directive identifier
     * @param projectPath host path to the project directory
     * @return list of file changes, or {@code null} to fall back to filesystem detection
     */
    default List<FileRecord> detectChanges(String directiveId, Path projectPath) {
        return null;
    }

    /**
     * Snapshots the project directory before centurion execution.
     * Used by Docker provider where the worldmind container cannot directly
     * see the host filesystem that centurions bind-mount.
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
     * Called after centurion execution with the before-snapshot from
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
