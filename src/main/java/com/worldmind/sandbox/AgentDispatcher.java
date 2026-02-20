package com.worldmind.sandbox;

import com.worldmind.core.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

/**
 * Thin translation layer between the LangGraph4j planning domain and Sandbox execution.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Builds instruction markdown via {@link InstructionBuilder}</li>
 *   <li>Delegates execution to {@link SandboxManager}</li>
 *   <li>Translates {@link SandboxManager.ExecutionResult} into an updated {@link Task}
 *       and {@link SandboxInfo}</li>
 * </ul>
 *
 * <p>Intentionally contains no business logic — just translation.
 */
@Service
public class AgentDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AgentDispatcher.class);

    private final SandboxManager manager;

    public AgentDispatcher(SandboxManager manager) {
        this.manager = manager;
    }

    /** Max output size kept in memory per task. Tail is preserved for error context. */
    private static final int MAX_OUTPUT_BYTES = 10_000;

    /**
     * Composite result returned after executing a task through a Sandbox.
     *
     * @param task    the task with updated status, iteration count, file changes, and timing
     * @param sandboxInfo metadata about the Sandbox container that executed the task
     * @param output       captured stdout/stderr from the container (truncated to ~10KB)
     */
    public record BridgeResult(
        Task task,
        SandboxInfo sandboxInfo,
        String output
    ) {}

    /**
     * Executes a task inside a Sandbox container and returns the translated result.
     *
     * <p>Flow:
     * <ol>
     *   <li>Build instruction markdown from task + project context</li>
     *   <li>Delegate to {@link SandboxManager#executeTask}</li>
     *   <li>Map exit code to {@link TaskStatus#PASSED} or {@link TaskStatus#FAILED}</li>
     *   <li>Construct updated task and sandbox info</li>
     * </ol>
     *
     * @param task   the task to execute
     * @param context     project context (may be null)
     * @param projectPath host path to the project directory
     * @return bridge result containing updated task, sandbox info, and output
     */
    public BridgeResult executeTask(Task task, ProjectContext context, Path projectPath, String gitRemoteUrl, String runtimeTag) {
        return executeTask(task, context, projectPath, gitRemoteUrl, runtimeTag, "medium");
    }

    public BridgeResult executeTask(Task task, ProjectContext context, Path projectPath, String gitRemoteUrl, String runtimeTag, String reasoningLevel) {
        log.info("Executing task {} [{}]: {} (reasoning={})",
                task.id(), task.agent(), task.description(), reasoningLevel);

        String instruction = InstructionBuilder.build(task, context, reasoningLevel);
        instruction = InstructionBuilder.withRuntimePreamble(instruction, runtimeTag);
        Instant startedAt = Instant.now();

        var execResult = manager.executeTask(
            task.agent(),
            task.id(),
            projectPath,
            instruction,
            Map.of(),
            gitRemoteUrl,
            runtimeTag,
            task.iteration()
        );

        Instant completedAt = Instant.now();
        // Goose may exit with code 1 even after successfully creating files
        // (e.g., rate limit hit during session cleanup). Treat as success if files were changed.
        // However, CODER and REFACTORER tasks MUST produce file changes to be considered successful.
        // If the model exits cleanly but did no work (the "lazy model" case), mark as failed.
        boolean hasFileChanges = !execResult.fileChanges().isEmpty();
        boolean requiresFileChanges = "CODER".equalsIgnoreCase(task.agent())
                || "REFACTORER".equalsIgnoreCase(task.agent());
        
        boolean success;
        if (requiresFileChanges && !hasFileChanges) {
            log.warn("Task {} ({}) exited with code {} but produced no file changes — marking as FAILED",
                    task.id(), task.agent(), execResult.exitCode());
            success = false;
        } else {
            success = execResult.exitCode() == 0 || hasFileChanges;
        }

        // CODER/REFACTORER need quality gates (TESTER/REVIEWER) — use VERIFYING until quality_gate evaluation.
        // Other agents (TESTER, REVIEWER, etc.) can be marked PASSED immediately.
        TaskStatus successStatus = requiresFileChanges 
                ? TaskStatus.VERIFYING 
                : TaskStatus.PASSED;

        // Note: iteration is NOT incremented here — EvaluateWaveNode handles iteration
        // tracking to avoid double-counting across retries and quality gate cycles.
        var updatedTask = new Task(
            task.id(),
            task.agent(),
            task.description(),
            task.inputContext(),
            task.successCriteria(),
            task.dependencies(),
            success ? successStatus : TaskStatus.FAILED,
            task.iteration(),
            task.maxIterations(),
            task.onFailure(),
            task.targetFiles(),
            execResult.fileChanges(),
            execResult.elapsedMs()
        );

        var sandboxInfo = new SandboxInfo(
            execResult.sandboxId(),
            task.agent(),
            task.id(),
            success ? "completed" : "failed",
            startedAt,
            completedAt
        );

        log.info("Task {} {} in {}ms",
                task.id(), success ? successStatus.name() : "FAILED", execResult.elapsedMs());

        return new BridgeResult(updatedTask, sandboxInfo, truncateOutput(execResult.output()));
    }

    /**
     * Truncates output to ~10KB keeping the head and tail for context.
     */
    static String truncateOutput(String output) {
        if (output == null || output.length() <= MAX_OUTPUT_BYTES) return output;
        int headSize = MAX_OUTPUT_BYTES / 2;
        int tailSize = MAX_OUTPUT_BYTES / 2;
        return output.substring(0, headSize)
                + "\n\n... [truncated " + (output.length() - MAX_OUTPUT_BYTES) + " chars] ...\n\n"
                + output.substring(output.length() - tailSize);
    }
}
