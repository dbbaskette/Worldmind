package com.worldmind.starblaster;

import com.worldmind.core.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

/**
 * Thin translation layer between the LangGraph4j planning domain and Starblaster execution.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Builds instruction markdown via {@link InstructionBuilder}</li>
 *   <li>Delegates execution to {@link StarblasterManager}</li>
 *   <li>Translates {@link StarblasterManager.ExecutionResult} into an updated {@link Directive}
 *       and {@link StarblasterInfo}</li>
 * </ul>
 *
 * <p>Intentionally contains no business logic — just translation.
 */
@Service
public class StarblasterBridge {

    private static final Logger log = LoggerFactory.getLogger(StarblasterBridge.class);

    private final StarblasterManager manager;

    public StarblasterBridge(StarblasterManager manager) {
        this.manager = manager;
    }

    /** Max output size kept in memory per directive. Tail is preserved for error context. */
    private static final int MAX_OUTPUT_BYTES = 10_000;

    /**
     * Composite result returned after executing a directive through a Starblaster.
     *
     * @param directive    the directive with updated status, iteration count, file changes, and timing
     * @param starblasterInfo metadata about the Starblaster container that executed the directive
     * @param output       captured stdout/stderr from the container (truncated to ~10KB)
     */
    public record BridgeResult(
        Directive directive,
        StarblasterInfo starblasterInfo,
        String output
    ) {}

    /**
     * Executes a directive inside a Starblaster container and returns the translated result.
     *
     * <p>Flow:
     * <ol>
     *   <li>Build instruction markdown from directive + project context</li>
     *   <li>Delegate to {@link StarblasterManager#executeDirective}</li>
     *   <li>Map exit code to {@link DirectiveStatus#PASSED} or {@link DirectiveStatus#FAILED}</li>
     *   <li>Construct updated directive and starblaster info</li>
     * </ol>
     *
     * @param directive   the directive to execute
     * @param context     project context (may be null)
     * @param projectPath host path to the project directory
     * @return bridge result containing updated directive, starblaster info, and output
     */
    public BridgeResult executeDirective(Directive directive, ProjectContext context, Path projectPath, String gitRemoteUrl, String runtimeTag) {
        return executeDirective(directive, context, projectPath, gitRemoteUrl, runtimeTag, "medium");
    }

    public BridgeResult executeDirective(Directive directive, ProjectContext context, Path projectPath, String gitRemoteUrl, String runtimeTag, String reasoningLevel) {
        log.info("Executing directive {} [{}]: {} (reasoning={})",
                directive.id(), directive.centurion(), directive.description(), reasoningLevel);

        String instruction = InstructionBuilder.build(directive, context, reasoningLevel);
        instruction = InstructionBuilder.withRuntimePreamble(instruction, runtimeTag);
        Instant startedAt = Instant.now();

        var execResult = manager.executeDirective(
            directive.centurion(),
            directive.id(),
            projectPath,
            instruction,
            Map.of(),
            gitRemoteUrl,
            runtimeTag
        );

        Instant completedAt = Instant.now();
        // Goose may exit with code 1 even after successfully creating files
        // (e.g., rate limit hit during session cleanup). Treat as success if files were changed.
        // However, FORGE and PRISM directives MUST produce file changes to be considered successful.
        // If the model exits cleanly but did no work (the "lazy model" case), mark as failed.
        boolean hasFileChanges = !execResult.fileChanges().isEmpty();
        boolean requiresFileChanges = "FORGE".equalsIgnoreCase(directive.centurion())
                || "PRISM".equalsIgnoreCase(directive.centurion());
        
        boolean success;
        if (requiresFileChanges && !hasFileChanges) {
            log.warn("Directive {} ({}) exited with code {} but produced no file changes — marking as FAILED",
                    directive.id(), directive.centurion(), execResult.exitCode());
            success = false;
        } else {
            success = execResult.exitCode() == 0 || hasFileChanges;
        }

        // FORGE/PRISM need quality gates (GAUNTLET/VIGIL) — use VERIFYING until seal evaluation.
        // Other centurions (GAUNTLET, VIGIL, etc.) can be marked PASSED immediately.
        DirectiveStatus successStatus = requiresFileChanges 
                ? DirectiveStatus.VERIFYING 
                : DirectiveStatus.PASSED;

        var updatedDirective = new Directive(
            directive.id(),
            directive.centurion(),
            directive.description(),
            directive.inputContext(),
            directive.successCriteria(),
            directive.dependencies(),
            success ? successStatus : DirectiveStatus.FAILED,
            directive.iteration() + 1,
            directive.maxIterations(),
            directive.onFailure(),
            directive.targetFiles(),
            execResult.fileChanges(),
            execResult.elapsedMs()
        );

        var starblasterInfo = new StarblasterInfo(
            execResult.starblasterId(),
            directive.centurion(),
            directive.id(),
            success ? "completed" : "failed",
            startedAt,
            completedAt
        );

        log.info("Directive {} {} in {}ms",
                directive.id(), success ? successStatus.name() : "FAILED", execResult.elapsedMs());

        return new BridgeResult(updatedDirective, starblasterInfo, truncateOutput(execResult.output()));
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
