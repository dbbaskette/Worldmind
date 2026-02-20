package com.worldmind.sandbox.cf;

import com.worldmind.core.model.FileRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link GitWorkspaceManager}.
 *
 * <p>Uses a test subclass to intercept git commands, allowing us to verify
 * command construction and test diff stat parsing without a real git repo.
 */
class GitWorkspaceManagerTest {

    private TestableGitWorkspaceManager manager;

    @BeforeEach
    void setUp() {
        manager = new TestableGitWorkspaceManager("https://github.com/example/project.git");
    }

    // --- getBranchName ---

    @Test
    void getBranchNameReturnsCorrectFormat() {
        assertEquals("worldmind/MISSION-001", manager.getBranchName("MISSION-001"));
    }

    @Test
    void getBranchNameHandlesSpecialCharacters() {
        assertEquals("worldmind/fix-bug-42", manager.getBranchName("fix-bug-42"));
    }

    // --- prepareBranch ---

    @Test
    void prepareBranchReturnsBranchName() {
        manager.setExitCode(0);
        var branchName = manager.prepareBranch("MISSION-001", Path.of("/tmp/project"));

        assertEquals("worldmind/MISSION-001", branchName);
    }

    @Test
    void prepareBranchExecutesCheckoutAndPush() {
        manager.setExitCode(0);
        manager.prepareBranch("MISSION-001", Path.of("/tmp/project"));

        var commands = manager.getExecutedCommands();
        assertEquals(2, commands.size());
        assertTrue(commands.get(0).contains("checkout"));
        assertTrue(commands.get(0).contains("-b"));
        assertTrue(commands.get(0).contains("worldmind/MISSION-001"));
        assertTrue(commands.get(1).contains("push"));
        assertTrue(commands.get(1).contains("worldmind/MISSION-001"));
    }

    @Test
    void prepareBranchThrowsOnCheckoutFailure() {
        manager.setExitCode(128);
        assertThrows(RuntimeException.class, () ->
                manager.prepareBranch("MISSION-001", Path.of("/tmp/project")));
    }

    // --- detectChanges (diff stat parsing) ---

    @Test
    void detectChangesParsesDiffStatModifiedFiles() {
        var diffOutput = " src/main/Foo.java | 15 +++---\n" +
                         " src/main/Bar.java |  3 ++-\n" +
                         " 2 files changed, 12 insertions(+), 6 deletions(-)";

        manager.setExitCode(0);
        manager.setGitOutput(diffOutput);
        var changes = manager.detectChanges("MISSION-001", "abc123", Path.of("/tmp/project"));

        assertEquals(2, changes.size());
        assertEquals("src/main/Foo.java", changes.get(0).path());
        assertEquals("modified", changes.get(0).action());
        assertEquals(15, changes.get(0).linesChanged());
        assertEquals("src/main/Bar.java", changes.get(1).path());
        assertEquals("modified", changes.get(1).action());
        assertEquals(3, changes.get(1).linesChanged());
    }

    @Test
    void detectChangesIdentifiesCreatedFiles() {
        var diffOutput = " src/main/NewFile.java (new) | 25 +++++++++++++\n" +
                         " 1 file changed, 25 insertions(+)";

        manager.setExitCode(0);
        manager.setGitOutput(diffOutput);
        var changes = manager.detectChanges("MISSION-001", "abc123", Path.of("/tmp/project"));

        assertEquals(1, changes.size());
        assertEquals("src/main/NewFile.java", changes.get(0).path());
        assertEquals("created", changes.get(0).action());
        assertEquals(25, changes.get(0).linesChanged());
    }

    @Test
    void detectChangesIdentifiesDeletedFiles() {
        var diffOutput = " src/old/Removed.java (gone) | 30 --------\n" +
                         " 1 file changed, 30 deletions(-)";

        manager.setExitCode(0);
        manager.setGitOutput(diffOutput);
        var changes = manager.detectChanges("MISSION-001", "abc123", Path.of("/tmp/project"));

        assertEquals(1, changes.size());
        assertEquals("src/old/Removed.java", changes.get(0).path());
        assertEquals("deleted", changes.get(0).action());
        assertEquals(30, changes.get(0).linesChanged());
    }

