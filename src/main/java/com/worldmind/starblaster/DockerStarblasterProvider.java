package com.worldmind.starblaster;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.WaitContainerResultCallback;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.core.command.LogContainerResultCallback;
import com.worldmind.core.model.FileRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Docker-based StarblasterProvider for local development.
 * Creates Docker containers for each Centurion directive execution.
 *
 * <p>Each container is configured with:
 * <ul>
 *   <li>A bind mount mapping the host project directory to /workspace</li>
 *   <li>Environment variables for Goose configuration</li>
 *   <li>Memory and CPU limits from the StarblasterRequest</li>
 *   <li>Extra host entry for host.docker.internal (for LM Studio access)</li>
 *   <li>Command: goose run /workspace/.worldmind/directives/{directiveId}.md</li>
 * </ul>
 *
 * <p>When the worldmind server itself runs inside Docker (WORKSPACE_VOLUME set),
 * it cannot directly see the host filesystem where centurions write code.
 * In this mode, file change detection uses lightweight helper containers that
 * bind-mount the same host project path to capture before/after file listings.
 */
public class DockerStarblasterProvider implements StarblasterProvider {

    private static final Logger log = LoggerFactory.getLogger(DockerStarblasterProvider.class);

    /** Alpine image used for lightweight file-listing helper containers. */
    private static final String HELPER_IMAGE = "alpine:3.19";

    private final DockerClient dockerClient;
    private final String imageRegistry;
    private final String imagePrefix;

    public DockerStarblasterProvider(DockerClient dockerClient, String imageRegistry, String imagePrefix) {
        this.dockerClient = dockerClient;
        this.imageRegistry = imageRegistry;
        this.imagePrefix = imagePrefix != null ? imagePrefix : "starblaster";
    }

