package com.worldmind.starblaster.cf;

import com.worldmind.core.model.FileRecord;
import com.worldmind.starblaster.InstructionStore;
import com.worldmind.starblaster.OutputStore;
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
    private final OutputStore outputStore;

    /**
     * Tracks starblasterId to app name mapping so waitForCompletion/captureOutput/teardown
     * can look up the app without re-parsing every time.
     */
    private final ConcurrentHashMap<String, String> starblasterAppNames = new ConcurrentHashMap<>();

    /** Tracks starblasterId to CF task GUID so we poll the exact task, not stale ones with the same name. */
    private final ConcurrentHashMap<String, String> starblasterTaskGuids = new ConcurrentHashMap<>();

    /** Caches the effective git URL per directive so detectChanges can reuse it. */
    private final ConcurrentHashMap<String, String> directiveGitUrls = new ConcurrentHashMap<>();

    public CloudFoundryStarblasterProvider(CloudFoundryProperties cfProperties,
                                           GitWorkspaceManager gitWorkspaceManager,
                                           CfApiClient cfApiClient,
                                           InstructionStore instructionStore,
                                           OutputStore outputStore) {
        this.cfProperties = cfProperties;
        this.gitWorkspaceManager = gitWorkspaceManager;
        this.cfApiClient = cfApiClient;
        this.instructionStore = instructionStore;
        this.outputStore = outputStore;
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
        gitRemoteUrl = sanitizeGitUrl(gitRemoteUrl);

        // Embed git token into HTTPS URLs for push authentication
        var gitToken = cfProperties.getGitToken();
        if (gitToken != null && !gitToken.isBlank() && gitRemoteUrl.startsWith("https://")) {
            gitRemoteUrl = gitRemoteUrl.replace("https://", "https://x-access-token:" + gitToken + "@");
        }

        // Cache the authenticated URL so detectChanges() can clone the directive branch later
        directiveGitUrls.put(directiveId, gitRemoteUrl);

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
        var outputUrl = orchestratorUrl + "/api/internal/output/" + taskName;

        // Build the task command that runs inside the CF app container.
        // CF tasks bypass Docker ENTRYPOINT, so we source entrypoint.sh
        // (minus its final 'exec goose run' line) to set up GOOSE_PROVIDER,
        // GOOSE_MODEL, config.yaml, SSL certs, etc. from VCAP_SERVICES.
        // Goose v1.x (Rust binary) handles SSL and auth natively.

        // Export all env vars into the CF task shell so entrypoint.sh can read
        // GOOSE_PROVIDER, GOOSE_MODEL, provider API keys, and MCP server configs.
        var envExports = request.envVars().entrySet().stream()
                .map(e -> "export %s='%s'".formatted(e.getKey(), e.getValue().replace("'", "\\'")))
                .collect(Collectors.joining(" && "));

        // Determine branch setup and post-Goose git commands based on centurion type.
        // FORGE/PRISM create their own branch and push changes.
        // GAUNTLET/VIGIL checkout the parent FORGE branch to test/review its code.
        // PULSE stays on the default branch (read-only research).
        boolean isImplementation = "forge".equals(type) || "prism".equals(type);
        String parentBranch = deriveParentBranch(type, directiveId);

        String branchSetup;
        String postGooseGit;
        if (parentBranch != null) {
            // GAUNTLET/VIGIL: fetch and checkout the parent FORGE/PRISM branch
            branchSetup = "(git fetch origin " + parentBranch + " && git checkout " + parentBranch
                    + ") || echo 'WARN: parent branch " + parentBranch + " not found, staying on default'";
            postGooseGit = "";
        } else if (isImplementation) {
            // FORGE/PRISM: create branch — push runs after Goose commits
            branchSetup = "git checkout -B " + branchName;
            // Use semicolons (not &&) so push always runs.
            // git diff --cached --quiet exits 1 if there ARE staged changes, so || triggers the commit.
            postGooseGit = "; git add -A; git diff --cached --quiet || git commit -m '" + directiveId
                    + "'; git push -uf origin " + branchName;
        } else {
            // PULSE and others: stay on default branch, no commit/push
            branchSetup = "true";
            postGooseGit = "";
        }

        var taskCommand = String.join(" && ",
                // Export env vars (provider config, API keys, MCP configs)
                envExports.isEmpty() ? "true" : envExports,
                // Source entrypoint.sh to parse VCAP_SERVICES and set up Goose config.
                // Entrypoint uses conditional assignment (:-) so pre-exported vars take precedence.
                "sed '$d' /usr/local/bin/entrypoint.sh > /tmp/_entrypoint_setup.sh && . /tmp/_entrypoint_setup.sh",
                "git clone %s /workspace && cd /workspace".formatted(gitRemoteUrl),
                "git config user.name 'Worldmind Centurion' && git config user.email 'centurion@worldmind.local'",
                branchSetup,
                "mkdir -p .worldmind/directives && curl --retry 3 -fk '%s' > .worldmind/directives/%s.md".formatted(instructionUrl, directiveId),
                // Dump diagnostics to a file that gets committed to git for debugging.
                "mkdir -p .worldmind && {"
                        + " echo '=== ENV ===' && env | grep -iE 'GOOSE|OPENAI|ANTHROPIC|GOOGLE|API_URL|API_KEY|PROVIDER|MODEL|SSL|MCP|HOME' | sed 's/\\(API_KEY\\|TOKEN\\)=.*/\\1=***/';"
                        + " echo '=== CONFIG ===' && cat $HOME/.config/goose/config.yaml 2>&1;"
                        + " echo '=== INSTRUCTION ===' && wc -c .worldmind/directives/" + directiveId + ".md 2>&1;"
                        + " echo '=== INSTRUCTION PREVIEW ===' && head -5 .worldmind/directives/" + directiveId + ".md 2>&1;"
                        + " echo '=== GOOSE VERSION ===' && goose --version 2>&1;"
                        + " echo '=== GOOSE RUN HELP ===' && goose run --help 2>&1;"
                        + " echo '=== WORKSPACE ===' && ls -la /workspace 2>&1;"
                        + " } > .worldmind/diagnostics.log 2>&1",
                // Run Goose headlessly with the instruction file.
                // -i/--instructions loads the file and executes its commands.
                // --no-session avoids persisting session state in CF ephemeral containers.
                // --with-builtin developer loads file-writing tools alongside config.yaml.
                // Verify instruction file is non-empty before running — curl may have failed silently.
                "INSTRUCTION_FILE=.worldmind/directives/" + directiveId + ".md"
                        + " && if [ ! -s \"$INSTRUCTION_FILE\" ]; then"
                        + " echo 'FATAL: instruction file is empty or missing' >> .worldmind/diagnostics.log;"
                        + " echo 'FATAL: instruction file is empty or missing'; exit 1; fi"
                        + " && GOOSE_DEBUG=true goose run --debug --no-session --with-builtin developer"
                        + " -i .worldmind/directives/" + directiveId + ".md > .worldmind/goose-output.log 2>&1"
                        + "; GOOSE_RC=$?; echo \"GOOSE_EXIT_CODE=$GOOSE_RC\" >> .worldmind/diagnostics.log"
                        + "; cp -r $HOME/.local/state/goose/logs/ .worldmind/goose-logs 2>/dev/null"
                        // POST Goose output back to orchestrator (CF API doesn't expose task stdout)
                        + "; curl -fk -X PUT -H 'Content-Type: text/plain' --data-binary @.worldmind/goose-output.log '%s' 2>/dev/null || true".formatted(outputUrl)
                        + postGooseGit
                        + "; exit $GOOSE_RC"
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
     * Returns Goose output for a task.
     *
     * <p>The CF API v3 does not expose task stdout/stderr. Centurion tasks
     * POST their Goose output back to the orchestrator via the OutputStore.
     * For failed tasks, this also checks the CF failure reason.
     */
    @Override
    public String captureOutput(String starblasterId) {
        try {
            // Check OutputStore first — centurions POST their output here
            String output = outputStore.remove(starblasterId);
            if (output != null && !output.isBlank()) {
                log.info("Retrieved output for {} from OutputStore ({} chars)", starblasterId, output.length());
                return output;
            }

            // Fall back to CF failure reason for tasks that failed before posting output
            var appName = getAppNameFromStarblasterId(starblasterId);
            var taskGuid = starblasterTaskGuids.get(starblasterId);
            var failureReason = taskGuid != null
                    ? cfApiClient.getTaskFailureReason(taskGuid)
                    : cfApiClient.getTaskFailureReason(appName, starblasterId);
            if (!failureReason.isEmpty()) {
                return "Task failed: " + failureReason;
            }
            log.warn("No output captured for task {} — centurion may not have posted output", starblasterId);
            return "";
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
        // For quality-gate centurions (GAUNTLET/VIGIL), use the parent FORGE branch.
        // Their directive IDs end with -GAUNTLET or -VIGIL but they don't push their own branches.
        String effectiveId = directiveId;
        for (var suffix : List.of("-GAUNTLET", "-VIGIL")) {
            if (directiveId.endsWith(suffix)) {
                effectiveId = directiveId.substring(0, directiveId.length() - suffix.length());
                break;
            }
        }
        String branchName = gitWorkspaceManager.getBranchName(effectiveId);
        // Use cached URL from openStarblaster() if available, fall back to config
        String gitUrl = directiveGitUrls.getOrDefault(directiveId, resolveAuthenticatedGitUrl());
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("worldmind-diff-");
            gitWorkspaceManager.runGit(tempDir, "clone", "--depth", "1", "--branch", branchName, gitUrl, ".");
            // Fetch main into an explicit ref — plain "fetch origin main" only updates
            // FETCH_HEAD, not origin/main, when the clone is single-branch.
            gitWorkspaceManager.runGit(tempDir, "fetch", "--depth", "1", "origin", "main:refs/remotes/origin/main");
            String diffOutput = gitWorkspaceManager.runGitOutput(tempDir, "diff", "--stat", "origin/main..HEAD");
            // Filter out .worldmind/ internal files (diagnostics, logs) — only report project changes
            return gitWorkspaceManager.parseDiffStat(diffOutput).stream()
                    .filter(f -> !f.path().startsWith(".worldmind/") && !f.path().startsWith(".worldmind\\"))
                    .toList();
        } catch (Exception e) {
            log.warn("Git-based change detection failed for {} (branch {}): {}", directiveId, branchName, e.getMessage());
            return List.of();
        } finally {
            if (tempDir != null) {
                deleteDirectory(tempDir);
            }
        }
    }

    /**
     * Derives the parent FORGE/PRISM branch for quality-gate centurions.
     * GAUNTLET and VIGIL directive IDs follow the pattern {@code DIR-NNN-GAUNTLET} or
     * {@code DIR-NNN-VIGIL}. This strips the centurion suffix to find the parent branch.
     *
     * @return the parent branch name, or null if not a quality-gate centurion
     */
    String deriveParentBranch(String centurionType, String directiveId) {
        if ("gauntlet".equalsIgnoreCase(centurionType) || "vigil".equalsIgnoreCase(centurionType)) {
            String suffix = "-" + centurionType.toUpperCase();
            if (directiveId.endsWith(suffix)) {
                String parentId = directiveId.substring(0, directiveId.length() - suffix.length());
                return gitWorkspaceManager.getBranchName(parentId);
            }
        }
        return null;
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

    /**
     * Strips GitHub browser-URL suffixes (e.g. /tree/main, /blob/...) so the URL
     * is a valid git remote for cloning.
     */
    static String sanitizeGitUrl(String url) {
        if (url == null) return "";
        // Strip /tree/..., /blob/..., /commit/... suffixes (GitHub browser URLs)
        return url.replaceFirst("/(tree|blob|commit|pulls?|issues?|actions|releases)/.*$", "")
                  .replaceFirst("/+$", "");
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
