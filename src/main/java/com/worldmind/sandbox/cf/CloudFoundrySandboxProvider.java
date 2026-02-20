package com.worldmind.sandbox.cf;

import com.worldmind.core.model.FileRecord;
import com.worldmind.sandbox.InstructionStore;
import com.worldmind.sandbox.OutputStore;
import com.worldmind.sandbox.SandboxProvider;
import com.worldmind.sandbox.AgentRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Cloud Foundry-based SandboxProvider for production deployments.
 *
 * <p>Uses CF tasks to run Agent tasks on pre-deployed CF apps.
 * Each agent type maps to a CF application (configured via
 * {@link CloudFoundryProperties#getAgentApps()}).
 *
 * <p>Workspace sharing is handled via git: agents clone the mission branch,
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
public class CloudFoundrySandboxProvider implements SandboxProvider {

    private static final Logger log = LoggerFactory.getLogger(CloudFoundrySandboxProvider.class);

    /**
     * Patterns to match sensitive data in strings for safe logging.
     */
    private static final Pattern SENSITIVE_URL_PATTERN = Pattern.compile(
            "(https?://)([^:]+:[^@]+)@"
    );
    private static final Pattern SENSITIVE_KEY_PATTERN = Pattern.compile(
            "((?:API_KEY|TOKEN|PASSWORD|SECRET)=['\"]?)([^'\"\\s]+)(['\"]?)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern SENSITIVE_EXPORT_PATTERN = Pattern.compile(
            "(export\\s+[A-Z_]*(?:KEY|TOKEN|PASSWORD|SECRET)[A-Z_]*=['\"]?)([^'\"]+)(['\"]?)",
            Pattern.CASE_INSENSITIVE
    );

    static final int POLL_INTERVAL_SECONDS = 5;

    private final CloudFoundryProperties cfProperties;
    private final GitWorkspaceManager gitWorkspaceManager;
    private final CfApiClient cfApiClient;
    private final InstructionStore instructionStore;
    private final OutputStore outputStore;

    /**
     * Tracks sandboxId to app name mapping so waitForCompletion/captureOutput/teardown
     * can look up the app without re-parsing every time.
     */
    private final ConcurrentHashMap<String, String> sandboxAppNames = new ConcurrentHashMap<>();

    /** Tracks sandboxId to CF task GUID so we poll the exact task, not stale ones with the same name. */
    private final ConcurrentHashMap<String, String> sandboxTaskGuids = new ConcurrentHashMap<>();

    /** Caches the effective git URL per task so detectChanges can reuse it. */
    private final ConcurrentHashMap<String, String> taskGitUrls = new ConcurrentHashMap<>();

    public CloudFoundrySandboxProvider(CloudFoundryProperties cfProperties,
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
    public String openSandbox(AgentRequest request) {
        var type = request.agentType().toLowerCase();
        var appName = cfProperties.getAgentApps().getOrDefault(type, "agent-" + type);

        var taskId = request.taskId();
        var taskName = "sandbox-" + type + "-" + taskId;
        var branchName = gitWorkspaceManager.getBranchName(taskId);
        var gitRemoteUrl = request.gitRemoteUrl() != null && !request.gitRemoteUrl().isBlank()
                ? request.gitRemoteUrl()
                : cfProperties.getGitRemoteUrl();
        gitRemoteUrl = sanitizeGitUrl(gitRemoteUrl);

        // Embed git token into HTTPS URLs for push authentication
        var gitToken = cfProperties.getGitToken();
        if (gitToken != null && !gitToken.isBlank() && gitRemoteUrl.startsWith("https://")) {
            gitRemoteUrl = gitRemoteUrl.replace("https://", "https://x-access-token:" + gitToken + "@");
        }

        // Cache the authenticated URL so detectChanges() can clone the task branch later
        taskGitUrls.put(taskId, gitRemoteUrl);

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
        // For DEPLOYER agents, inject CF credentials so they can deploy apps via cf CLI.
        // These are available in cfProperties from the orchestrator's env vars.
        var effectiveEnvVars = new java.util.HashMap<>(request.envVars());
        if ("deployer".equals(type)) {
            effectiveEnvVars.putIfAbsent("CF_API_URL", cfProperties.getApiUrl());
            effectiveEnvVars.putIfAbsent("CF_USERNAME", cfProperties.getCfUsername());
            effectiveEnvVars.putIfAbsent("CF_PASSWORD", cfProperties.getCfPassword());
            effectiveEnvVars.putIfAbsent("CF_ORG", cfProperties.getOrg());
            effectiveEnvVars.putIfAbsent("CF_SPACE", cfProperties.getSpace());
        }

        var envExports = effectiveEnvVars.entrySet().stream()
                .map(e -> "export %s='%s'".formatted(e.getKey(), e.getValue().replace("'", "\\'")))
                .collect(Collectors.joining(" && "));

        // Determine branch setup and post-Goose git commands based on agent type.
        // CODER/REFACTORER create their own branch and push changes.
        // TESTER/REVIEWER checkout the parent CODER branch to test/review its code.
        // RESEARCHER stays on the default branch (read-only research).
        boolean isImplementation = "coder".equals(type) || "refactorer".equals(type);
        boolean isRetry = request.iteration() > 0;
        String parentBranch = deriveParentBranch(type, taskId);

        String branchSetup;
        String postGooseGit;
        if (parentBranch != null) {
            // TESTER/REVIEWER: fetch and checkout the parent CODER/REFACTORER branch
            branchSetup = "(git fetch origin " + parentBranch + " && git checkout " + parentBranch
                    + ") || echo 'WARN: parent branch " + parentBranch + " not found, staying on default'";
            postGooseGit = "";
        } else if (isImplementation) {
            // ALWAYS start fresh from main for CODER/REFACTORER tasks.
            // 
            // Why? Several scenarios cause stale branches that lead to merge conflicts:
            // 1. RETRY: Previous branch was based on old main (before other tasks merged)
            // 2. LEFTOVER: Branch exists from a previous mission that wasn't cleaned up
            // 3. MODIFICATION: Running against existing code with old worldmind branches
            // 4. CF RESTART: Task restarted but main has moved forward
            //
            // The safest approach is to ALWAYS delete any existing branch and start fresh
            // from current main. This ensures:
            // - Agent always sees the latest code (including other merged tasks)
            // - No stale changes from previous attempts interfere
            // - Clean git history without orphaned commits
            //
            // The instruction text contains all context the agent needs (including
            // review feedback for retries), so losing the old branch content is fine.
            if (isRetry) {
                log.info("Task {} is retry (iteration {}): deleting old branch and starting fresh from main",
                        taskId, request.iteration());
            } else {
                log.info("Task {} (iteration 0): ensuring fresh branch from current main", taskId);
            }
            
            // Delete any existing branch (remote and local) then create fresh from main.
            // The '|| true' ensures we continue even if branch doesn't exist.
            branchSetup = "git push origin --delete " + branchName + " 2>/dev/null || true; "
                    + "git branch -D " + branchName + " 2>/dev/null || true; "
                    + "git checkout -b " + branchName;
            
            // Use semicolons (not &&) so push always runs.
            // Each task has its own .worldmind-TASK-XXX/ directory so logs don't conflict.
            // git diff --cached --quiet exits 1 if there ARE staged changes, so || triggers the commit.
            postGooseGit = "; git add -A; git diff --cached --quiet || git commit -m '" + taskId
                    + "'; git push -uf origin " + branchName;
        } else {
            // RESEARCHER and others: stay on default branch, no commit/push
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
                "git config user.name 'Worldmind Agent' && git config user.email 'agent@worldmind.local'",
                branchSetup,
                // Use task-specific directory (.worldmind-TASK-001/) so each task's logs don't conflict
                "mkdir -p .worldmind-" + taskId + " && curl --retry 3 -fk '%s' > .worldmind-%s/instruction.md".formatted(instructionUrl, taskId),
                // Dump diagnostics to a file that gets committed to git for debugging.
                // IMPORTANT: Exclude VCAP_SERVICES (contains credentials in JSON) and mask any API keys/tokens.
                "{"
                        + " echo '=== ENV ===' && env | grep -iE 'GOOSE|OPENAI|ANTHROPIC|GOOGLE|API_URL|PROVIDER|MODEL|SSL|MCP|HOME' | grep -v VCAP | sed 's/\\(API_KEY\\|TOKEN\\|PASSWORD\\|SECRET\\)=.*/\\1=***/';"
                        + " echo '=== CONFIG ===' && cat $HOME/.config/goose/config.yaml 2>&1 | sed 's/\\(api_key\\|token\\|password\\|secret\\):.*/\\1: ***/i';"
                        + " echo '=== INSTRUCTION ===' && wc -c .worldmind-" + taskId + "/instruction.md 2>&1;"
                        + " echo '=== INSTRUCTION PREVIEW ===' && head -5 .worldmind-" + taskId + "/instruction.md 2>&1;"
                        + " echo '=== GOOSE VERSION ===' && goose --version 2>&1;"
                        + " echo '=== GOOSE RUN HELP ===' && goose run --help 2>&1;"
                        + " echo '=== WORKSPACE ===' && ls -la /workspace 2>&1;"
                        + " } > .worldmind-" + taskId + "/diagnostics.log 2>&1",
                // Run Goose headlessly with the instruction file.
                // -i/--instructions loads the file and executes its commands.
                // --no-session avoids persisting session state in CF ephemeral containers.
                // --with-builtin developer loads file-writing tools alongside config.yaml.
                // --with-builtin web_search enables web search for research-heavy tasks (CODER, RESEARCHER).
                // Verify instruction file is non-empty before running — curl may have failed silently.
                "INSTRUCTION_FILE=.worldmind-" + taskId + "/instruction.md"
                        + " && if [ ! -s \"$INSTRUCTION_FILE\" ]; then"
                        + " echo 'FATAL: instruction file is empty or missing' >> .worldmind-" + taskId + "/diagnostics.log;"
                        + " echo 'FATAL: instruction file is empty or missing'; exit 1; fi"
                        + " && GOOSE_DEBUG=true goose run --debug --no-session --with-builtin developer"
                        + (("coder".equals(type) || "researcher".equals(type)) ? " --with-builtin web_search" : "")
                        + " -i .worldmind-" + taskId + "/instruction.md > .worldmind-" + taskId + "/goose-output.log 2>&1"
                        + "; GOOSE_RC=$?; echo \"GOOSE_EXIT_CODE=$GOOSE_RC\" >> .worldmind-" + taskId + "/diagnostics.log"
                        + "; cp -r $HOME/.local/state/goose/logs/ .worldmind-" + taskId + "/goose-logs 2>/dev/null"
                        // POST Goose output back to orchestrator (CF API doesn't expose task stdout)
                        + "; curl -fk -X PUT -H 'Content-Type: text/plain' --data-binary @.worldmind-" + taskId + "/goose-output.log '%s' 2>/dev/null || true".formatted(outputUrl)
                        + postGooseGit
                        + "; exit $GOOSE_RC"
        );

        log.info("Opening Sandbox {} for task {} on app {}", taskName, taskId, appName);
        log.debug("Task command: {}", maskSensitiveData(taskCommand));

        var taskGuid = cfApiClient.createTask(appName, taskCommand, taskName, memoryMb, diskMb);

        log.info("CF task {} started on app {} (guid={})", taskName, appName, taskGuid);
        sandboxAppNames.put(taskName, appName);
        sandboxTaskGuids.put(taskName, taskGuid);

        return taskName;
    }

    @Override
    public int waitForCompletion(String sandboxId, int timeoutSeconds) {
        var appName = getAppNameFromSandboxId(sandboxId);
        var taskGuid = sandboxTaskGuids.get(sandboxId);
        log.info("Waiting for task {} (guid={}) on app {} (timeout: {}s)",
                sandboxId, taskGuid, appName, timeoutSeconds);

        long deadline = System.currentTimeMillis() + (timeoutSeconds * 1000L);

        while (System.currentTimeMillis() < deadline) {
            try {
                var state = taskGuid != null
                        ? cfApiClient.getTaskState(taskGuid)
                        : cfApiClient.getTaskState(appName, sandboxId);

                if ("SUCCEEDED".equals(state)) {
                    log.info("Task {} completed successfully", sandboxId);
                    return 0;
                } else if ("FAILED".equals(state)) {
                    log.warn("Task {} failed", sandboxId);
                    return 1;
                }

                log.debug("Task {} state: {}, polling in {}s", sandboxId, state, POLL_INTERVAL_SECONDS);
                Thread.sleep(POLL_INTERVAL_SECONDS * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while waiting for task {}", sandboxId);
                return -1;
            } catch (Exception e) {
                log.warn("Error polling task {} status: {}", sandboxId, e.getMessage());
            }
        }

        log.warn("Task {} timed out after {}s", sandboxId, timeoutSeconds);
        return -1;
    }

    /**
     * Returns Goose output for a task.
     *
     * <p>The CF API v3 does not expose task stdout/stderr. Agent tasks
     * POST their Goose output back to the orchestrator via the OutputStore.
     * For failed tasks, this also checks the CF failure reason.
     */
    @Override
    public String captureOutput(String sandboxId) {
        try {
            // Check OutputStore first — agents POST their output here
            String output = outputStore.remove(sandboxId);
            if (output != null && !output.isBlank()) {
                log.info("Retrieved output for {} from OutputStore ({} chars)", sandboxId, output.length());
                return output;
            }

            // Fall back to CF failure reason for tasks that failed before posting output
            var appName = getAppNameFromSandboxId(sandboxId);
            var taskGuid = sandboxTaskGuids.get(sandboxId);
            var failureReason = taskGuid != null
                    ? cfApiClient.getTaskFailureReason(taskGuid)
                    : cfApiClient.getTaskFailureReason(appName, sandboxId);
            if (!failureReason.isEmpty()) {
                return "Task failed: " + failureReason;
            }
            log.warn("No output captured for task {} — agent may not have posted output", sandboxId);
            return "";
        } catch (Exception e) {
            log.warn("Failed to capture output for task {}: {}", sandboxId, e.getMessage());
            return "";
        }
    }

    @Override
    public void teardownSandbox(String sandboxId) {
        try {
            var taskGuid = sandboxTaskGuids.get(sandboxId);
            if (taskGuid != null) {
                cfApiClient.cancelTask(taskGuid);
            } else {
                var appName = getAppNameFromSandboxId(sandboxId);
                cfApiClient.cancelTask(appName, sandboxId);
            }
            log.info("Task {} cancelled", sandboxId);
        } catch (Exception e) {
            log.debug("Could not cancel task {} (may already be done): {}",
                    sandboxId, e.getMessage());
        } finally {
            sandboxAppNames.remove(sandboxId);
            sandboxTaskGuids.remove(sandboxId);
            instructionStore.remove(sandboxId);
        }
    }

    /**
     * Detects file changes by cloning the task branch and diffing against main.
     *
     * <p>CF agents push changes to {@code worldmind/{taskId}} branches.
     * This method shallow-clones that branch into a temp directory and runs
     * {@code git diff --stat origin/main..HEAD} to detect what files changed.
     */
    @Override
    public List<FileRecord> detectChanges(String taskId, Path projectPath) {
        // For quality-gate agents (TESTER/REVIEWER), use the parent CODER branch.
        // Their task IDs end with -TESTER or -REVIEWER but they don't push their own branches.
        String effectiveId = taskId;
        for (var suffix : List.of("-TESTER", "-REVIEWER")) {
            if (taskId.endsWith(suffix)) {
                effectiveId = taskId.substring(0, taskId.length() - suffix.length());
                break;
            }
        }
        String branchName = gitWorkspaceManager.getBranchName(effectiveId);
        // Use cached URL from openSandbox() if available, fall back to config
        String gitUrl = taskGitUrls.getOrDefault(taskId, resolveAuthenticatedGitUrl());
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
            log.warn("Git-based change detection failed for {} (branch {}): {}", taskId, branchName, e.getMessage());
            return List.of();
        } finally {
            if (tempDir != null) {
                deleteDirectory(tempDir);
            }
        }
    }

    /**
     * Derives the parent CODER/REFACTORER branch for quality-gate agents.
     * TESTER and REVIEWER task IDs follow the pattern {@code TASK-NNN-TESTER} or
     * {@code TASK-NNN-REVIEWER}. This strips the agent suffix to find the parent branch.
     *
     * @return the parent branch name, or null if not a quality-gate agent
     */
    String deriveParentBranch(String agentType, String taskId) {
        if ("tester".equalsIgnoreCase(agentType) || "reviewer".equalsIgnoreCase(agentType)) {
            String suffix = "-" + agentType.toUpperCase();
            if (taskId.endsWith(suffix)) {
                String parentId = taskId.substring(0, taskId.length() - suffix.length());
                return gitWorkspaceManager.getBranchName(parentId);
            }
        }
        return null;
    }

    /**
     * Builds an authenticated git URL by embedding the git token into the HTTPS URL.
     * Reuses the same logic as {@link #openSandbox}.
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
     * Extracts the app name for a given sandboxId.
     *
     * <p>First checks the in-memory cache. If not found, parses the agent type
     * from the sandboxId format {@code sandbox-{type}-{taskId}} and looks
     * it up in agentApps.
     */
    String getAppNameFromSandboxId(String sandboxId) {
        var cached = sandboxAppNames.get(sandboxId);
        if (cached != null) {
            return cached;
        }

        if (!sandboxId.startsWith("sandbox-")) {
            throw new RuntimeException(
                    "Invalid sandboxId format: '%s' (expected sandbox-{type}-{taskId})"
                            .formatted(sandboxId));
        }

        var withoutPrefix = sandboxId.substring("sandbox-".length());
        var dashIndex = withoutPrefix.indexOf('-');
        if (dashIndex < 0) {
            throw new RuntimeException(
                    "Invalid sandboxId format: '%s' (expected sandbox-{type}-{taskId})"
                            .formatted(sandboxId));
        }

        var type = withoutPrefix.substring(0, dashIndex);
        var appName = cfProperties.getAgentApps().getOrDefault(type, "agent-" + type);

        sandboxAppNames.put(sandboxId, appName);
        return appName;
    }

    /**
     * Masks sensitive data (credentials, API keys, tokens) in a string for safe logging.
     * Replaces:
     * - Embedded URL credentials like https://user:token@host with https://***@host
     * - Environment variables like API_KEY=secret with API_KEY=***
     * - Export statements like export OPENAI_API_KEY='secret' with export OPENAI_API_KEY='***'
     *
     * @param input the string that may contain sensitive data
     * @return the string with sensitive data masked
     */
    static String maskSensitiveData(String input) {
        if (input == null) return null;
        String result = SENSITIVE_URL_PATTERN.matcher(input).replaceAll("$1***@");
        result = SENSITIVE_KEY_PATTERN.matcher(result).replaceAll("$1***$3");
        result = SENSITIVE_EXPORT_PATTERN.matcher(result).replaceAll("$1***$3");
        return result;
    }
}
