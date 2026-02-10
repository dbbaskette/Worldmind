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
        log.debug("Running: {}", String.join(" ", command));

        try {
            var process = new ProcessBuilder(command)
                    .directory(workDir.toFile())
                    .redirectErrorStream(true)
                    .start();

            // Consume output to prevent blocking
            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.debug("git: {}", line);
                }
            }

            return process.waitFor();
        } catch (IOException | InterruptedException e) {
            log.error("Git command failed: {}", String.join(" ", command), e);
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
        log.debug("Running (capture): {}", String.join(" ", command));

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
                log.warn("Git command exited with code {}: {}", exitCode, String.join(" ", command));
            }

            return output;
        } catch (IOException | InterruptedException e) {
            log.error("Git command failed: {}", String.join(" ", command), e);
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

    private List<String> buildCommand(String... args) {
        var command = new ArrayList<String>();
        command.add("git");
        command.addAll(List.of(args));
        return command;
    }
}