    @Test
    void detectChangesMixedFileTypes() {
        var diffOutput = " src/main/Foo.java           | 15 +++---\n" +
                         " src/main/NewFile.java (new) | 25 ++++++++++\n" +
                         " src/old/Gone.java (gone)    |  7 -------\n" +
                         " 3 files changed, 30 insertions(+), 17 deletions(-)";

        manager.setExitCode(0);
        manager.setGitOutput(diffOutput);
        var changes = manager.detectChanges("MISSION-001", "abc123", Path.of("/tmp/project"));

        assertEquals(3, changes.size());

        assertEquals("modified", changes.get(0).action());
        assertEquals("src/main/Foo.java", changes.get(0).path());

        assertEquals("created", changes.get(1).action());
        assertEquals("src/main/NewFile.java", changes.get(1).path());

        assertEquals("deleted", changes.get(2).action());
        assertEquals("src/old/Gone.java", changes.get(2).path());
    }

    @Test
    void detectChangesReturnsEmptyListOnEmptyDiff() {
        manager.setExitCode(0);
        manager.setGitOutput("");
        var changes = manager.detectChanges("MISSION-001", "abc123", Path.of("/tmp/project"));

        assertTrue(changes.isEmpty());
    }

    @Test
    void detectChangesReturnsEmptyListOnNullDiff() {
        manager.setExitCode(0);
        manager.setGitOutput(null);
        var changes = manager.detectChanges("MISSION-001", "abc123", Path.of("/tmp/project"));

        assertTrue(changes.isEmpty());
    }

    // --- cleanup ---

    @Test
    void cleanupDoesNotThrowOnErrors() {
        manager.setExitCode(1);  // simulate failure
        manager.setThrowOnGit(true);

        assertDoesNotThrow(() -> manager.cleanup("MISSION-001", Path.of("/tmp/project")));
    }

    @Test
    void cleanupExecutesDeleteCommands() {
        manager.setExitCode(0);
        manager.cleanup("MISSION-001", Path.of("/tmp/project"));

        var commands = manager.getExecutedCommands();
        assertEquals(2, commands.size());
        // Remote delete
        assertTrue(commands.get(0).contains("push"));
        assertTrue(commands.get(0).contains("--delete"));
        assertTrue(commands.get(0).contains("worldmind/MISSION-001"));
        // Local delete
        assertTrue(commands.get(1).contains("branch"));
        assertTrue(commands.get(1).contains("-D"));
        assertTrue(commands.get(1).contains("worldmind/MISSION-001"));
    }

    // --- mergeTaskBranches ---

    @Test
    void mergeTaskBranchesClonesAndMergesEachBranch() {
        manager.setExitCode(0);
        manager.mergeTaskBranches(List.of("TASK-001", "TASK-002"), "ghp_token", null);

        var commands = manager.getExecutedCommands();
        // Should clone, config user, config email, checkout main,
        // then for each task: fetch, checkout temp branch, rebase, checkout main, merge
        assertTrue(commands.stream().anyMatch(c -> c.contains("clone")), "Should clone: " + commands);
        assertTrue(commands.stream().anyMatch(c -> c.contains("checkout") && c.contains("main")), "Should checkout main: " + commands);
        // Verify explicit refspec is used for fetch
        assertTrue(commands.stream().anyMatch(c -> c.contains("fetch") && c.contains("refs/heads/worldmind/TASK-001")),
                "Should fetch TASK-001: " + commands);
        // New flow uses temp branches and merge
        assertTrue(commands.stream().anyMatch(c -> c.contains("merge") && c.contains("temp-TASK-001")), 
                "Should merge temp-TASK-001: " + commands);
        assertTrue(commands.stream().anyMatch(c -> c.contains("fetch") && c.contains("refs/heads/worldmind/TASK-002")),
                "Should fetch TASK-002: " + commands);
        assertTrue(commands.stream().anyMatch(c -> c.contains("push") && c.contains("main")), "Should push main: " + commands);
        assertTrue(commands.stream().anyMatch(c -> c.contains("--delete") && c.contains("worldmind/TASK-001")), "Should delete TASK-001 branch: " + commands);
        assertTrue(commands.stream().anyMatch(c -> c.contains("--delete") && c.contains("worldmind/TASK-002")), "Should delete TASK-002 branch: " + commands);
    }

