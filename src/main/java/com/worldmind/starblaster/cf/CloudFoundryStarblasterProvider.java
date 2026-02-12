package com.worldmind.starblaster.cf;

import com.worldmind.core.model.FileRecord;
import com.worldmind.starblaster.InstructionStore;
import com.worldmind.starblaster.StarblasterProvider;
import com.worldmind.starblaster.StarblasterRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Cloud Foundry-based StarblasterProvider for production deployments.
 *
 * <p>Uses CF tasks to run Centurion directives on pre-deployed CF apps.
 * Each centurion type maps to a CF application (configured via
 * {@link CloudFoundryProperties#getCenturionApps()}).
 *
 * <p>Workspace sharing is handled via git: centurions clone the mission branch,
 * run Goose, commit changes, and push. The orchestrator then detects changes
 * via {@link GitWorkspaceManager#detectChanges}.
 *
 * <p>This class uses {@link CfApiClient} to call the CF Cloud Controller
 * API v3 directly over HTTP, rather than shelling out to the {@code cf} CLI
 * (which is not available in the Java buildpack container).
 *
 * <p>Instruction text is stored in {@link InstructionStore} and fetched by
 * the CF task via HTTP, since CF task commands are limited to 4096 characters.
 */
public class CloudFoundryStarblasterProvider implements StarblasterProvider {

    private static final Logger log = LoggerFactory.getLogger(CloudFoundryStarblasterProvider.class);

    static final int POLL_INTERVAL_SECONDS = 5;

    private final CloudFoundryProperties cfProperties;
    private final GitWorkspaceManager gitWorkspaceManager;
    private final CfApiClient cfApiClient;
    private final InstructionStore instructionStore;

    /**
     * Tracks starblasterId to app name mapping so waitForCompletion/captureOutput/teardown
     * can look up the app without re-parsing every time.
     */
    private final ConcurrentHashMap<String, String> starblasterAppNames = new ConcurrentHashMap<>();

    /** Tracks starblasterId to CF task GUID so we poll the exact task, not stale ones with the same name. */
    private final ConcurrentHashMap<String, String> starblasterTaskGuids = new ConcurrentHashMap<>();

    public CloudFoundryStarblasterProvider(CloudFoundryProperties cfProperties,
                                           GitWorkspaceManager gitWorkspaceManager,
                                           CfApiClient cfApiClient,
                                           InstructionStore instructionStore) {
        this.cfProperties = cfProperties;
        this.gitWorkspaceManager = gitWorkspaceManager;
        this.cfApiClient = cfApiClient;
        this.instructionStore = instructionStore;
    }

    @Override
    public String openStarblaster(StarblasterRequest request) {
        var type = request.centurionType().toLowerCase();
        var appName = cfProperties.getCenturionApps().getOrDefault(type, "centurion-" + type);

        var directiveId = request.directiveId();
        var taskName = "starblaster-" + type + "-" + directiveId;
        var branchName = gitWorkspaceManager.getBranchName(directiveId);
        var gitRemoteUrl = request.gitRemoteUrl() != null && !request.gitRemoteUrl().isBlank()
                ? request.gitRemoteUrl()
                : cfProperties.getGitRemoteUrl();

        // Embed git token into HTTPS URLs for push authentication
        var gitToken = cfProperties.getGitToken();
        if (gitToken != null && !gitToken.isBlank() && gitRemoteUrl.startsWith("https://")) {
            gitRemoteUrl = gitRemoteUrl.replace("https://", "https://x-access-token:" + gitToken + "@");
        }

        var memoryMb = request.memoryLimitMb() > 0
                ? request.memoryLimitMb()
                : cfProperties.getTaskMemoryMb();
        var diskMb = cfProperties.getTaskDiskMb();

        // Store instruction text for HTTP retrieval by the task.
        // CF task commands are limited to 4096 chars, so we can't inline the instruction.
        var instructionKey = taskName;
        instructionStore.put(instructionKey, request.instructionText());

        var orchestratorUrl = cfProperties.getOrchestratorUrl();
        var instructionUrl = orchestratorUrl + "/api/internal/instructions/" + instructionKey;

        // Build the task command that runs inside the CF app container.
        // CF tasks bypass Docker ENTRYPOINT, so we source entrypoint.sh
        // (minus its final 'exec goose run' line) to set up GOOSE_PROVIDER,
        // GOOSE_MODEL, config.yaml, SSL certs, etc. from VCAP_SERVICES.
        // Goose v1.x (Rust binary) handles SSL and auth natively.

        // Export MCP env vars into the CF task shell so entrypoint.sh can generate Goose extensions
        var mcpExports = request.envVars().entrySet().stream()
                .filter(e -> e.getKey().startsWith("MCP_"))
                .map(e -> "export %s='%s'".formatted(e.getKey(), e.getValue().replace("'", "\\'")))
                .collect(Collectors.joining(" && "));

        var taskCommand = String.join(" && ",
                // Export MCP server env vars (no-op if none configured)
                mcpExports.isEmpty() ? "true" : mcpExports,
                // Source entrypoint.sh to parse VCAP_SERVICES and set up Goose config.
                // Bridge API_KEY to OPENAI_API_KEY — older entrypoint images export
                // GOOSE_PROVIDER__API_KEY which Goose v1.x doesn't read.
                "sed '$d' /usr/local/bin/entrypoint.sh > /tmp/_entrypoint_setup.sh && . /tmp/_entrypoint_setup.sh && [ -n \"$API_KEY\" ] && export OPENAI_API_KEY=\"${OPENAI_API_KEY:-$API_KEY}\"",
                "git clone %s /workspace && cd /workspace".formatted(gitRemoteUrl),
                "git config user.name 'Worldmind Centurion' && git config user.email 'centurion@worldmind.local'",
                "git checkout -B %s".formatted(branchName),
                "mkdir -p .worldmind/directives && curl --retry 3 -fk '%s' > .worldmind/directives/%s.md".formatted(instructionUrl, directiveId),
                "goose run --no-session -i .worldmind/directives/%s.md".formatted(directiveId),
                "git add -A && git commit -m '%s' && git push -uf origin %s".formatted(directiveId, branchName)
        );

        log.info("Opening Starblaster {} for directive {} on app {}", taskName, directiveId, appName);
        log.debug("Task command: {}", taskCommand);

        var taskGuid = cfApiClient.createTask(appName, taskCommand, taskName, memoryMb, diskMb);

        log.info("CF task {} started on app {} (guid={})", taskName, appName, taskGuid);
        starblasterAppNames.put(taskName, appName);
        starblasterTaskGuids.put(taskName, taskGuid);

        return taskName;
    }

    @Override
    public int waitForCompletion(String starblasterId, int timeoutSeconds) {
        var appName = getAppNameFromStarblasterId(starblasterId);
        var taskGuid = starblasterTaskGuids.get(starblasterId);
        log.info("Waiting for task {} (guid={}) on app {} (timeout: {}s)",
                starblasterId, taskGuid, appName, timeoutSeconds);

        long deadline = System.currentTimeMillis() + (timeoutSeconds * 1000L);

        while (System.currentTimeMillis() < deadline) {
            try {
                var state = taskGuid != null
                        ? cfApiClient.getTaskState(taskGuid)
                        : cfApiClient.getTaskState(appName, starblasterId);

                if ("SUCCEEDED".equals(state)) {
                    log.info("Task {} completed successfully", starblasterId);
                    return 0;
                } else if ("FAILED".equals(state)) {
                    log.warn("Task {} failed", starblasterId);
                    return 1;
                }

                log.debug("Task {} state: {}, polling in {}s", starblasterId, state, POLL_INTERVAL_SECONDS);
                Thread.sleep(POLL_INTERVAL_SECONDS * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while waiting for task {}", starblasterId);
                return -1;
            } catch (Exception e) {
                log.warn("Error polling task {} status: {}", starblasterId, e.getMessage());
            }
        }

        log.warn("Task {} timed out after {}s", starblasterId, timeoutSeconds);
        return -1;
    }

    /**
     * Returns diagnostic output for a task.
     *
     * <p>The CF API v3 does not expose application logs directly (that requires
     * the Log Cache API). For failed tasks, this returns the failure reason
     * from the task resource. For other states, it returns a pointer to
     * {@code cf logs}.
     */
    @Override
    public String captureOutput(String starblasterId) {
        try {
            var appName = getAppNameFromStarblasterId(starblasterId);
            var taskGuid = starblasterTaskGuids.get(starblasterId);
            var failureReason = taskGuid != null
                    ? cfApiClient.getTaskFailureReason(taskGuid)
                    : cfApiClient.getTaskFailureReason(appName, starblasterId);
            if (!failureReason.isEmpty()) {
                return "Task failed: " + failureReason;
            }
            return "Task output available via: cf logs " + appName + " --recent";
        } catch (Exception e) {
            log.warn("Failed to capture output for task {}: {}", starblasterId, e.getMessage());
            return "";
        }
    }

    @Override
    public void teardownStarblaster(String starblasterId) {
        try {
            var taskGuid = starblasterTaskGuids.get(starblasterId);
            if (taskGuid != null) {
                cfApiClient.cancelTask(taskGuid);
            } else {
                var appName = getAppNameFromStarblasterId(starblasterId);
                cfApiClient.cancelTask(appName, starblasterId);
            }
            log.info("Task {} cancelled", starblasterId);
        } catch (Exception e) {
            log.debug("Could not cancel task {} (may already be done): {}",
                    starblasterId, e.getMessage());
        } finally {
            starblasterAppNames.remove(starblasterId);
            starblasterTaskGuids.remove(starblasterId);
            instructionStore.remove(starblasterId);
        }
    }

    /**
     * Detects file changes by cloning the directive branch and diffing against main.
     *
     * <p>CF centurions push changes to {@code worldmind/{directiveId}} branches.
     * This method shallow-clones that branch into a temp directory and runs
     * {@code git diff --stat origin/main..HEAD} to detect what files changed.
     */
    @Override
    public List<FileRecord> detectChanges(String directiveId, Path projectPath) {
        String branchName = gitWorkspaceManager.getBranchName(directiveId);
        String gitUrl = resolveAuthenticatedGitUrl();
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("worldmind-diff-");
            gitWorkspaceManager.runGit(tempDir, "clone", "--depth", "1", "--branch", branchName, gitUrl, ".");
            gitWorkspaceManager.runGit(tempDir, "fetch", "--depth", "1", "origin", "main");
            String diffOutput = gitWorkspaceManager.runGitOutput(tempDir, "diff", "--stat", "origin/main..HEAD");
            return gitWorkspaceManager.parseDiffStat(diffOutput);
        } catch (Exception e) {
            log.warn("Git-based change detection failed for {}: {}", directiveId, e.getMessage());
            return List.of();
        } finally {
            if (tempDir != null) {
                deleteDirectory(tempDir);
            }
        }
    }

    /**
     * Builds an authenticated git URL by embedding the git token into the HTTPS URL.
     * Reuses the same logic as {@link #openStarblaster}.
     */
    String resolveAuthenticatedGitUrl() {
        String gitUrl = cfProperties.getGitRemoteUrl();
        String gitToken = cfProperties.getGitToken();
        if (gitToken != null && !gitToken.isBlank() && gitUrl.startsWith("https://")) {
            gitUrl = gitUrl.replace("https://", "https://x-access-token:" + gitToken + "@");
        }
        return gitUrl;
    }

    private static void deleteDirectory(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); }
                        catch (IOException ignored) {}
                    });
        } catch (IOException ignored) {}
    }

    /**
     * Extracts the app name for a given starblasterId.
     *
     * <p>First checks the in-memory cache. If not found, parses the centurion type
     * from the starblasterId format {@code starblaster-{type}-{directiveId}} and looks
     * it up in centurionApps.
     */
    String getAppNameFromStarblasterId(String starblasterId) {
        var cached = starblasterAppNames.get(starblasterId);
        if (cached != null) {
            return cached;
        }

        if (!starblasterId.startsWith("starblaster-")) {
            throw new RuntimeException(
                    "Invalid starblasterId format: '%s' (expected starblaster-{type}-{directiveId})"
                            .formatted(starblasterId));
        }

        var withoutPrefix = starblasterId.substring("starblaster-".length());
        var dashIndex = withoutPrefix.indexOf('-');
        if (dashIndex < 0) {
            throw new RuntimeException(
                    "Invalid starblasterId format: '%s' (expected starblaster-{type}-{directiveId})"
                            .formatted(starblasterId));
        }

        var type = withoutPrefix.substring(0, dashIndex);
        var appName = cfProperties.getCenturionApps().getOrDefault(type, "centurion-" + type);

        starblasterAppNames.put(starblasterId, appName);
        return appName;
    }
}
