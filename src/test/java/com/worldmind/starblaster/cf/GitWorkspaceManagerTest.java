package com.worldmind.starblaster.cf;

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

    // --- mergeDirectiveBranches ---

    @Test
    void mergeDirectiveBranchesClonesAndMergesEachBranch() {
        manager.setExitCode(0);
        manager.mergeDirectiveBranches(List.of("DIR-001", "DIR-002"), "ghp_token", null);

        var commands = manager.getExecutedCommands();
        // Should clone, config user, config email, checkout main,
        // then for each: fetch + merge, then push main, then 2x push --delete
        assertTrue(commands.stream().anyMatch(c -> c.contains("clone")), "Should clone: " + commands);
        assertTrue(commands.stream().anyMatch(c -> c.contains("checkout") && c.contains("main")), "Should checkout main: " + commands);
        assertTrue(commands.stream().anyMatch(c -> c.contains("fetch") && c.contains("worldmind/DIR-001")), "Should fetch DIR-001: " + commands);
        assertTrue(commands.stream().anyMatch(c -> c.contains("merge") && c.contains("worldmind/DIR-001")), "Should merge DIR-001: " + commands);
        assertTrue(commands.stream().anyMatch(c -> c.contains("fetch") && c.contains("worldmind/DIR-002")), "Should fetch DIR-002: " + commands);
        assertTrue(commands.stream().anyMatch(c -> c.contains("push") && c.contains("main")), "Should push main: " + commands);
        assertTrue(commands.stream().anyMatch(c -> c.contains("--delete") && c.contains("worldmind/DIR-001")), "Should delete DIR-001 branch: " + commands);
        assertTrue(commands.stream().anyMatch(c -> c.contains("--delete") && c.contains("worldmind/DIR-002")), "Should delete DIR-002 branch: " + commands);
    }

    @Test
    void mergeDirectiveBranchesSkipsMissingBranches() {
        // Return non-zero exit code for fetch (branch not found), then succeed for everything else
        manager.setExitCodeSequence(List.of(
                0,  // clone
                0,  // config user.name
                0,  // config user.email
                0,  // checkout main
                1,  // fetch DIR-001 (branch not found)
                0,  // fetch DIR-002
                0,  // merge DIR-002
                0,  // push main
                0,  // delete DIR-001
                0   // delete DIR-002
        ));
        manager.mergeDirectiveBranches(List.of("DIR-001", "DIR-002"), "ghp_token", null);

        var commands = manager.getExecutedCommands();
        // Should NOT have a merge for DIR-001 since fetch failed
        assertFalse(commands.stream().anyMatch(c -> c.contains("merge") && c.contains("worldmind/DIR-001")),
                "Should skip merge for missing branch: " + commands);
        // Should still merge DIR-002
        assertTrue(commands.stream().anyMatch(c -> c.contains("merge") && c.contains("worldmind/DIR-002")),
                "Should merge DIR-002: " + commands);
    }

    @Test
    void cleanupDirectiveBranchesDeletesWithoutMerging() {
        manager.setExitCode(0);
        manager.cleanupDirectiveBranches(List.of("DIR-001", "DIR-002"), "ghp_token", null);

        var commands = manager.getExecutedCommands();
        assertTrue(commands.stream().anyMatch(c -> c.contains("clone")), "Should clone: " + commands);
        assertTrue(commands.stream().anyMatch(c -> c.contains("--delete") && c.contains("worldmind/DIR-001")), "Should delete DIR-001: " + commands);
        assertTrue(commands.stream().anyMatch(c -> c.contains("--delete") && c.contains("worldmind/DIR-002")), "Should delete DIR-002: " + commands);
        assertFalse(commands.stream().anyMatch(c -> c.contains("merge")), "Should NOT merge: " + commands);
    }

    @Test
    void mergeDirectiveBranchesAbortsOnConflict() {
        // Simulate merge conflict on second merge
        manager.setExitCodeSequence(List.of(
                0,  // clone
                0,  // config user.name
                0,  // config user.email
                0,  // checkout main
                0,  // fetch DIR-001
                0,  // merge DIR-001
                0,  // fetch DIR-002
                1,  // merge DIR-002 (conflict!)
                0,  // merge --abort
                0,  // push main
                0,  // delete DIR-001
                0   // delete DIR-002
        ));
        manager.mergeDirectiveBranches(List.of("DIR-001", "DIR-002"), "ghp_token", null);

        var commands = manager.getExecutedCommands();
        assertTrue(commands.stream().anyMatch(c -> c.contains("merge") && c.contains("--abort")),
                "Should abort conflicting merge: " + commands);
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
    }
}
