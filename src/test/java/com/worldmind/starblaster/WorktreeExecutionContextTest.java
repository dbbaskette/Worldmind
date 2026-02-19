package com.worldmind.starblaster;

import com.worldmind.starblaster.cf.GitWorkspaceManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WorktreeExecutionContextTest {

    @Mock
    private GitWorkspaceManager gitWorkspaceManager;

    private WorktreeExecutionContext context;

    @BeforeEach
    void setUp() {
        context = new WorktreeExecutionContext(gitWorkspaceManager);
    }

    @Test
    void createMissionWorkspaceCallsGitManager() {
        Path expectedPath = Path.of("/tmp/workspace");
        when(gitWorkspaceManager.createMissionWorkspace("MISSION-001", "https://github.com/test/repo.git"))
                .thenReturn(expectedPath);

        Path result = context.createMissionWorkspace("MISSION-001", "https://github.com/test/repo.git");

        assertEquals(expectedPath, result);
        verify(gitWorkspaceManager).createMissionWorkspace("MISSION-001", "https://github.com/test/repo.git");
    }

    @Test
    void createMissionWorkspaceIsCached() {
        Path expectedPath = Path.of("/tmp/workspace");
        when(gitWorkspaceManager.createMissionWorkspace(eq("MISSION-001"), anyString()))
                .thenReturn(expectedPath);

        context.createMissionWorkspace("MISSION-001", "https://github.com/test/repo.git");
        context.createMissionWorkspace("MISSION-001", "https://github.com/test/repo.git");

        verify(gitWorkspaceManager, times(1)).createMissionWorkspace(anyString(), anyString());
    }

    @Test
    void getMissionWorkspaceReturnsNullForUnknownMission() {
        assertNull(context.getMissionWorkspace("UNKNOWN"));
    }

    @Test
    void acquireWorktreeCreatesWorktree() {
        Path workspacePath = Path.of("/tmp/workspace");
        Path worktreePath = Path.of("/tmp/worktree-DIR-001");

        when(gitWorkspaceManager.createMissionWorkspace(anyString(), anyString()))
                .thenReturn(workspacePath);
        when(gitWorkspaceManager.addWorktree(workspacePath, "DIR-001", "main"))
                .thenReturn(GitWorkspaceManager.WorktreeResult.success(worktreePath));

        context.createMissionWorkspace("MISSION-001", "https://github.com/test/repo.git");
        Path result = context.acquireWorktree("MISSION-001", "DIR-001", "main");

        assertEquals(worktreePath, result);
        verify(gitWorkspaceManager).addWorktree(workspacePath, "DIR-001", "main");
    }

    @Test
    void acquireWorktreeReturnsNullWhenMissionWorkspaceNotFound() {
        Path result = context.acquireWorktree("MISSION-001", "DIR-001", "main");

        assertNull(result);
        verify(gitWorkspaceManager, never()).addWorktree(any(), any(), any());
    }

    @Test
    void acquireWorktreeReusesExistingWorktree() {
        Path workspacePath = Path.of("/tmp/workspace");
        Path worktreePath = Path.of("/tmp/worktree-DIR-001");

        when(gitWorkspaceManager.createMissionWorkspace(anyString(), anyString()))
                .thenReturn(workspacePath);
        when(gitWorkspaceManager.addWorktree(workspacePath, "DIR-001", "main"))
                .thenReturn(GitWorkspaceManager.WorktreeResult.success(worktreePath));

        context.createMissionWorkspace("MISSION-001", "https://github.com/test/repo.git");
        context.acquireWorktree("MISSION-001", "DIR-001", "main");
        context.acquireWorktree("MISSION-001", "DIR-001", "main");

        verify(gitWorkspaceManager, times(1)).addWorktree(any(), any(), any());
    }

    @Test
    void acquireWorktreeReturnsNullOnFailure() {
        Path workspacePath = Path.of("/tmp/workspace");

        when(gitWorkspaceManager.createMissionWorkspace(anyString(), anyString()))
                .thenReturn(workspacePath);
        when(gitWorkspaceManager.addWorktree(any(), any(), any()))
                .thenReturn(GitWorkspaceManager.WorktreeResult.failure("Git error"));

        context.createMissionWorkspace("MISSION-001", "https://github.com/test/repo.git");
        Path result = context.acquireWorktree("MISSION-001", "DIR-001", "main");

        assertNull(result);
    }

    @Test
    void getWorktreeReturnsNullForUnknownDirective() {
        assertNull(context.getWorktree("UNKNOWN"));
    }

    @Test
    void commitAndPushDelegatesToGitManager() {
        Path workspacePath = Path.of("/tmp/workspace");
        Path worktreePath = Path.of("/tmp/worktree-DIR-001");

        when(gitWorkspaceManager.createMissionWorkspace(anyString(), anyString()))
                .thenReturn(workspacePath);
        when(gitWorkspaceManager.addWorktree(any(), any(), any()))
                .thenReturn(GitWorkspaceManager.WorktreeResult.success(worktreePath));
        when(gitWorkspaceManager.commitAndPushWorktree(worktreePath, "DIR-001"))
                .thenReturn(true);

        context.createMissionWorkspace("MISSION-001", "https://github.com/test/repo.git");
        context.acquireWorktree("MISSION-001", "DIR-001", "main");
        boolean result = context.commitAndPush("DIR-001");

        assertTrue(result);
        verify(gitWorkspaceManager).commitAndPushWorktree(worktreePath, "DIR-001");
    }

    @Test
    void commitAndPushReturnsFalseForUnknownDirective() {
        boolean result = context.commitAndPush("UNKNOWN");

        assertFalse(result);
        verify(gitWorkspaceManager, never()).commitAndPushWorktree(any(), any());
    }

    @Test
    void releaseWorktreeRemovesWorktree() {
        Path workspacePath = Path.of("/tmp/workspace");
        Path worktreePath = Path.of("/tmp/worktree-DIR-001");

        when(gitWorkspaceManager.createMissionWorkspace(anyString(), anyString()))
                .thenReturn(workspacePath);
        when(gitWorkspaceManager.addWorktree(any(), any(), any()))
                .thenReturn(GitWorkspaceManager.WorktreeResult.success(worktreePath));
        when(gitWorkspaceManager.removeWorktree(any(), any())).thenReturn(true);

        context.createMissionWorkspace("MISSION-001", "https://github.com/test/repo.git");
        context.acquireWorktree("MISSION-001", "DIR-001", "main");
        context.releaseWorktree("MISSION-001", "DIR-001");

        verify(gitWorkspaceManager).removeWorktree(workspacePath, "DIR-001");
        assertNull(context.getWorktree("DIR-001"));
    }

    @Test
    void cleanupMissionRemovesAllWorktrees() {
        Path workspacePath = Path.of("/tmp/workspace");

        when(gitWorkspaceManager.createMissionWorkspace(anyString(), anyString()))
                .thenReturn(workspacePath);

        context.createMissionWorkspace("MISSION-001", "https://github.com/test/repo.git");
        context.cleanupMission("MISSION-001");

        verify(gitWorkspaceManager).cleanupMissionWorkspace(workspacePath);
        assertNull(context.getMissionWorkspace("MISSION-001"));
    }

    @Test
    void cleanupMissionDoesNothingForUnknownMission() {
        context.cleanupMission("UNKNOWN");

        verify(gitWorkspaceManager, never()).cleanupMissionWorkspace(any());
    }

    @Test
    void getActiveWorktreeCountReturnsCorrectValue() {
        Path workspacePath = Path.of("/tmp/workspace");

        when(gitWorkspaceManager.createMissionWorkspace(anyString(), anyString()))
                .thenReturn(workspacePath);
        when(gitWorkspaceManager.addWorktree(any(), eq("DIR-001"), any()))
                .thenReturn(GitWorkspaceManager.WorktreeResult.success(Path.of("/tmp/wt-1")));
        when(gitWorkspaceManager.addWorktree(any(), eq("DIR-002"), any()))
                .thenReturn(GitWorkspaceManager.WorktreeResult.success(Path.of("/tmp/wt-2")));

        context.createMissionWorkspace("MISSION-001", "https://github.com/test/repo.git");
        assertEquals(0, context.getActiveWorktreeCount());

        context.acquireWorktree("MISSION-001", "DIR-001", "main");
        assertEquals(1, context.getActiveWorktreeCount());

        context.acquireWorktree("MISSION-001", "DIR-002", "main");
        assertEquals(2, context.getActiveWorktreeCount());
    }

    @Test
    void getActiveMissionCountReturnsCorrectValue() {
        when(gitWorkspaceManager.createMissionWorkspace(eq("MISSION-001"), anyString()))
                .thenReturn(Path.of("/tmp/ws1"));
        when(gitWorkspaceManager.createMissionWorkspace(eq("MISSION-002"), anyString()))
                .thenReturn(Path.of("/tmp/ws2"));

        assertEquals(0, context.getActiveMissionCount());

        context.createMissionWorkspace("MISSION-001", "https://github.com/test/repo.git");
        assertEquals(1, context.getActiveMissionCount());

        context.createMissionWorkspace("MISSION-002", "https://github.com/test/repo2.git");
        assertEquals(2, context.getActiveMissionCount());
    }
}
