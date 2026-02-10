package com.worldmind.starblaster;

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
}
