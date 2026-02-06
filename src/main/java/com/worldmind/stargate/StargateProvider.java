package com.worldmind.stargate;

/**
 * Abstraction for container orchestration.
 * Implementations: DockerStargateProvider (dev), CloudFoundryStargateProvider (prod).
 */
public interface StargateProvider {

    /**
     * Creates and starts a container for a Centurion.
     * @return the container/stargate ID
     */
    String openStargate(StargateRequest request);

    /**
     * Blocks until the container exits or timeout is reached.
     * @return the container exit code (0 = success)
     */
    int waitForCompletion(String stargateId, int timeoutSeconds);

    /**
     * Captures stdout/stderr logs from the container.
     */
    String captureOutput(String stargateId);

    /**
     * Stops and removes the container.
     */
    void teardownStargate(String stargateId);
}
