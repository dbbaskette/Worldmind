package com.worldmind.stargate;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.WaitContainerResultCallback;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.core.command.LogContainerResultCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Docker-based StargateProvider for local development.
 * Creates Docker containers for each Centurion directive execution.
 *
 * <p>Each container is configured with:
 * <ul>
 *   <li>A bind mount mapping the host project directory to /workspace</li>
 *   <li>Environment variables for Goose configuration</li>
 *   <li>Memory and CPU limits from the StargateRequest</li>
 *   <li>Extra host entry for host.docker.internal (for LM Studio access)</li>
 *   <li>Command: goose run /workspace/.worldmind/directives/{directiveId}.md</li>
 * </ul>
 */
public class DockerStargateProvider implements StargateProvider {

    private static final Logger log = LoggerFactory.getLogger(DockerStargateProvider.class);
    private static final String IMAGE_PREFIX = "worldmind/centurion-";
    private static final String IMAGE_TAG = ":latest";

    private final DockerClient dockerClient;

    public DockerStargateProvider(DockerClient dockerClient) {
        this.dockerClient = dockerClient;
    }

    @Override
    public String openStargate(StargateRequest request) {
        String type = request.centurionType().toLowerCase();
        String containerName = "stargate-" + type + "-" + request.directiveId();
        String imageName = IMAGE_PREFIX + type + IMAGE_TAG;
        log.info("Opening Stargate {} for directive {} (image: {})",
                containerName, request.directiveId(), imageName);

        // Clean up any stale container with the same name from a previous retry
        try {
            dockerClient.removeContainerCmd(containerName).withForce(true).exec();
            log.debug("Removed stale container {}", containerName);
        } catch (Exception ignored) {
            // Container doesn't exist — normal case
        }

        var envList = new ArrayList<String>();
        request.envVars().forEach((k, v) -> envList.add(k + "=" + v));

        // When running inside Docker (WORKSPACE_VOLUME set):
        //   - Bind-mount the HOST project path to /workspace (so files land on the host FS)
        //   - Mount the shared Docker volume to /instructions (for instruction files
        //     written by the server at <volume>/directives/<id>.md)
        // When running locally: just bind-mount the host project path (covers both)
        String workspaceVolume = System.getenv("WORKSPACE_VOLUME");
        String hostProjectPath = request.projectPath().toString();

        var binds = new ArrayList<Bind>();
        binds.add(new Bind(hostProjectPath, new Volume("/workspace"), AccessMode.rw));
        if (workspaceVolume != null) {
            binds.add(new Bind(workspaceVolume, new Volume("/instructions"), AccessMode.rw));
        }

        var hostConfig = HostConfig.newHostConfig()
                .withBinds(binds.toArray(new Bind[0]))
                .withMemory((long) request.memoryLimitMb() * 1024 * 1024)
                .withCpuCount((long) request.cpuCount())
                .withExtraHosts("host.docker.internal:host-gateway")
                .withDns("8.8.8.8", "8.8.4.4");

        // Instruction file path: in Docker → /instructions/directives/<id>.md
        //                         locally  → /workspace/.worldmind/directives/<id>.md
        String instructionPath = workspaceVolume != null
                ? "/instructions/directives/" + request.directiveId() + ".md"
                : "/workspace/.worldmind/directives/" + request.directiveId() + ".md";

        var response = dockerClient.createContainerCmd(imageName)
                .withName(containerName)
                .withHostConfig(hostConfig)
                .withEnv(envList)
                .withCmd(instructionPath)
                .withWorkingDir("/workspace")
                .exec();

        String containerId = response.getId();
        dockerClient.startContainerCmd(containerId).exec();
        log.info("Stargate {} started (container {})", containerName, containerId);
        return containerId;
    }

    @Override
    public int waitForCompletion(String stargateId, int timeoutSeconds) {
        try {
            var callback = dockerClient.waitContainerCmd(stargateId)
                    .exec(new WaitContainerResultCallback());
            var result = callback.awaitStatusCode(timeoutSeconds, TimeUnit.SECONDS);
            return result != null ? result : -1;
        } catch (Exception e) {
            log.error("Timeout or error waiting for stargate {}", stargateId, e);
            return -1;
        }
    }

    @Override
    public String captureOutput(String stargateId) {
        var sb = new StringBuilder();
        try {
            dockerClient.logContainerCmd(stargateId)
                    .withStdOut(true)
                    .withStdErr(true)
                    .withFollowStream(false)
                    .exec(new LogContainerResultCallback() {
                        @Override
                        public void onNext(Frame frame) {
                            sb.append(new String(frame.getPayload()));
                        }
                    }).awaitCompletion(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while capturing output from stargate {}", stargateId);
        }
        return sb.toString();
    }

    @Override
    public void teardownStargate(String stargateId) {
        try {
            dockerClient.stopContainerCmd(stargateId).exec();
        } catch (Exception e) {
            log.debug("Container {} may already be stopped: {}", stargateId, e.getMessage());
        }
        try {
            dockerClient.removeContainerCmd(stargateId).withForce(true).exec();
            log.info("Stargate {} torn down", stargateId);
        } catch (Exception e) {
            log.warn("Failed to remove container {}", stargateId, e);
        }
    }
}