    @Override
    public String openStarblaster(StarblasterRequest request) {
        String type = request.centurionType().toLowerCase();
        String containerName = "starblaster-" + type + "-" + request.directiveId();
        String runtimeTag = request.runtimeTag() != null ? request.runtimeTag() : "base";
        String imageName = imageRegistry + "/" + imagePrefix + ":" + runtimeTag;

        // Fallback: if the tagged image doesn't exist locally, use base
        try {
            dockerClient.inspectImageCmd(imageName).exec();
        } catch (NotFoundException e) {
            log.warn("Image {} not found locally, falling back to base", imageName);
            imageName = imageRegistry + "/" + imagePrefix + ":base";
        }

        log.info("Opening Starblaster {} for directive {} (image: {})",
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
        log.info("Starblaster {} started (container {})", containerName, containerId);
        return containerId;
    }

    @Override
    public int waitForCompletion(String starblasterId, int timeoutSeconds) {
        try {
            var callback = dockerClient.waitContainerCmd(starblasterId)
                    .exec(new WaitContainerResultCallback());
            var result = callback.awaitStatusCode(timeoutSeconds, TimeUnit.SECONDS);
            return result != null ? result : -1;
        } catch (Exception e) {
            log.error("Timeout or error waiting for starblaster {}", starblasterId, e);
            return -1;
        }
    }

    @Override
    public String captureOutput(String starblasterId) {
        var sb = new StringBuilder();
        try {
            dockerClient.logContainerCmd(starblasterId)
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
            log.warn("Interrupted while capturing output from starblaster {}", starblasterId);
        }
        return sb.toString();
    }

    @Override
    public void teardownStarblaster(String starblasterId) {
        try {
            dockerClient.stopContainerCmd(starblasterId).exec();
        } catch (Exception e) {
            log.debug("Container {} may already be stopped: {}", starblasterId, e.getMessage());
        }
        try {
            dockerClient.removeContainerCmd(starblasterId).withForce(true).exec();
            log.info("Starblaster {} torn down", starblasterId);
        } catch (Exception e) {
            log.warn("Failed to remove container {}", starblasterId, e);
        }
    }

    /**
     * When the worldmind server runs inside Docker, it cannot see the host filesystem.
     * This method runs a lightweight helper container that bind-mounts the same host
     * project path and captures a file listing with timestamps.
     */
    @Override
    public Map<String, Long> snapshotProjectFiles(Path projectPath) {
        if (System.getenv("WORKSPACE_VOLUME") == null) {
            return null; // Not in Docker-in-Docker mode, use default local snapshot
        }
        return runFileListingHelper(projectPath, "snapshot-before");
    }

    /**
     * Compares a before-snapshot against the current state of the host project
     * directory using a helper container.
     */
    @Override
    public List<FileRecord> detectChangesBySnapshot(Map<String, Long> beforeSnapshot, Path projectPath) {
        if (System.getenv("WORKSPACE_VOLUME") == null || beforeSnapshot == null) {
            return null; // Use default local detection
        }

        Map<String, Long> afterSnapshot = runFileListingHelper(projectPath, "snapshot-after");
        if (afterSnapshot == null) {
            log.warn("Failed to capture after-snapshot, falling back to empty changes");
            return List.of();
        }

        var changes = new ArrayList<FileRecord>();
        for (var entry : afterSnapshot.entrySet()) {
            String path = entry.getKey();
            Long afterTime = entry.getValue();
            Long beforeTime = beforeSnapshot.get(path);

            if (beforeTime == null) {
                changes.add(new FileRecord(path, "created", 0));
            } else if (!afterTime.equals(beforeTime)) {
                changes.add(new FileRecord(path, "modified", 0));
            }
        }

        log.info("Docker helper detected {} file changes", changes.size());
        return changes;
    }

    /**
     * Runs a lightweight Alpine container that bind-mounts the host project path
     * and outputs a file listing with modification timestamps.
     *
     * <p>Output format: one line per file, "mtime_seconds path"
     */
    private Map<String, Long> runFileListingHelper(Path hostProjectPath, String suffix) {
        String containerName = "wm-" + suffix + "-" + System.currentTimeMillis();
        try {
            var hostConfig = HostConfig.newHostConfig()
                    .withBinds(new Bind(hostProjectPath.toString(), new Volume("/scan"), AccessMode.ro));

            var response = dockerClient.createContainerCmd(HELPER_IMAGE)
                    .withName(containerName)
                    .withHostConfig(hostConfig)
                    .withCmd("sh", "-c",
                            "find /scan -type f " +
                            "-not -path '*/.git/*' " +
                            "-not -path '*/.worldmind/*' " +
                            "-not -path '*/node_modules/*' " +
                            "-exec stat -c '%Y %n' {} +")
                    .exec();

            String containerId = response.getId();
            dockerClient.startContainerCmd(containerId).exec();

            var callback = dockerClient.waitContainerCmd(containerId)
                    .exec(new WaitContainerResultCallback());
            callback.awaitStatusCode(30, TimeUnit.SECONDS);

            var sb = new StringBuilder();
            dockerClient.logContainerCmd(containerId)
                    .withStdOut(true)
                    .withFollowStream(false)
                    .exec(new LogContainerResultCallback() {
                        @Override
                        public void onNext(Frame frame) {
                            sb.append(new String(frame.getPayload()));
                        }
                    }).awaitCompletion(10, TimeUnit.SECONDS);

            dockerClient.removeContainerCmd(containerId).withForce(true).exec();

            return parseFileListing(sb.toString());
        } catch (Exception e) {
            log.warn("Helper container {} failed: {}", containerName, e.getMessage());
            // Clean up on failure
            try { dockerClient.removeContainerCmd(containerName).withForce(true).exec(); }
            catch (Exception ignored) {}
            return null;
        }
    }

    /**
     * Parses "mtime_seconds path" output into a map of relative path -> mtime.
     */
    private static Map<String, Long> parseFileListing(String output) {
        var result = new HashMap<String, Long>();
        for (String line : output.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            int spaceIdx = line.indexOf(' ');
            if (spaceIdx <= 0) continue;
            try {
                long mtime = Long.parseLong(line.substring(0, spaceIdx));
                String fullPath = line.substring(spaceIdx + 1);
                // Strip "/scan/" prefix to get relative path
                String relativePath = fullPath.startsWith("/scan/")
                        ? fullPath.substring(6)
                        : fullPath;
                result.put(relativePath, mtime);
            } catch (NumberFormatException e) {
                // Skip malformed lines
            }
        }
        return result;
    }
}
