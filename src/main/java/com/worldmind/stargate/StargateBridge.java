package com.worldmind.stargate;

import com.worldmind.core.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

/**
 * Thin translation layer between the LangGraph4j planning domain and Stargate execution.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Builds instruction markdown via {@link InstructionBuilder}</li>
 *   <li>Delegates execution to {@link StargateManager}</li>
 *   <li>Translates {@link StargateManager.ExecutionResult} into an updated {@link Directive}
 *       and {@link StargateInfo}</li>
 * </ul>
 *
 * <p>Intentionally contains no business logic — just translation.
 */
@Service
public class StargateBridge {

    private static final Logger log = LoggerFactory.getLogger(StargateBridge.class);

    private final StargateManager manager;

    public StargateBridge(StargateManager manager) {
        this.manager = manager;
    }

    /**
     * Composite result returned after executing a directive through a Stargate.
     *
     * @param directive    the directive with updated status, iteration count, file changes, and timing
     * @param stargateInfo metadata about the Stargate container that executed the directive
     * @param output       captured stdout/stderr from the container
     */
    public record BridgeResult(
        Directive directive,
        StargateInfo stargateInfo,
        String output
    ) {}

    /**
     * Executes a directive inside a Stargate container and returns the translated result.
     *
     * <p>Flow:
     * <ol>
     *   <li>Build instruction markdown from directive + project context</li>
     *   <li>Delegate to {@link StargateManager#executeDirective}</li>
     *   <li>Map exit code to {@link DirectiveStatus#PASSED} or {@link DirectiveStatus#FAILED}</li>
     *   <li>Construct updated directive and stargate info</li>
     * </ol>
     *
     * @param directive   the directive to execute
     * @param context     project context (may be null)
     * @param projectPath host path to the project directory
     * @return bridge result containing updated directive, stargate info, and output
     */
    public BridgeResult executeDirective(Directive directive, ProjectContext context, Path projectPath) {
        log.info("Executing directive {} [{}]: {}",
                directive.id(), directive.centurion(), directive.description());

        String instruction = InstructionBuilder.build(directive, context);
        Instant startedAt = Instant.now();

        var execResult = manager.executeDirective(
            directive.centurion(),
            directive.id(),
            projectPath,
            instruction,
            Map.of()
        );

        Instant completedAt = Instant.now();
        // Goose may exit with code 1 even after successfully creating files
        // (e.g., rate limit hit during session cleanup). Treat as success if files were changed.
        boolean success = execResult.exitCode() == 0
                || !execResult.fileChanges().isEmpty();

        var updatedDirective = new Directive(
            directive.id(),
            directive.centurion(),
            directive.description(),
            directive.inputContext(),
            directive.successCriteria(),
            directive.dependencies(),
            success ? DirectiveStatus.PASSED : DirectiveStatus.FAILED,
            directive.iteration() + 1,
            directive.maxIterations(),
            directive.onFailure(),
            execResult.fileChanges(),
            execResult.elapsedMs()
        );

        var stargateInfo = new StargateInfo(
            execResult.stargateId(),
            directive.centurion(),
            directive.id(),
            success ? "completed" : "failed",
            startedAt,
            completedAt
        );

        log.info("Directive {} {} in {}ms",
                directive.id(), success ? "PASSED" : "FAILED", execResult.elapsedMs());

        return new BridgeResult(updatedDirective, stargateInfo, execResult.output());
    }
}
