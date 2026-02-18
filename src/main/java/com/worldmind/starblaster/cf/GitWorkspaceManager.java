package com.worldmind.starblaster.cf;

import com.worldmind.core.model.FileRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Manages git-based mission branches for centurion tasks on Cloud Foundry.
 *
 * <p>Instead of Docker bind mounts, centurions clone mission branches,
 * make changes, and push. This enables distributed execution across
 * CF app instances without shared filesystem access.
 *
 * <p>This class shells out to the {@code git} CLI via {@link ProcessBuilder}
 * rather than depending on JGit.
 */
public class GitWorkspaceManager {

    private static final Logger log = LoggerFactory.getLogger(GitWorkspaceManager.class);

    private static final String BRANCH_PREFIX = "worldmind/";

    /**
     * Pattern to parse a single line of {@code git diff --stat} output.
     * Examples:
     * <pre>
     *   src/main/Foo.java           | 15 +++---
     *   src/main/Bar.java (new)     |  3 +++
     *   src/old/Baz.java (gone)     |  7 -------
     * </pre>
     */
    static final Pattern DIFF_STAT_LINE = Pattern.compile(
            "^\\s*(.+?)\\s*\\|\\s*(\\d+)\\s*[+\\-]*\\s*$"
    );

    /**
     * Pattern to match embedded credentials in URLs (e.g., https://user:token@host).
     * Used to mask sensitive data before logging.
     */
    private static final Pattern SENSITIVE_URL_PATTERN = Pattern.compile(
            "(https?://)([^:]+:[^@]+)@"
    );

    private final String gitRemoteUrl;

    /**
     * Creates a new GitWorkspaceManager.
     *
     * @param gitRemoteUrl the remote git URL for the project
     */
    public GitWorkspaceManager(String gitRemoteUrl) {
        this.gitRemoteUrl = gitRemoteUrl;
    }

    /**
     * Creates a mission branch from current HEAD and pushes it to origin.
     *
     * @param missionId   unique mission identifier
     * @param projectPath path to the local git repository
     * @return the branch name ({@code worldmind/{missionId}})
     */
    public String prepareBranch(String missionId, Path projectPath) {
        var branchName = getBranchName(missionId);
        log.info("Creating mission branch '{}' in {}", branchName, projectPath);

        int checkoutExit = runGit(projectPath, "checkout", "-b", branchName);
        if (checkoutExit != 0) {
            throw new RuntimeException(
                    "Failed to create branch '%s' (exit code %d)".formatted(branchName, checkoutExit));
        }

        int pushExit = runGit(projectPath, "push", "-u", "origin", branchName);
        if (pushExit != 0) {
            throw new RuntimeException(
                    "Failed to push branch '%s' to origin (exit code %d)".formatted(branchName, pushExit));
        }

        log.info("Mission branch '{}' created and pushed", branchName);
        return branchName;
    }

    /**
     * Detects file changes on a mission branch since a given commit.
     *
     * <p>Checks out the mission branch, pulls latest changes, then runs
     * {@code git diff --stat} to detect what changed.
     *
     * @param missionId    unique mission identifier
     * @param beforeCommit the commit hash to diff against
     * @return list of file changes, empty if none
     */
    public List<FileRecord> detectChanges(String missionId, String beforeCommit) {
        var branchName = getBranchName(missionId);
        // projectPath is derived from git remote but we need workDir for commands.
        // The caller is expected to run this from the project directory.
        // For now, use current directory as fallback.
        return detectChanges(missionId, beforeCommit, Path.of("."));
    }

    /**
     * Detects file changes on a mission branch since a given commit.
     *
     * @param missionId    unique mission identifier
     * @param beforeCommit the commit hash to diff against
     * @param projectPath  path to the local git repository
     * @return list of file changes, empty if none
     */
    public List<FileRecord> detectChanges(String missionId, String beforeCommit, Path projectPath) {
        var branchName = getBranchName(missionId);
        log.info("Detecting changes on branch '{}' since {}", branchName, beforeCommit);

        runGit(projectPath, "checkout", branchName);
        runGit(projectPath, "pull");

        var diffOutput = runGitOutput(projectPath, "diff", "--stat", beforeCommit + "..HEAD");
        return parseDiffStat(diffOutput);
    }

