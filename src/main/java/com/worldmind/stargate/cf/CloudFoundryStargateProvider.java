package com.worldmind.stargate.cf;

import com.worldmind.stargate.StargateProvider;
import com.worldmind.stargate.StargateRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Cloud Foundry-based StargateProvider for production deployments.
 *
 * <p>Uses CF tasks to run Centurion directives on pre-deployed CF apps.
 * Each centurion type maps to a CF application (configured via
 * {@link CloudFoundryProperties#getCenturionApps()}).
 *
 * <p>Workspace sharing is handled via git: centurions clone the mission branch,
 * run Goose, commit changes, and push. The orchestrator then detects changes
 * via {@link GitWorkspaceManager#detectChanges}.
 *
 * <p>This class shells out to the {@code cf} CLI via {@link ProcessBuilder}
 * rather than depending on the heavy cloudfoundry-client-reactor library.
 */
public class CloudFoundryStargateProvider implements StargateProvider {

    private static final Logger log = LoggerFactory.getLogger(CloudFoundryStargateProvider.class);

    private static final int POLL_INTERVAL_SECONDS = 5;

    private final CloudFoundryProperties cfProperties;
    private final GitWorkspaceManager gitWorkspaceManager;

    /**
     * Tracks stargateId to app name mapping so waitForCompletion/captureOutput/teardown
     * can look up the app without re-parsing every time.
     */
    private final ConcurrentHashMap<String, String> stargateAppNames = new ConcurrentHashMap<>();

    /**
     * Creates a new CloudFoundryStargateProvider.
     *
     * @param cfProperties      CF configuration properties
     * @param gitWorkspaceManager git workspace manager for branch operations
     */
    public CloudFoundryStargateProvider(CloudFoundryProperties cfProperties,
                                        GitWorkspaceManager gitWorkspaceManager) {
        this.cfProperties = cfProperties;
        this.gitWorkspaceManager = gitWorkspaceManager;
    }

    /**
     * Opens a Stargate by running a CF task on the centurion app.
     *
     * <p>The task clones the mission branch, copies the directive, runs Goose,
     * and pushes any changes back to the branch.
     *
     * @param request the stargate request containing centurion type, directive, etc.
     * @return the task name (stargateId) in format {@code stargate-{type}-{directiveId}}
     * @throws RuntimeException if the centurion type is unknown or the cf command fails
     */
    @Override
    public String openStargate(StargateRequest request) {
        var type = request.centurionType().toLowerCase();
        var appName = cfProperties.getCenturionApps().get(type);
        if (appName == null) {
            throw new RuntimeException(
                    "Unknown centurion type '%s'. Known types: %s".formatted(
                            type, cfProperties.getCenturionApps().keySet()));
        }

        var directiveId = request.directiveId();
        var taskName = "stargate-" + type + "-" + directiveId;
        var branchName = gitWorkspaceManager.getBranchName(directiveId);
        var gitRemoteUrl = cfProperties.getGitRemoteUrl();

        var memoryMb = request.memoryLimitMb() > 0
                ? request.memoryLimitMb()
                : cfProperties.getTaskMemoryMb();
        var diskMb = cfProperties.getTaskDiskMb();

        // Build the task command that runs inside the CF app container
        var taskCommand = String.join(" && ",
                "git clone -b %s %s /workspace".formatted(branchName, gitRemoteUrl),
                "mkdir -p /workspace/.worldmind/directives",
                "cd /workspace",
                "goose run /workspace/.worldmind/directives/%s.md".formatted(directiveId),
                "git add -A",
                "git commit -m 'DIR-%s'".formatted(directiveId),
                "git push"
        );

        log.info("Opening Stargate {} for directive {} on app {}", taskName, directiveId, appName);
        log.debug("Task command: {}", taskCommand);

        var output = runCf("run-task", appName,
                "--command", taskCommand,
                "--name", taskName,
                "-m", memoryMb + "M",
                "-k", diskMb + "M");

        log.info("CF task {} started on app {}: {}", taskName, appName, output.trim());

        // Track the mapping for later operations
        stargateAppNames.put(taskName, appName);

        return taskName;
    }

    /**
     * Polls CF task status until completion or timeout.
     *
     * <p>CF task states: RUNNING, SUCCEEDED, FAILED.
     *
     * @param stargateId     the task name returned by {@link #openStargate}
     * @param timeoutSeconds maximum time to wait
     * @return 0 for SUCCEEDED, 1 for FAILED, -1 for timeout
     */
    @Override
    public int waitForCompletion(String stargateId, int timeoutSeconds) {
        var appName = getAppNameFromStargateId(stargateId);
        log.info("Waiting for task {} on app {} (timeout: {}s)", stargateId, appName, timeoutSeconds);

        long deadline = System.currentTimeMillis() + (timeoutSeconds * 1000L);

        while (System.currentTimeMillis() < deadline) {
            try {
                var output = runCf("tasks", appName);
                var status = parseTaskStatus(output, stargateId);

                if ("SUCCEEDED".equals(status)) {
                    log.info("Task {} completed successfully", stargateId);
                    return 0;
                } else if ("FAILED".equals(status)) {
                    log.warn("Task {} failed", stargateId);
                    return 1;
                }

                // Still RUNNING — wait and poll again
                log.debug("Task {} still running, polling in {}s", stargateId, POLL_INTERVAL_SECONDS);
                Thread.sleep(POLL_INTERVAL_SECONDS * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while waiting for task {}", stargateId);
                return -1;
            } catch (Exception e) {
                log.warn("Error polling task {} status: {}", stargateId, e.getMessage());
                // Continue polling — transient errors are expected
            }
        }

        log.warn("Task {} timed out after {}s", stargateId, timeoutSeconds);
        return -1;
    }

    /**
     * Captures recent logs from the CF app filtered for this task.
     *
     * @param stargateId the task name
     * @return log output, or empty string on error
     */
    @Override
    public String captureOutput(String stargateId) {
        try {
            var appName = getAppNameFromStargateId(stargateId);
            var output = runCf("logs", appName, "--recent");

            // Filter lines related to this task
            return output.lines()
                    .filter(line -> line.contains(stargateId) || line.contains("APP/TASK"))
                    .collect(Collectors.joining("\n"));
        } catch (Exception e) {
            log.warn("Failed to capture output for task {}: {}", stargateId, e.getMessage());
            return "";
        }
    }

    /**
     * Terminates a CF task. Silently ignores errors (task may already be done).
     *
     * @param stargateId the task name
     */
    @Override
    public void teardownStargate(String stargateId) {
        try {
            var appName = getAppNameFromStargateId(stargateId);
            runCf("terminate-task", appName, stargateId);
            log.info("Task {} terminated on app {}", stargateId, appName);
        } catch (Exception e) {
            log.debug("Could not terminate task {} (may already be done): {}",
                    stargateId, e.getMessage());
        } finally {
            stargateAppNames.remove(stargateId);
        }
    }

    /**
     * Parses the task status from {@code cf tasks} output.
     *
     * <p>Expected output format:
     * <pre>
     * id   name                       state       ...
     * 3    stargate-forge-DIR-001     SUCCEEDED   ...
     * </pre>
     *
     * @param tasksOutput raw output from cf tasks
     * @param taskName    the task name to search for
     * @return the status string (RUNNING, SUCCEEDED, FAILED) or null if not found
     */
    String parseTaskStatus(String tasksOutput, String taskName) {
        if (tasksOutput == null || tasksOutput.isBlank()) {
            return null;
        }

        for (var line : tasksOutput.split("\n")) {
            if (line.contains(taskName)) {
                // Split the line by whitespace and find the state column
                var parts = line.trim().split("\\s+");
                // Expected columns: id, name, state, ...
                // The task name is in the second column, state in the third
                for (int i = 0; i < parts.length; i++) {
                    if (parts[i].equals(taskName) && i + 1 < parts.length) {
                        return parts[i + 1];
                    }
                }
            }
        }
        return null;
    }

    /**
     * Extracts the app name for a given stargateId.
     *
     * <p>First checks the in-memory cache. If not found, parses the centurion type
     * from the stargateId format {@code stargate-{type}-{directiveId}} and looks
     * it up in centurionApps.
     *
     * @param stargateId task name in format stargate-{type}-{directiveId}
     * @return the CF app name
     * @throws RuntimeException if the type cannot be resolved
     */
    String getAppNameFromStargateId(String stargateId) {
        // Check cache first
        var cached = stargateAppNames.get(stargateId);
        if (cached != null) {
            return cached;
        }

        // Parse: stargate-{type}-{directiveId}
        if (!stargateId.startsWith("stargate-")) {
            throw new RuntimeException(
                    "Invalid stargateId format: '%s' (expected stargate-{type}-{directiveId})".formatted(stargateId));
        }

        var withoutPrefix = stargateId.substring("stargate-".length());
        var dashIndex = withoutPrefix.indexOf('-');
        if (dashIndex < 0) {
            throw new RuntimeException(
                    "Invalid stargateId format: '%s' (expected stargate-{type}-{directiveId})".formatted(stargateId));
        }

        var type = withoutPrefix.substring(0, dashIndex);
        var appName = cfProperties.getCenturionApps().get(type);
        if (appName == null) {
            throw new RuntimeException(
                    "Unknown centurion type '%s' parsed from stargateId '%s'".formatted(type, stargateId));
        }

        // Cache for future lookups
        stargateAppNames.put(stargateId, appName);
        return appName;
    }

    /**
     * Runs a {@code cf} CLI command and returns stdout.
     *
     * @param args cf command arguments (e.g. "run-task", "my-app", ...)
     * @return captured stdout as a string
     * @throws RuntimeException if the command fails or returns a non-zero exit code
     */
    String runCf(String... args) {
        var command = buildCommand(args);
        log.debug("Running: {}", String.join(" ", command));

        try {
            var process = new ProcessBuilder(command)
                    .redirectErrorStream(false)
                    .start();

            String output;
            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }

            // Also consume stderr
            String errorOutput;
            try (var reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                errorOutput = reader.lines().collect(Collectors.joining("\n"));
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                log.error("CF command failed (exit {}): {} — stderr: {}",
                        exitCode, String.join(" ", command), errorOutput);
                throw new RuntimeException(
                        "CF command failed (exit %d): %s".formatted(exitCode, errorOutput));
            }

            return output;
        } catch (IOException | InterruptedException e) {
            log.error("CF command execution error: {}", String.join(" ", command), e);
            throw new RuntimeException("CF command failed", e);
        }
    }

    /**
     * Runs a {@code cf} CLI command and returns the exit code.
     *
     * @param args cf command arguments
     * @return the process exit code
     */
    int runCfExitCode(String... args) {
        var command = buildCommand(args);
        log.debug("Running (exit code): {}", String.join(" ", command));

        try {
            var process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();

            // Consume output to prevent blocking
            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                while (reader.readLine() != null) {
                    // drain
                }
            }

            return process.waitFor();
        } catch (IOException | InterruptedException e) {
            log.error("CF command execution error: {}", String.join(" ", command), e);
            return -1;
        }
    }

    private List<String> buildCommand(String... args) {
        var command = new ArrayList<String>();
        command.add("cf");
        command.addAll(List.of(args));
        return command;
    }
}
