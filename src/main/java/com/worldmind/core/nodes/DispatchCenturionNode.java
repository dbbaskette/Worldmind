package com.worldmind.core.nodes;

import com.worldmind.core.model.Directive;
import com.worldmind.core.model.DirectiveStatus;
import com.worldmind.core.model.MissionStatus;
import com.worldmind.core.state.WorldmindState;
import com.worldmind.starblaster.StarblasterBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * LangGraph4j node that dispatches the next pending directive to a Starblaster
 * container via the {@link StarblasterBridge}.
 *
 * <p>Reads {@code directives} and {@code currentDirectiveIndex} from the graph state,
 * finds the next {@link DirectiveStatus#PENDING} directive, executes it through the
 * bridge, and returns state updates including the starblaster info, advanced index,
 * mission status, and any errors.
 */
@Component
public class DispatchCenturionNode {

    private static final Logger log = LoggerFactory.getLogger(DispatchCenturionNode.class);

    private final StarblasterBridge bridge;

    public DispatchCenturionNode(StarblasterBridge bridge) {
        this.bridge = bridge;
    }

    public Map<String, Object> apply(WorldmindState state) {
        var directives = state.directives();
        int currentIndex = state.currentDirectiveIndex();
        String retryContext = state.<String>value("retryContext").orElse(null);

        if (directives.isEmpty() || currentIndex >= directives.size()) {
            log.info("No pending directives — mission complete");
            return Map.of("status", MissionStatus.COMPLETED.name());
        }

        var directive = directives.get(currentIndex);
        if (directive.status() != DirectiveStatus.PENDING) {
            if (retryContext != null && !retryContext.isEmpty() && directive.status() == DirectiveStatus.FAILED) {
                log.info("Retrying FAILED directive {} with retry context", directive.id());
                // Fall through to dispatch with augmented context
            } else {
                log.info("Directive {} already {}, advancing", directive.id(), directive.status());
                return Map.of("currentDirectiveIndex", currentIndex + 1);
            }
        }

        // If retryContext is present, augment the directive's inputContext
        var directiveToDispatch = directive;
        if (retryContext != null && !retryContext.isEmpty()) {
            String augmentedContext = (directive.inputContext() != null ? directive.inputContext() + "\n\n" : "") +
                "## Retry Context (from previous attempt)\n\n" + retryContext;
            directiveToDispatch = new Directive(
                directive.id(), directive.centurion(), directive.description(),
                augmentedContext, directive.successCriteria(), directive.dependencies(),
                DirectiveStatus.PENDING, directive.iteration(), directive.maxIterations(),
                directive.onFailure(), directive.filesAffected(), directive.elapsedMs()
            );
        }

        log.info("Dispatching directive {} [{}]: {}",
                directiveToDispatch.id(), directiveToDispatch.centurion(), directiveToDispatch.description());

        var projectContext = state.projectContext().orElse(null);
        String projectPath = projectContext != null ? projectContext.rootPath() : ".";

        try {
            var result = bridge.executeDirective(
                directiveToDispatch, projectContext, Path.of(projectPath), state.gitRemoteUrl(), state.runtimeTag(), state.reasoningLevel()
            );

            var updates = new HashMap<String, Object>();
            updates.put("starblasters", List.of(result.starblasterInfo()));
            updates.put("currentDirectiveIndex", currentIndex + 1);
            updates.put("status", MissionStatus.EXECUTING.name());

            if (result.directive().status() == DirectiveStatus.FAILED) {
                updates.put("errors", List.of(
                    "Directive " + directive.id() + " failed: " + result.output()));
            }

            // Clear retryContext after consuming
            if (retryContext != null && !retryContext.isEmpty()) {
                updates.put("retryContext", "");
            }

            return updates;
        } catch (Exception e) {
            log.error("Infrastructure error dispatching directive {}: {}",
                    directive.id(), e.getMessage());
            var updates = new HashMap<String, Object>();
            updates.put("currentDirectiveIndex", currentIndex + 1);
            updates.put("status", MissionStatus.FAILED.name());
            updates.put("errors", List.of(
                "Directive " + directive.id() + " infrastructure error: " + e.getMessage()));
            // Clear retryContext even on infrastructure error
            if (retryContext != null && !retryContext.isEmpty()) {
                updates.put("retryContext", "");
            }
            return updates;
        }
    }
}