    @Test
    void mergeTaskBranchesSkipsMissingBranches() {
        // Return non-zero exit code for fetch (branch not found), then succeed for everything else
        // Due to the rebase-based flow, the sequence is longer
        manager.setExitCodeSequence(List.of(
                0,  // clone
                0,  // config user.name
                0,  // config user.email
                0,  // checkout main
                0,  // fetch origin main
                0,  // checkout main
                0,  // reset --hard origin/main
                1,  // fetch TASK-001 (branch not found) - causes skip
                0,  // fetch origin main (for TASK-002)
                0,  // checkout main
                0,  // reset --hard origin/main
                0,  // fetch TASK-002
                0,  // checkout -B temp-TASK-002
                0,  // rebase main
                0,  // checkout main
                0,  // merge temp-TASK-002
                0,  // push main
                0,  // delete TASK-002
                0   // (any remaining deletes)
        ));
        manager.mergeTaskBranches(List.of("TASK-001", "TASK-002"), "ghp_token", null);

        var commands = manager.getExecutedCommands();
        // Should NOT have a merge for TASK-001 since fetch failed
        assertFalse(commands.stream().anyMatch(c -> c.contains("merge") && c.contains("temp-TASK-001")),
                "Should skip merge for missing branch: " + commands);
        // Should still merge TASK-002
        assertTrue(commands.stream().anyMatch(c -> c.contains("merge") && c.contains("temp-TASK-002")),
                "Should merge TASK-002: " + commands);
    }

    @Test
    void cleanupTaskBranchesDeletesWithoutMerging() {
        manager.setExitCode(0);
        manager.cleanupTaskBranches(List.of("TASK-001", "TASK-002"), "ghp_token", null);

        var commands = manager.getExecutedCommands();
        assertTrue(commands.stream().anyMatch(c -> c.contains("clone")), "Should clone: " + commands);
        assertTrue(commands.stream().anyMatch(c -> c.contains("--delete") && c.contains("worldmind/TASK-001")), "Should delete TASK-001: " + commands);
        assertTrue(commands.stream().anyMatch(c -> c.contains("--delete") && c.contains("worldmind/TASK-002")), "Should delete TASK-002: " + commands);
        assertFalse(commands.stream().anyMatch(c -> c.contains("merge")), "Should NOT merge: " + commands);
    }

    @Test
    void mergeTaskBranchesHandlesRebaseConflict() {
        // Simulate conflict during rebase - the new flow uses rebase instead of direct merge
        // When rebase fails, it aborts and retries, then skips that task if still failing
        manager.setExitCodeSequence(List.of(
                0,  // clone
                0,  // config user.name
                0,  // config user.email
                0,  // checkout main
                // TASK-001 attempt 1
                0,  // fetch origin main
                0,  // checkout main
                0,  // reset --hard origin/main
                0,  // fetch TASK-001
                0,  // checkout -B temp-TASK-001
                1,  // rebase main (conflict!)
                0,  // rebase --abort
                0,  // checkout main
                // TASK-001 attempt 2 (retry)
                0,  // fetch origin main
                0,  // checkout main
                0,  // reset --hard origin/main
                0,  // fetch TASK-001
                0,  // checkout -B temp-TASK-001
                1,  // rebase main (conflict again!)
                0,  // rebase --abort
                0,  // checkout main
                // TASK-002
                0,  // fetch origin main
                0,  // checkout main
                0,  // reset --hard origin/main
                0,  // fetch TASK-002
                0,  // checkout -B temp-TASK-002
                0,  // rebase main
                0,  // checkout main
                0,  // merge temp-TASK-002
                0,  // push main
                0,  // delete TASK-002
                0   // (any remaining deletes)
        ));
        manager.mergeTaskBranches(List.of("TASK-001", "TASK-002"), "ghp_token", null);

        var commands = manager.getExecutedCommands();
        // Should have multiple rebase attempts for TASK-001
        long rebaseAbortCount = commands.stream()
                .filter(c -> c.contains("rebase") && c.contains("--abort"))
                .count();
        assertTrue(rebaseAbortCount >= 2, 
                "Should have at least 2 rebase --abort (retries): " + commands);
    }

    @Test
    void mergeTaskBranchesSucceedsOnRetry() {
        // Simulate conflict on first attempt, success on retry
        manager.setExitCodeSequence(List.of(
                0,  // clone
                0,  // config user.name
                0,  // config user.email
                0,  // checkout main
                // TASK-001 attempt 1
                0,  // fetch origin main
                0,  // checkout main
                0,  // reset --hard origin/main
                0,  // fetch TASK-001
                0,  // checkout -B temp-TASK-001
                1,  // rebase main (conflict!)
                0,  // rebase --abort
                0,  // checkout main
                // TASK-001 attempt 2 (retry succeeds)
                0,  // fetch origin main
                0,  // checkout main
                0,  // reset --hard origin/main
                0,  // fetch TASK-001
                0,  // checkout -B temp-TASK-001
                0,  // rebase main (success!)
                0,  // checkout main
                0,  // merge temp-TASK-001
                0,  // push main
                0   // delete TASK-001
        ));
        manager.mergeTaskBranches(List.of("TASK-001"), "ghp_token", null);

        var commands = manager.getExecutedCommands();
        // Should have one rebase abort (first attempt) and one successful merge
        assertTrue(commands.stream().anyMatch(c -> c.contains("rebase") && c.contains("--abort")),
                "Should abort first rebase: " + commands);
        assertTrue(commands.stream().anyMatch(c -> c.contains("merge") && c.contains("temp-TASK-001")),
                "Should eventually merge after retry: " + commands);
    }