    /**
     * Merges the mission branch into main with a no-fast-forward merge.
     *
     * @param missionId unique mission identifier
     */
    public void mergeMission(String missionId) {
        mergeMission(missionId, Path.of("."));
    }

    /**
     * Merges the mission branch into main with a no-fast-forward merge.
     *
     * @param missionId   unique mission identifier
     * @param projectPath path to the local git repository
     */
    public void mergeMission(String missionId, Path projectPath) {
        var branchName = getBranchName(missionId);
        log.info("Merging mission branch '{}' into main", branchName);

        int checkoutExit = runGit(projectPath, "checkout", "main");
        if (checkoutExit != 0) {
            throw new RuntimeException(
                    "Failed to checkout main (exit code %d)".formatted(checkoutExit));
        }

        int mergeExit = runGit(projectPath, "merge", branchName,
                "--no-ff", "-m", "merge mission " + missionId);
        if (mergeExit != 0) {
            throw new RuntimeException(
                    "Failed to merge branch '%s' (exit code %d)".formatted(branchName, mergeExit));
        }

        int pushExit = runGit(projectPath, "push");
        if (pushExit != 0) {
            throw new RuntimeException(
                    "Failed to push merge (exit code %d)".formatted(pushExit));
        }

        log.info("Mission branch '{}' merged and pushed", branchName);
    }

    /**
     * Cleans up the mission branch (local and remote).
     * Silently ignores errors since the branch may already be deleted.
     *
     * @param missionId unique mission identifier
     */
    public void cleanup(String missionId) {
        cleanup(missionId, Path.of("."));
    }

    /**
     * Cleans up the mission branch (local and remote).
     * Silently ignores errors since the branch may already be deleted.
     *
     * @param missionId   unique mission identifier
     * @param projectPath path to the local git repository
     */
    public void cleanup(String missionId, Path projectPath) {
        var branchName = getBranchName(missionId);
        log.info("Cleaning up mission branch '{}'", branchName);

        try {
            runGit(projectPath, "push", "origin", "--delete", branchName);
        } catch (Exception e) {
            log.debug("Could not delete remote branch '{}': {}", branchName, e.getMessage());
        }

        try {
            runGit(projectPath, "branch", "-D", branchName);
        } catch (Exception e) {
            log.debug("Could not delete local branch '{}': {}", branchName, e.getMessage());
        }

        log.info("Mission branch '{}' cleanup complete", branchName);
    }

    /**
     * Returns the branch name for a given mission ID.
     *
     * @param missionId unique mission identifier
     * @return branch name in format {@code worldmind/{missionId}}
     */
    public String getBranchName(String missionId) {
        return BRANCH_PREFIX + missionId;
    }

    /**
     * Runs a git command and returns the exit code.
     *
     * @param workDir working directory for the git command
     * @param args    git arguments (e.g. "checkout", "-b", "branch-name")
     * @return process exit code
     */
    int runGit(Path workDir, String... args) {
        var command = buildCommand(args);
        log.debug("Running: {}", maskCommand(command));

        try {
            var process = new ProcessBuilder(command)
                    .directory(workDir.toFile())
                    .redirectErrorStream(true)
                    .start();

            // Consume output to prevent blocking
            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.debug("git: {}", maskSensitiveData(line));
                }
            }

