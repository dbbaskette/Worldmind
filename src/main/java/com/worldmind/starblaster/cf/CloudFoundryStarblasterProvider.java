package com.worldmind.starblaster.cf;

import com.worldmind.starblaster.StarblasterProvider;
import com.worldmind.starblaster.StarblasterRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Base64;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

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
 */
public class CloudFoundryStarblasterProvider implements StarblasterProvider {

    private static final Logger log = LoggerFactory.getLogger(CloudFoundryStarblasterProvider.class);

    static final int POLL_INTERVAL_SECONDS = 5;

    private final CloudFoundryProperties cfProperties;
    private final GitWorkspaceManager gitWorkspaceManager;
    private final CfApiClient cfApiClient;

    /**
     * Tracks starblasterId to app name mapping so waitForCompletion/captureOutput/teardown
     * can look up the app without re-parsing every time.
     */
    private final ConcurrentHashMap<String, String> starblasterAppNames = new ConcurrentHashMap<>();

    public CloudFoundryStarblasterProvider(CloudFoundryProperties cfProperties,
                                           GitWorkspaceManager gitWorkspaceManager,
                                           CfApiClient cfApiClient) {
        this.cfProperties = cfProperties;
        this.gitWorkspaceManager = gitWorkspaceManager;
        this.cfApiClient = cfApiClient;
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

        var memoryMb = request.memoryLimitMb() > 0
                ? request.memoryLimitMb()
                : cfProperties.getTaskMemoryMb();
        var diskMb = cfProperties.getTaskDiskMb();

        // Base64-encode the instruction so it survives shell escaping
        var instructionB64 = Base64.getEncoder().encodeToString(
                request.instructionText().getBytes(StandardCharsets.UTF_8));

        // Build the task command that runs inside the CF app container.
        // Clone from default branch, create a directive branch, write the
        // instruction file (from base64), run Goose, commit, and push.
        var taskCommand = String.join(" && ",
                "git clone %s /workspace".formatted(gitRemoteUrl),
                "cd /workspace",
                "git checkout -b %s".formatted(branchName),
                "mkdir -p .worldmind/directives",
                "echo '%s' | base64 -d > .worldmind/directives/%s.md".formatted(instructionB64, directiveId),
                "goose run .worldmind/directives/%s.md".formatted(directiveId),
                "git add -A",
                "git commit -m 'DIR-%s'".formatted(directiveId),
                "git push -u origin %s".formatted(branchName)
        );

        log.info("Opening Starblaster {} for directive {} on app {}", taskName, directiveId, appName);
        log.debug("Task command: {}", taskCommand);

        cfApiClient.createTask(appName, taskCommand, taskName, memoryMb, diskMb);

        log.info("CF task {} started on app {}", taskName, appName);
        starblasterAppNames.put(taskName, appName);

        return taskName;
    }

    @Override
    public int waitForCompletion(String starblasterId, int timeoutSeconds) {
        var appName = getAppNameFromStarblasterId(starblasterId);
        log.info("Waiting for task {} on app {} (timeout: {}s)", starblasterId, appName, timeoutSeconds);

        long deadline = System.currentTimeMillis() + (timeoutSeconds * 1000L);

        while (System.currentTimeMillis() < deadline) {
            try {
                var state = cfApiClient.getTaskState(appName, starblasterId);

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
            var failureReason = cfApiClient.getTaskFailureReason(appName, starblasterId);
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
            var appName = getAppNameFromStarblasterId(starblasterId);
            cfApiClient.cancelTask(appName, starblasterId);
            log.info("Task {} cancelled on app {}", starblasterId, appName);
        } catch (Exception e) {
            log.debug("Could not cancel task {} (may already be done): {}",
                    starblasterId, e.getMessage());
        } finally {
            starblasterAppNames.remove(starblasterId);
        }
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
