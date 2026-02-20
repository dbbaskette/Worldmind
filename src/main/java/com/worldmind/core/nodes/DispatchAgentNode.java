package com.worldmind.core.nodes;

import com.worldmind.core.model.Task;
import com.worldmind.core.model.TaskStatus;
import com.worldmind.core.model.MissionStatus;
import com.worldmind.core.state.WorldmindState;
import com.worldmind.sandbox.AgentDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * LangGraph4j node that dispatches the next pending task to a Sandbox
 * container via the {@link AgentDispatcher}.
 *
 * <p>Reads {@code tasks} and {@code currentTaskIndex} from the graph state,
 * finds the next {@link TaskStatus#PENDING} task, executes it through the
 * bridge, and returns state updates including the sandbox info, advanced index,
 * mission status, and any errors.
 */
@Component
public class DispatchAgentNode {

    private static final Logger log = LoggerFactory.getLogger(DispatchAgentNode.class);

    private final AgentDispatcher bridge;

    public DispatchAgentNode(AgentDispatcher bridge) {
        this.bridge = bridge;
    }

    public Map<String, Object> apply(WorldmindState state) {
        var tasks = state.tasks();
        int currentIndex = state.currentTaskIndex();
        String retryContext = state.<String>value("retryContext").orElse(null);

        if (tasks.isEmpty() || currentIndex >= tasks.size()) {
            log.info("No pending tasks — mission complete");
            return Map.of("status", MissionStatus.COMPLETED.name());
        }

        var task = tasks.get(currentIndex);
        if (task.status() != TaskStatus.PENDING) {
            if (retryContext != null && !retryContext.isEmpty() && task.status() == TaskStatus.FAILED) {
                log.info("Retrying FAILED task {} with retry context", task.id());
                // Fall through to dispatch with augmented context
            } else {
                log.info("Task {} already {}, advancing", task.id(), task.status());
                return Map.of("currentTaskIndex", currentIndex + 1);
            }
        }

        // If retryContext is present, augment the task's inputContext
        var taskToDispatch = task;
        if (retryContext != null && !retryContext.isEmpty()) {
            String augmentedContext = (task.inputContext() != null ? task.inputContext() + "\n\n" : "") +
                "## Retry Context (from previous attempt)\n\n" + retryContext;
            taskToDispatch = new Task(
                task.id(), task.agent(), task.description(),
                augmentedContext, task.successCriteria(), task.dependencies(),
                TaskStatus.PENDING, task.iteration(), task.maxIterations(),
                task.onFailure(), task.targetFiles(), task.filesAffected(), task.elapsedMs()
            );
        }

        log.info("Dispatching task {} [{}]: {}",
                taskToDispatch.id(), taskToDispatch.agent(), taskToDispatch.description());

        var projectContext = state.projectContext().orElse(null);
        String projectPath = projectContext != null ? projectContext.rootPath() : ".";

        try {
            var result = bridge.executeTask(
                taskToDispatch, projectContext, Path.of(projectPath), state.gitRemoteUrl(), state.runtimeTag(), state.reasoningLevel()
            );

            var updates = new HashMap<String, Object>();
            updates.put("sandboxes", List.of(result.sandboxInfo()));
            updates.put("currentTaskIndex", currentIndex + 1);
            updates.put("status", MissionStatus.EXECUTING.name());

            if (result.task().status() == TaskStatus.FAILED) {
                updates.put("errors", List.of(
                    "Task " + task.id() + " failed: " + result.output()));
            }

            // Clear retryContext after consuming
            if (retryContext != null && !retryContext.isEmpty()) {
                updates.put("retryContext", "");
            }

            return updates;
        } catch (Exception e) {
            log.error("Infrastructure error dispatching task {}: {}",
                    task.id(), e.getMessage());
            var updates = new HashMap<String, Object>();
            updates.put("currentTaskIndex", currentIndex + 1);
            updates.put("status", MissionStatus.FAILED.name());
            updates.put("errors", List.of(
                "Task " + task.id() + " infrastructure error: " + e.getMessage()));
            // Clear retryContext even on infrastructure error
            if (retryContext != null && !retryContext.isEmpty()) {
                updates.put("retryContext", "");
            }
            return updates;
        }
    }
}