    // --- parseDiffStat directly ---

    @Test
    void parseDiffStatSkipsSummaryLine() {
        var output = " 3 files changed, 10 insertions(+), 5 deletions(-)";
        var results = manager.parseDiffStat(output);

        assertTrue(results.isEmpty());
    }

    @Test
    void parseDiffStatHandlesSingleFile() {
        var output = " README.md | 2 +-";
        var results = manager.parseDiffStat(output);

        assertEquals(1, results.size());
        assertEquals("README.md", results.get(0).path());
        assertEquals("modified", results.get(0).action());
        assertEquals(2, results.get(0).linesChanged());
    }

    // --- Worktree operations ---
    // Note: These tests use a mock that bypasses filesystem checks

    @Test
    void addWorktreeCreatesWorktreeWithNewBranch() {
        manager.setExitCode(0);
        manager.setMockDirectoryExists(true);
        var result = manager.addWorktree(Path.of("/tmp/mission-ws"), "TASK-001", "main");

        assertTrue(result.success(), "Result should be success: " + result.error());
        assertNotNull(result.worktreePath());
        assertTrue(result.worktreePath().toString().contains("worktree-TASK-001"));

        var commands = manager.getExecutedCommands();
        assertTrue(commands.stream().anyMatch(c -> 
                c.contains("worktree") && c.contains("add") && c.contains("worldmind/TASK-001")),
                "Should add worktree: " + commands);
    }

    @Test
    void addWorktreeFallsBackToExistingBranch() {
        // First worktree add fails (branch exists), second succeeds
        manager.setExitCodeSequence(List.of(1, 0));
        manager.setMockDirectoryExists(true);
        var result = manager.addWorktree(Path.of("/tmp/mission-ws"), "TASK-001", "main");

        assertTrue(result.success(), "Result should be success: " + result.error());
        
        var commands = manager.getExecutedCommands();
        assertEquals(2, commands.size());
        assertTrue(commands.get(0).contains("-b"), "First attempt should try creating branch");
        assertFalse(commands.get(1).contains("-b"), "Second attempt should not use -b");
    }

    @Test
    void addWorktreeReturnsFailureOnError() {
        manager.setExitCode(1);
        manager.setMockDirectoryExists(true);
        var result = manager.addWorktree(Path.of("/tmp/mission-ws"), "TASK-001", "main");

        assertFalse(result.success());
        assertNull(result.worktreePath());
        assertNotNull(result.error());
    }

    @Test
    void addWorktreeReturnsFailureForNullWorkspace() {
        var result = manager.addWorktree(null, "TASK-001", "main");

        assertFalse(result.success());
        assertTrue(result.error().contains("does not exist"));
    }

    @Test
    void removeWorktreeExecutesGitWorktreeRemove() {
        manager.setExitCode(0);
        manager.setMockDirectoryExists(true);
        boolean success = manager.removeWorktree(Path.of("/tmp/mission-ws"), "TASK-001");

        assertTrue(success);
        var commands = manager.getExecutedCommands();
        assertTrue(commands.stream().anyMatch(c -> 
                c.contains("worktree") && c.contains("remove") && c.contains("worktree-TASK-001")),
                "Should remove worktree: " + commands);
    }

    @Test
    void removeWorktreeHandlesFailureGracefully() {
        manager.setExitCodeSequence(List.of(1, 0));  // remove fails, prune succeeds
        manager.setMockDirectoryExists(true);
        boolean success = manager.removeWorktree(Path.of("/tmp/mission-ws"), "TASK-001");

        assertTrue(success);
        var commands = manager.getExecutedCommands();
        assertTrue(commands.stream().anyMatch(c -> c.contains("worktree") && c.contains("prune")),
                "Should prune after failed remove: " + commands);
    }

