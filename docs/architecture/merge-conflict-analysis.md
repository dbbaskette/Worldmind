# Merge Conflict Analysis and Enhancement Proposals

## Problem Statement

When tasks execute against existing codebases or retry after failures, merge conflicts can occur due to stale branches. This document analyzes the root causes and implemented solutions.

## Scenarios That Cause Stale Branches

1. **Retry after failure**: Task branch was created from old `main`. When retried after other tasks merged, it conflicts.

2. **Leftover branches from previous missions**: Branches weren't cleaned up, contain outdated code.

3. **Modifying existing code**: Running against a repo with old worldmind branches from previous runs.

4. **CF task restart**: Task restarted mid-execution but `main` has moved forward from other merges.

## Implemented Solution: Always Fresh Branch Strategy

**Status: IMPLEMENTED** ✓

As of the latest changes, **ALL CODER/REFACTORER tasks always start fresh from current `main`**, regardless of whether it's a first attempt or retry. This eliminates the root cause of stale branches.

### How It Works

In `CloudFoundrySandboxProvider.java`:

```java
// ALL CODER/REFACTORER tasks delete any existing branch and create fresh from main
branchSetup = "git push origin --delete " + branchName + " 2>/dev/null || true; "
            + "git branch -D " + branchName + " 2>/dev/null || true; "
            + "git checkout -b " + branchName;
```

This ensures:
- Agent always sees the latest code (including other merged tasks)
- No stale changes from previous attempts interfere  
- Clean git history without orphaned commits
- Works for first attempts AND retries
- Handles leftover branches from previous missions

### Why This Is Safe

The instruction text contains all context the agent needs. For retries, `EvaluateWaveNode` enriches the context with:
- Review feedback from the previous attempt
- List of files merged by other tasks
- Explicit instructions to not recreate existing files

Losing the old branch content is fine because:
1. The agent can read existing files from `main`
2. Previous work that was good is already in `main` (if merged)
3. Previous work that was bad should be redone from scratch anyway

## Previous Root Causes (Now Resolved)

### 1. Branch Not Rebased Before Retry ✓ FIXED

Previously:
```java
branchSetup = "(git fetch origin " + branchName + " 2>/dev/null && git checkout " + branchName 
            + ") || git checkout -b " + branchName;
```

This preserved the old branch. Now we always delete and recreate.

### 2. No "Fresh Start" for Retries ✓ FIXED

Previously, retry context told the agent to "start fresh" but the branch setup didn't actually start fresh. Now it does.

### 3. Incomplete File Overlap Detection ✓ FIXED

`EvaluateWaveNode` now updates `targetFiles` using actual `filesAffected` from previous attempts, giving the scheduler accurate overlap detection.

### 4. No Awareness of Merged Changes ✓ FIXED

`EvaluateWaveNode` now includes detailed context about which files were merged by other tasks, helping the agent avoid recreating existing files.

---

## Implemented Enhancements

### Enhancement 1: Always Fresh Branch ✓ IMPLEMENTED

**Problem**: Tasks (especially retries) reused old branches based on stale main.

**Solution**: ALL CODER/REFACTORER tasks delete any existing branch and create fresh from current main.

```java
// In CloudFoundrySandboxProvider.java - applies to ALL implementation tasks
branchSetup = "git push origin --delete " + branchName + " 2>/dev/null || true; "
            + "git branch -D " + branchName + " 2>/dev/null || true; "
            + "git checkout -b " + branchName;
```

This handles ALL scenarios: first attempts, retries, leftover branches, and CF restarts.

### Enhancement 2: Pass Iteration Count to Sandbox ✓ IMPLEMENTED

**Problem**: Sandbox didn't know if this was a retry.

**Solution**: `AgentRequest` now includes iteration count:

```java
public record AgentRequest(
    // ... other fields ...
    int iteration  // 0 = first attempt, 1+ = retry
) implements Serializable {}
```

The iteration flows: `Task.iteration()` → `AgentDispatcher` → `SandboxManager` → `AgentRequest` → `CloudFoundrySandboxProvider`

While we now always start fresh regardless of iteration, having the iteration count available enables better logging and potential future optimizations.

### Enhancement 3: Include Previously-Merged Files in Retry Context ✓ IMPLEMENTED

**Problem**: Retried tasks didn't know what files were created by other merged tasks.

**Solution**: `EvaluateWaveNode` now enriches retry context with merged file details:

```java
// In EvaluateWaveNode.java when handling merge conflicts
var mergedFilesContext = new StringBuilder();
if (!mergeResult.mergedIds().isEmpty()) {
    mergedFilesContext.append("\n\nFILES ALREADY MERGED INTO MAIN by other tasks:");
    for (String mergedId : mergeResult.mergedIds()) {
        // ... list files from each merged task ...
    }
    mergedFilesContext.append("\nDO NOT recreate these files...");
}
```

### Enhancement 4: Use Actual `filesAffected` for Overlap Detection ✓ IMPLEMENTED

**Problem**: Scheduler only used predicted `targetFiles`, not actual files changed.

**Solution**: When resetting a task for retry, `EvaluateWaveNode` updates `targetFiles` with actual `filesAffected`:

```java
List<String> actualTargets = (d.filesAffected() != null && !d.filesAffected().isEmpty())
    ? d.filesAffected().stream().map(f -> f.path()).toList()
    : d.targetFiles();
```

---

## Future Enhancements (Not Yet Implemented)

### Enhancement 5: Block Conflicting Retries Until Dependencies Re-merge

Add "soft dependencies" based on file overlap to prevent tasks from running if their target files were already modified by merged tasks.

### Enhancement 6: Rebase Strategy Option

Offer a rebase strategy that attempts to preserve previous work before falling back to fresh. This is complex and may introduce hard-to-debug merge issues, so the "always fresh" approach is preferred for now.

### Enhancement 7: Conflict Detection Before Dispatch

Pre-check if a branch would conflict with main before dispatching the task. This could save compute but adds latency and complexity.

---

## Testing Scenarios

1. **Basic retry**: TASK-001 fails review, retries, should succeed with fresh branch
2. **Parallel with merged conflict**: TASK-001 and TASK-002 both create files, one fails, other merges, retry should work cleanly
3. **Leftover branches**: Running against repo with old worldmind branches should work correctly
4. **Modifying existing code**: Tasks against existing codebase should start fresh from current main
5. **CF restart**: Task that restarts after CF disruption should pick up from current main