            return process.waitFor();
        } catch (IOException | InterruptedException e) {
            log.error("Git command failed: {}", maskCommand(command), e);
            throw new RuntimeException("Git command failed", e);
        }
    }

    /**
     * Runs a git command and captures stdout.
     *
     * @param workDir working directory for the git command
     * @param args    git arguments
     * @return captured stdout as a single string
     */
    String runGitOutput(Path workDir, String... args) {
        var command = buildCommand(args);
        log.debug("Running (capture): {}", maskCommand(command));

        try {
            var process = new ProcessBuilder(command)
                    .directory(workDir.toFile())
                    .redirectErrorStream(false)
                    .start();

            String output;
            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                output = reader.lines().collect(java.util.stream.Collectors.joining("\n"));
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                log.warn("Git command exited with code {}: {}", exitCode, maskCommand(command));
            }

            return output;
        } catch (IOException | InterruptedException e) {
            log.error("Git command failed: {}", maskCommand(command), e);
            throw new RuntimeException("Git command failed", e);
        }
    }

    /**
     * Parses {@code git diff --stat} output into a list of {@link FileRecord}.
     *
     * <p>Each line like {@code src/main/Foo.java | 15 +++---} becomes a FileRecord.
     * Lines containing {@code (new)} are marked as "created", lines with
     * {@code (gone)} as "deleted", and everything else as "modified".
     * The summary line (e.g., "3 files changed, 10 insertions(+)") is ignored.
     *
     * @param diffStatOutput raw output from git diff --stat
     * @return parsed list of file changes
     */
    List<FileRecord> parseDiffStat(String diffStatOutput) {
        if (diffStatOutput == null || diffStatOutput.isBlank()) {
            return List.of();
        }

        var results = new ArrayList<FileRecord>();
        for (var line : diffStatOutput.split("\n")) {
            Matcher matcher = DIFF_STAT_LINE.matcher(line);
            if (!matcher.matches()) {
                // Skip summary line or unparseable lines
                continue;
            }

            var rawPath = matcher.group(1).trim();
            int linesChanged = Integer.parseInt(matcher.group(2));

            String action;
            String path;
            if (rawPath.contains("(new)")) {
                action = "created";
                path = rawPath.replace("(new)", "").trim();
            } else if (rawPath.contains("(gone)")) {
                action = "deleted";
                path = rawPath.replace("(gone)", "").trim();
            } else {
                action = "modified";
                path = rawPath;
            }

            results.add(new FileRecord(path, action, linesChanged));
        }

        return results;
    }

    /**
     * Result of a merge operation (wave or final).
     */
    public record MergeResult(List<String> mergedIds, List<String> conflictedIds) {
        public boolean hasConflicts() {
            return !conflictedIds.isEmpty();
        }
    }

    /**
     * Merges all passed directive branches into main and pushes.
     * Deletes merged branches from remote after successful merge.
     *
     * @param directiveIds list of directive IDs whose branches should be merged
     * @param gitToken     git token for push authentication
     * @param overrideGitUrl optional git URL override; uses config URL if null/blank
     */
    public void mergeDirectiveBranches(List<String> directiveIds, String gitToken, String overrideGitUrl) {
        MergeResult result = doMergeBranches(directiveIds, gitToken, overrideGitUrl, true);
        if (result.hasConflicts()) {
            log.warn("Kept {} conflicted branches on remote for manual resolution: {}",
                    result.conflictedIds().size(), result.conflictedIds());
        }
    }

    /**
     * Merges completed directive branches from a wave into main and pushes.
     * Called after each wave completes so the next wave can branch from updated main.
     * Does NOT delete merged branches (cleanup happens at mission end).
     *
     * @param directiveIds list of directive IDs from the completed wave
     * @param gitToken     git token for push authentication
     * @param overrideGitUrl optional git URL override; uses config URL if null/blank
     * @return result containing merged and conflicted directive IDs
     */
    public MergeResult mergeWaveBranches(List<String> directiveIds, String gitToken, String overrideGitUrl) {
        return doMergeBranches(directiveIds, gitToken, overrideGitUrl, false);
    }

    /**
     * Core merge logic shared by {@link #mergeDirectiveBranches} and {@link #mergeWaveBranches}.
     *
     * <p>Clones the repo into a temp directory, rebases each branch onto main, merges with --no-ff,
     * and pushes. If a merge conflicts, that branch is skipped and others continue.
     *
     * @param directiveIds list of directive IDs whose branches should be merged
     * @param gitToken     git token for push authentication
     * @param overrideGitUrl optional git URL override; uses config URL if null/blank
     * @param deleteBranchesAfterMerge if true, delete merged branches from remote
     * @return result containing merged and conflicted directive IDs
     */
    private MergeResult doMergeBranches(List<String> directiveIds, String gitToken, 
                                         String overrideGitUrl, boolean deleteBranchesAfterMerge) {
        List<String> mergedIds = new ArrayList<>();
        List<String> conflictedIds = new ArrayList<>();

        if (directiveIds == null || directiveIds.isEmpty()) {
            log.debug("No directive branches to merge");
            return new MergeResult(mergedIds, conflictedIds);
        }

        // CRITICAL: Sort directive IDs to ensure deterministic merge order
        // DIR-001 must merge before DIR-002, etc. to prevent race conditions
        List<String> sortedIds = new ArrayList<>(directiveIds);
        sortedIds.sort(String::compareTo);
        
        if (!sortedIds.equals(directiveIds)) {
            log.info("Merge: reordered directives for deterministic merge: {} -> {}", 
                    directiveIds, sortedIds);
        }

        String gitUrl = authenticatedUrl(gitToken, overrideGitUrl);
        if (gitUrl == null || gitUrl.isBlank()) {
            log.warn("Skipping merge: no git remote URL configured for directive branches {}", sortedIds);
            return new MergeResult(mergedIds, conflictedIds);
        }

        Path tempDir = null;
        try {
            tempDir = java.nio.file.Files.createTempDirectory("worldmind-merge-");
            runGit(tempDir, "clone", gitUrl, ".");
            runGit(tempDir, "config", "user.name", "Worldmind");
            runGit(tempDir, "config", "user.email", "worldmind@worldmind.local");
            runGit(tempDir, "checkout", "main");

            log.info("Merge: processing {} directive branches in order: {} (delete after: {})", 
                    sortedIds.size(), sortedIds, deleteBranchesAfterMerge);

            for (String id : sortedIds) {
                if (mergeSingleBranch(tempDir, id, mergedIds, conflictedIds)) {
                    log.info("Merge: successfully merged {}", id);
                    
                    // Push main after EACH successful merge so later rebases see the changes
                    // This is critical for preventing conflicts when parallel directives
                    // create the same files
                    int pushExit = runGit(tempDir, "push", "origin", "main");
                    if (pushExit != 0) {
                        log.error("Merge: failed to push main after merging {}", id);
                        // Pull and retry push in case of race condition
                        runGit(tempDir, "pull", "--rebase", "origin", "main");
                        pushExit = runGit(tempDir, "push", "origin", "main");
                        if (pushExit != 0) {
                            log.error("Merge: push still failed after pull --rebase for {}", id);
                        }
                    } else {
                        log.info("Merge: pushed main with {} merged", id);
                    }
                }
            }

            if (deleteBranchesAfterMerge) {
                for (String id : mergedIds) {
                    String branch = getBranchName(id);
                    runGit(tempDir, "push", "origin", "--delete", branch);
                }
            }

            if (!conflictedIds.isEmpty()) {
                log.warn("Merge: {} branches had conflicts and were not merged: {} — " +
                        "these directives will be retried with updated main context",
                        conflictedIds.size(), conflictedIds);
            }

        } catch (Exception e) {
            log.error("Merge failed: {}", e.getMessage(), e);
        } finally {
            if (tempDir != null) {
                deleteDirectory(tempDir);
            }
        }

        return new MergeResult(mergedIds, conflictedIds);
    }

    /**
     * Fetches, rebases, and merges a single directive branch into main.
     *
     * @param tempDir      working directory with cloned repo on main branch
     * @param directiveId  the directive ID to merge
     * @param mergedIds    list to add successful merges to
     * @param conflictedIds list to add conflicted merges to
     * @return true if merge succeeded, false otherwise
     */
    private boolean mergeSingleBranch(Path tempDir, String directiveId, 
                                       List<String> mergedIds, List<String> conflictedIds) {
        String branch = getBranchName(directiveId);

        // Always fetch latest main first - critical for sequential merge ordering
        // This ensures we're rebasing onto the actual current main (with prior merges)
        runGit(tempDir, "fetch", "origin", "main");
        runGit(tempDir, "checkout", "main");
        runGit(tempDir, "reset", "--hard", "origin/main");

        int fetchExit = runGit(tempDir, "fetch", "origin",
                "refs/heads/" + branch + ":refs/remotes/origin/" + branch);
        if (fetchExit != 0) {
            log.warn("Merge: branch {} not found on remote, skipping", branch);
            return false;
        }

        int checkoutExit = runGit(tempDir, "checkout", "-B", "temp-" + directiveId, "origin/" + branch);
        if (checkoutExit != 0) {
            log.warn("Merge: could not checkout branch {} for rebase, skipping", branch);
            return false;
        }

        // Rebase onto the freshly-fetched main
        int rebaseExit = runGit(tempDir, "rebase", "main");
        if (rebaseExit != 0) {
            String conflictFiles = runGitOutput(tempDir, "diff", "--name-only", "--diff-filter=U");
            log.error("Merge: rebase conflict on {} — conflicting files: {} " +
                    "(directive will be retried with updated main)", 
                    branch, conflictFiles.isBlank() ? "(unknown)" : conflictFiles.replace("\n", ", "));
            runGit(tempDir, "rebase", "--abort");
            runGit(tempDir, "checkout", "main");
            conflictedIds.add(directiveId);
            return false;
        }

        runGit(tempDir, "checkout", "main");
        int mergeExit = runGit(tempDir, "merge", "temp-" + directiveId,
                "--no-ff", "-m", "merge directive " + directiveId);
        if (mergeExit != 0) {
            log.error("Merge: merge conflict on branch {} after rebase, aborting", branch);
            runGit(tempDir, "merge", "--abort");
            conflictedIds.add(directiveId);
            return false;
        }

        mergedIds.add(directiveId);
        return true;
    }

    /**
     * Deletes directive branches from the remote without merging.
     * Used when a mission fails and branches should be cleaned up.
     *
     * @param directiveIds list of directive IDs whose branches should be deleted
     * @param gitToken     git token for push authentication
     * @param overrideGitUrl optional git URL override (e.g. from mission request); uses config URL if null/blank
     */
    public void cleanupDirectiveBranches(List<String> directiveIds, String gitToken, String overrideGitUrl) {
        String gitUrl = authenticatedUrl(gitToken, overrideGitUrl);
        if (gitUrl == null || gitUrl.isBlank()) {
            log.warn("Skipping cleanup: no git remote URL configured for directive branches {}", directiveIds);
            return;
        }
        Path tempDir = null;
        try {
            tempDir = java.nio.file.Files.createTempDirectory("worldmind-cleanup-");
            runGit(tempDir, "clone", "--depth", "1", gitUrl, ".");
            for (String id : directiveIds) {
                String branch = getBranchName(id);
                int exit = runGit(tempDir, "push", "origin", "--delete", branch);
                if (exit != 0) {
                    log.debug("Could not delete branch {} (may already be deleted)", branch);
                }
            }
        } catch (Exception e) {
            log.error("Failed to cleanup directive branches: {}", e.getMessage(), e);
        } finally {
            if (tempDir != null) {
                deleteDirectory(tempDir);
            }
        }
    }

    /**
     * Strips GitHub browser-URL suffixes (e.g. /tree/main, /blob/...) so the URL
     * is a valid git remote for cloning.
     */
    static String sanitizeGitUrl(String url) {
        if (url == null) return "";
        return url.replaceFirst("/(tree|blob|commit|pulls?|issues?|actions|releases)/.*$", "")
                  .replaceFirst("/+$", "");
    }

    private String authenticatedUrl(String gitToken, String overrideGitUrl) {
        String baseUrl = (overrideGitUrl != null && !overrideGitUrl.isBlank()) ? sanitizeGitUrl(overrideGitUrl) : gitRemoteUrl;
        if (baseUrl == null || baseUrl.isBlank()) {
            log.warn("No git remote URL configured — cannot perform git operations");
            return "";
        }
        log.info("Resolved git URL for merge/cleanup: {}", maskSensitiveData(baseUrl));
        if (gitToken != null && !gitToken.isBlank() && baseUrl.startsWith("https://")) {
            return baseUrl.replace("https://", "https://x-access-token:" + gitToken + "@");
        }
        return baseUrl;
    }

    private static void deleteDirectory(Path dir) {
        try (var walk = java.nio.file.Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try { java.nio.file.Files.deleteIfExists(p); }
                        catch (IOException ignored) {}
                    });
        } catch (IOException ignored) {}
    }

    /**
     * Masks sensitive data (credentials, tokens) in a string for safe logging.
     * Replaces embedded URL credentials like https://user:token@host with https://***@host.
     *
     * @param input the string that may contain sensitive data
     * @return the string with sensitive data masked
     */
    static String maskSensitiveData(String input) {
        if (input == null) return null;
        return SENSITIVE_URL_PATTERN.matcher(input).replaceAll("$1***@");
    }

    /**
     * Masks sensitive data in a command list for safe logging.
     *
     * @param command the command arguments
     * @return a single string with sensitive data masked
     */
    private static String maskCommand(List<String> command) {
        return command.stream()
                .map(GitWorkspaceManager::maskSensitiveData)
                .collect(java.util.stream.Collectors.joining(" "));
    }

    private List<String> buildCommand(String... args) {
        var command = new ArrayList<String>();
        command.add("git");
        command.addAll(List.of(args));
        return command;
    }
}