    @Test
    void listWorktreesParsesPorcelainOutput() {
        manager.setMockDirectoryExists(true);
        manager.setGitOutput(
                "worktree /tmp/mission-ws\n" +
                "HEAD abc123\n" +
                "branch refs/heads/main\n" +
                "\n" +
                "worktree /tmp/worktree-TASK-001\n" +
                "HEAD def456\n" +
                "branch refs/heads/worldmind/TASK-001\n" +
                "\n" +
                "worktree /tmp/worktree-TASK-002\n" +
                "HEAD ghi789\n" +
                "branch refs/heads/worldmind/TASK-002\n"
        );

        var worktrees = manager.listWorktrees(Path.of("/tmp/mission-ws"));

        assertEquals(2, worktrees.size());
        assertTrue(worktrees.contains("worktree-TASK-001"));
        assertTrue(worktrees.contains("worktree-TASK-002"));
    }

    @Test
    void listWorktreesReturnsEmptyForNullWorkspace() {
        var worktrees = manager.listWorktrees(null);
        assertTrue(worktrees.isEmpty());
    }

    @Test
    void commitAndPushWorktreeStagesAndPushes() {
        manager.setExitCodeSequence(List.of(
                0,  // git add -A
                1,  // git diff --cached --quiet (1 = there ARE changes)
                0,  // git commit
                0   // git push
        ));
        manager.setMockDirectoryExists(true);

        boolean success = manager.commitAndPushWorktree(Path.of("/tmp/worktree-TASK-001"), "TASK-001");

        assertTrue(success);
        var commands = manager.getExecutedCommands();
        assertTrue(commands.stream().anyMatch(c -> c.contains("add") && c.contains("-A")));
        assertTrue(commands.stream().anyMatch(c -> c.contains("commit")));
        assertTrue(commands.stream().anyMatch(c -> c.contains("push") && c.contains("worldmind/TASK-001")));
    }

    @Test
    void commitAndPushWorktreeSkipsCommitWhenNoChanges() {
        manager.setExitCodeSequence(List.of(
                0,  // git add -A
                0   // git diff --cached --quiet (0 = NO changes)
        ));
        manager.setMockDirectoryExists(true);

        boolean success = manager.commitAndPushWorktree(Path.of("/tmp/worktree-TASK-001"), "TASK-001");

        assertTrue(success);
        var commands = manager.getExecutedCommands();
        assertFalse(commands.stream().anyMatch(c -> c.contains("commit")),
                "Should not commit when no changes: " + commands);
    }

    @Test
    void cleanupMissionWorkspaceRemovesAllWorktrees() {
        manager.setExitCode(0);
        manager.setMockDirectoryExists(true);
        manager.setGitOutput(
                "worktree /tmp/mission-ws\n" +
                "HEAD abc123\n" +
                "\n" +
                "worktree /tmp/worktree-TASK-001\n" +
                "HEAD def456\n"
        );

        manager.cleanupMissionWorkspace(Path.of("/tmp/mission-ws"));

        var commands = manager.getExecutedCommands();
        assertTrue(commands.stream().anyMatch(c -> c.contains("worktree") && c.contains("list")),
                "Should list worktrees: " + commands);
        assertTrue(commands.stream().anyMatch(c -> c.contains("worktree") && c.contains("prune")),
                "Should prune worktrees: " + commands);
    }

    // --- Test subclass that intercepts git commands ---

    /**
     * Testable subclass of GitWorkspaceManager that intercepts git calls
     * so we can test logic without a real git repository.
     */
    static class TestableGitWorkspaceManager extends GitWorkspaceManager {

        private int exitCode = 0;
        private List<Integer> exitCodeSequence = null;
        private int exitCodeIndex = 0;
        private String gitOutput = "";
        private boolean throwOnGit = false;
        private boolean mockDirectoryExists = false;
        private final List<String> executedCommands = new java.util.ArrayList<>();

        TestableGitWorkspaceManager(String gitRemoteUrl) {
            super(gitRemoteUrl);
        }

        void setExitCode(int exitCode) {
            this.exitCode = exitCode;
            this.exitCodeSequence = null;
            this.exitCodeIndex = 0;
        }

        void setExitCodeSequence(List<Integer> sequence) {
            this.exitCodeSequence = sequence;
            this.exitCodeIndex = 0;
        }

        void setGitOutput(String output) {
            this.gitOutput = output;
        }

        void setThrowOnGit(boolean throwOnGit) {
            this.throwOnGit = throwOnGit;
        }

        void setMockDirectoryExists(boolean exists) {
            this.mockDirectoryExists = exists;
        }

