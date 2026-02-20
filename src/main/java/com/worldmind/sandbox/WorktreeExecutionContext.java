package com.worldmind.sandbox;

import com.worldmind.core.metrics.WorldmindMetrics;
import com.worldmind.sandbox.cf.GitWorkspaceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages worktree-based execution contexts for parallel task execution.
 *
 * <p>This class provides isolated working directories for each task by leveraging
 * git worktrees. Each task gets its own worktree branching from main, allowing
 * parallel execution without file conflicts.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>Mission starts: {@link #createMissionWorkspace} clones the repository</li>
 *   <li>Per task: {@link #acquireWorktree} creates an isolated worktree</li>
 *   <li>After execution: {@link #commitAndPush} commits changes, {@link #releaseWorktree} cleans up</li>
 *   <li>Mission ends: {@link #cleanupMission} removes all worktrees and the workspace</li>
 * </ol>
 *
 * <p>This is used by local execution (Docker provider) and potentially CF execution
 * when operating in a persistent-volume mode.
 */
public class WorktreeExecutionContext {

    private static final Logger log = LoggerFactory.getLogger(WorktreeExecutionContext.class);

    private final GitWorkspaceManager gitWorkspaceManager;
    private final WorldmindMetrics metrics;

    /** Maps missionId to the mission workspace path (the main clone). */
    private final ConcurrentHashMap<String, Path> missionWorkspaces = new ConcurrentHashMap<>();

    /** Maps taskId to its worktree path. */
    private final ConcurrentHashMap<String, Path> taskWorktrees = new ConcurrentHashMap<>();

    public WorktreeExecutionContext(GitWorkspaceManager gitWorkspaceManager) {
        this(gitWorkspaceManager, null);
    }

    public WorktreeExecutionContext(GitWorkspaceManager gitWorkspaceManager, WorldmindMetrics metrics) {
        this.gitWorkspaceManager = gitWorkspaceManager;
        this.metrics = metrics;
    }

    /**
     * Creates or retrieves the mission workspace for a given mission.
     * The workspace is a full clone of the repository that serves as the
     * shared .git directory for all task worktrees.
     *
     * @param missionId unique mission identifier
     * @param gitUrl    authenticated git URL for cloning
     * @return path to the mission workspace, or null if creation failed
     */
    public Path createMissionWorkspace(String missionId, String gitUrl) {
        return missionWorkspaces.computeIfAbsent(missionId, id -> {
            log.info("Creating mission workspace for {}", missionId);
            return gitWorkspaceManager.createMissionWorkspace(id, gitUrl);
        });
    }

    /**
     * Gets the mission workspace path if it exists.
     *
     * @param missionId unique mission identifier
     * @return path to the workspace, or null if not created
     */
    public Path getMissionWorkspace(String missionId) {
        return missionWorkspaces.get(missionId);
    }

    /**
     * Acquires an isolated worktree for a task's execution.
     * Creates a new branch from baseBranch and sets up a separate working directory.
     *
     * @param missionId   the mission this task belongs to
     * @param taskId the task identifier (used for branch and directory naming)
     * @param baseBranch  branch to start from (typically "main")
     * @return path to the worktree directory, or null if acquisition failed
     */
    public Path acquireWorktree(String missionId, String taskId, String baseBranch) {
        Path workspace = missionWorkspaces.get(missionId);
        if (workspace == null) {
            log.error("Cannot acquire worktree: mission workspace {} not found", missionId);
            recordWorktreeMetric("acquire", false);
            return null;
        }

        // Check if already acquired (idempotent for retries)
        Path existing = taskWorktrees.get(taskId);
        if (existing != null) {
            log.info("Reusing existing worktree for {} at {}", taskId, existing);
            return existing;
        }

        var result = gitWorkspaceManager.addWorktree(workspace, taskId, baseBranch);
        if (result.success()) {
            taskWorktrees.put(taskId, result.worktreePath());
            log.info("Acquired worktree for {} at {}", taskId, result.worktreePath());
            recordWorktreeMetric("acquire", true);
            recordActiveWorktreesMetric();
            return result.worktreePath();
        }

        log.error("Failed to acquire worktree for {}: {}", taskId, result.error());
        recordWorktreeMetric("acquire", false);
        return null;
    }

    /**
     * Gets the worktree path for a task if it exists.
     *
     * @param taskId the task identifier
     * @return path to the worktree, or null if not acquired
     */
    public Path getWorktree(String taskId) {
        return taskWorktrees.get(taskId);
    }

    /**
     * Commits and pushes changes from a task's worktree.
     *
     * @param taskId the task identifier
     * @return true if commit and push succeeded (or no changes), false on error
     */
    public boolean commitAndPush(String taskId) {
        Path worktree = taskWorktrees.get(taskId);
        if (worktree == null) {
            log.warn("Cannot commit: worktree for {} not found", taskId);
            return false;
        }

        return gitWorkspaceManager.commitAndPushWorktree(worktree, taskId);
    }

    /**
     * Releases a task's worktree after execution completes.
     * The branch is preserved for subsequent merge operations.
     *
     * @param missionId   the mission this task belongs to
     * @param taskId the task identifier
     */
    public void releaseWorktree(String missionId, String taskId) {
        Path workspace = missionWorkspaces.get(missionId);
        if (workspace == null) {
            log.warn("Cannot release worktree: mission workspace {} not found", missionId);
            taskWorktrees.remove(taskId);
            recordWorktreeMetric("release", false);
            return;
        }

        Path worktree = taskWorktrees.remove(taskId);
        if (worktree != null) {
            boolean removed = gitWorkspaceManager.removeWorktree(workspace, taskId);
            recordWorktreeMetric("release", removed);
            log.info("Released worktree for {}", taskId);
        }
    }

    /**
     * Cleans up all worktrees and the mission workspace.
     * Call this when the mission completes or is cancelled.
     *
     * @param missionId the mission to clean up
     */
    public void cleanupMission(String missionId) {
        Path workspace = missionWorkspaces.remove(missionId);
        if (workspace == null) {
            log.debug("No workspace to clean up for mission {}", missionId);
            return;
        }

        // Remove all task worktrees that belong to this mission
        // (This is a fallback; ideally releaseWorktree was called for each)
        taskWorktrees.entrySet().removeIf(entry -> {
            Path worktreePath = entry.getValue();
            if (worktreePath.getParent().equals(workspace.getParent())) {
                gitWorkspaceManager.removeWorktree(workspace, entry.getKey());
                return true;
            }
            return false;
        });

        gitWorkspaceManager.cleanupMissionWorkspace(workspace);
        recordWorktreeMetric("cleanup", true);
        log.info("Cleaned up mission workspace for {}", missionId);
    }

    /**
     * Returns the number of active worktrees across all missions.
     * Useful for metrics and debugging.
     */
    public int getActiveWorktreeCount() {
        return taskWorktrees.size();
    }

    /**
     * Returns the number of active mission workspaces.
     */
    public int getActiveMissionCount() {
        return missionWorkspaces.size();
    }

    private void recordWorktreeMetric(String operation, boolean success) {
        if (metrics != null) {
            metrics.recordWorktreeOperation(operation, success);
        }
    }

    private void recordActiveWorktreesMetric() {
        if (metrics != null) {
            metrics.recordActiveWorktrees(taskWorktrees.size());
        }
    }
}
