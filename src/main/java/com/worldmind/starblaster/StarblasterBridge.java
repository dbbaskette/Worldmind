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

    /**
     * Composite result returned after executing a directive through a Starblaster.
     *
     * @param directive    the directive with updated status, iteration count, file changes, and timing
     * @param starblasterInfo metadata about the Starblaster container that executed the directive
     * @param output       captured stdout/stderr from the container
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

        var starblasterInfo = new StarblasterInfo(
            execResult.starblasterId(),
            directive.centurion(),
            directive.id(),
            success ? "completed" : "failed",
            startedAt,
            completedAt
        );

        log.info("Directive {} {} in {}ms",
                directive.id(), success ? "PASSED" : "FAILED", execResult.elapsedMs());

        return new BridgeResult(updatedDirective, starblasterInfo, execResult.output());
    }
}
