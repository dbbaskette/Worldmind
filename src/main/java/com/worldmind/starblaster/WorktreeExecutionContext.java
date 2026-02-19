package com.worldmind.starblaster;

import com.worldmind.starblaster.cf.GitWorkspaceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages worktree-based execution contexts for parallel directive execution.
 *
 * <p>This class provides isolated working directories for each directive by leveraging
 * git worktrees. Each directive gets its own worktree branching from main, allowing
 * parallel execution without file conflicts.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>Mission starts: {@link #createMissionWorkspace} clones the repository</li>
 *   <li>Per directive: {@link #acquireWorktree} creates an isolated worktree</li>
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

    /** Maps missionId to the mission workspace path (the main clone). */
    private final ConcurrentHashMap<String, Path> missionWorkspaces = new ConcurrentHashMap<>();

    /** Maps directiveId to its worktree path. */
    private final ConcurrentHashMap<String, Path> directiveWorktrees = new ConcurrentHashMap<>();

    public WorktreeExecutionContext(GitWorkspaceManager gitWorkspaceManager) {
        this.gitWorkspaceManager = gitWorkspaceManager;
    }

    /**
     * Creates or retrieves the mission workspace for a given mission.
     * The workspace is a full clone of the repository that serves as the
     * shared .git directory for all directive worktrees.
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
     * Acquires an isolated worktree for a directive's execution.
     * Creates a new branch from baseBranch and sets up a separate working directory.
     *
     * @param missionId   the mission this directive belongs to
     * @param directiveId the directive identifier (used for branch and directory naming)
     * @param baseBranch  branch to start from (typically "main")
     * @return path to the worktree directory, or null if acquisition failed
     */
    public Path acquireWorktree(String missionId, String directiveId, String baseBranch) {
        Path workspace = missionWorkspaces.get(missionId);
        if (workspace == null) {
            log.error("Cannot acquire worktree: mission workspace {} not found", missionId);
            return null;
        }

        // Check if already acquired (idempotent for retries)
        Path existing = directiveWorktrees.get(directiveId);
        if (existing != null) {
            log.info("Reusing existing worktree for {} at {}", directiveId, existing);
            return existing;
        }

        var result = gitWorkspaceManager.addWorktree(workspace, directiveId, baseBranch);
        if (result.success()) {
            directiveWorktrees.put(directiveId, result.worktreePath());
            log.info("Acquired worktree for {} at {}", directiveId, result.worktreePath());
            return result.worktreePath();
        }

        log.error("Failed to acquire worktree for {}: {}", directiveId, result.error());
        return null;
    }

    /**
     * Gets the worktree path for a directive if it exists.
     *
     * @param directiveId the directive identifier
     * @return path to the worktree, or null if not acquired
     */
    public Path getWorktree(String directiveId) {
        return directiveWorktrees.get(directiveId);
    }

    /**
     * Commits and pushes changes from a directive's worktree.
     *
     * @param directiveId the directive identifier
     * @return true if commit and push succeeded (or no changes), false on error
     */
    public boolean commitAndPush(String directiveId) {
        Path worktree = directiveWorktrees.get(directiveId);
        if (worktree == null) {
            log.warn("Cannot commit: worktree for {} not found", directiveId);
            return false;
        }

        return gitWorkspaceManager.commitAndPushWorktree(worktree, directiveId);
    }

    /**
     * Releases a directive's worktree after execution completes.
     * The branch is preserved for subsequent merge operations.
     *
     * @param missionId   the mission this directive belongs to
     * @param directiveId the directive identifier
     */
    public void releaseWorktree(String missionId, String directiveId) {
        Path workspace = missionWorkspaces.get(missionId);
        if (workspace == null) {
            log.warn("Cannot release worktree: mission workspace {} not found", missionId);
            directiveWorktrees.remove(directiveId);
            return;
        }

        Path worktree = directiveWorktrees.remove(directiveId);
        if (worktree != null) {
            gitWorkspaceManager.removeWorktree(workspace, directiveId);
            log.info("Released worktree for {}", directiveId);
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

        // Remove all directive worktrees that belong to this mission
        // (This is a fallback; ideally releaseWorktree was called for each)
        directiveWorktrees.entrySet().removeIf(entry -> {
            Path worktreePath = entry.getValue();
            if (worktreePath.getParent().equals(workspace.getParent())) {
                gitWorkspaceManager.removeWorktree(workspace, entry.getKey());
                return true;
            }
            return false;
        });

        gitWorkspaceManager.cleanupMissionWorkspace(workspace);
        log.info("Cleaned up mission workspace for {}", missionId);
    }

    /**
     * Returns the number of active worktrees across all missions.
     * Useful for metrics and debugging.
     */
    public int getActiveWorktreeCount() {
        return directiveWorktrees.size();
    }

    /**
     * Returns the number of active mission workspaces.
     */
    public int getActiveMissionCount() {
        return missionWorkspaces.size();
    }
}