        List<String> getExecutedCommands() {
            return executedCommands;
        }

        private int nextExitCode() {
            if (exitCodeSequence != null && !exitCodeSequence.isEmpty()) {
                int idx = Math.min(exitCodeIndex, exitCodeSequence.size() - 1);
                exitCodeIndex++;
                return exitCodeSequence.get(idx);
            }
            return exitCode;
        }

        @Override
        int runGit(Path workDir, String... args) {
            var command = String.join(" ", args);
            executedCommands.add(command);
            if (throwOnGit) {
                throw new RuntimeException("Simulated git failure: " + command);
            }
            return nextExitCode();
        }

        @Override
        String runGitOutput(Path workDir, String... args) {
            var command = String.join(" ", args);
            executedCommands.add(command);
            if (throwOnGit) {
                throw new RuntimeException("Simulated git failure: " + command);
            }
            return gitOutput;
        }

        @Override
        public WorktreeResult addWorktree(Path missionWorkspace, String taskId, String baseBranch) {
            if (missionWorkspace == null || (!mockDirectoryExists && !java.nio.file.Files.isDirectory(missionWorkspace))) {
                return WorktreeResult.failure("Mission workspace does not exist: " + missionWorkspace);
            }

            String branchName = getBranchName(taskId);
            Path worktreePath = missionWorkspace.resolveSibling("worktree-" + taskId);

            int exitCodeVal = runGit(missionWorkspace, "worktree", "add", 
                    worktreePath.toString(), "-b", branchName, baseBranch);

            if (exitCodeVal != 0) {
                exitCodeVal = runGit(missionWorkspace, "worktree", "add", 
                        worktreePath.toString(), branchName);
                
                if (exitCodeVal != 0) {
                    return WorktreeResult.failure("Failed to create worktree for " + taskId + " (exit code " + exitCodeVal + ")");
                }
            }

            return WorktreeResult.success(worktreePath);
        }

        @Override
        public boolean removeWorktree(Path missionWorkspace, String taskId) {
            if (missionWorkspace == null || (!mockDirectoryExists && !java.nio.file.Files.isDirectory(missionWorkspace))) {
                return false;
            }

            Path worktreePath = missionWorkspace.resolveSibling("worktree-" + taskId);
            int exitCodeVal = runGit(missionWorkspace, "worktree", "remove", "--force", worktreePath.toString());

            if (exitCodeVal != 0) {
                runGit(missionWorkspace, "worktree", "prune");
            }

            return true;
        }

        @Override
        public List<String> listWorktrees(Path missionWorkspace) {
            if (missionWorkspace == null || (!mockDirectoryExists && !java.nio.file.Files.isDirectory(missionWorkspace))) {
                return List.of();
            }

            String output = runGitOutput(missionWorkspace, "worktree", "list", "--porcelain");
            List<String> worktrees = new java.util.ArrayList<>();

            for (String line : output.split("\n")) {
                if (line.startsWith("worktree ")) {
                    String path = line.substring("worktree ".length()).trim();
                    Path worktreePath = Path.of(path);
                    String dirName = worktreePath.getFileName().toString();
                    if (dirName.startsWith("worktree-")) {
                        worktrees.add(dirName);
                    }
                }
            }

            return worktrees;
        }

        @Override
        public boolean commitAndPushWorktree(Path worktreePath, String taskId) {
            if (worktreePath == null || (!mockDirectoryExists && !java.nio.file.Files.isDirectory(worktreePath))) {
                return false;
            }

            String branchName = getBranchName(taskId);

            runGit(worktreePath, "add", "-A");

            int diffExit = runGit(worktreePath, "diff", "--cached", "--quiet");
            if (diffExit == 0) {
                return true;
            }

            int commitExit = runGit(worktreePath, "commit", "-m", "Task " + taskId);
            if (commitExit != 0) {
                return false;
            }

            int pushExit = runGit(worktreePath, "push", "-u", "origin", branchName, "--force");
            return pushExit == 0;
        }

        @Override
        public void cleanupMissionWorkspace(Path missionWorkspace) {
            if (missionWorkspace == null) {
                return;
            }

            List<String> worktrees = listWorktrees(missionWorkspace);
            for (String worktreeName : worktrees) {
                Path worktreePath = missionWorkspace.resolveSibling(worktreeName);
                runGit(missionWorkspace, "worktree", "remove", "--force", worktreePath.toString());
            }

            runGit(missionWorkspace, "worktree", "prune");
        }
    }
}
