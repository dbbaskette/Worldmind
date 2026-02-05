package com.worldmind.core.graph;

import com.worldmind.core.model.InteractionMode;
import com.worldmind.core.model.MissionStatus;
import com.worldmind.core.nodes.ClassifyRequestNode;
import com.worldmind.core.nodes.PlanMissionNode;
import com.worldmind.core.nodes.UploadContextNode;
import com.worldmind.core.state.WorldmindState;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * Builds and holds the compiled LangGraph4j {@link StateGraph} that drives
 * the Worldmind planning pipeline.
 * <p>
 * The graph flows through:
 * <pre>
 *   START -> classify_request -> upload_context -> plan_mission
 *         -> [conditional] -> await_approval -> END
 *                          -> END  (FULL_AUTO skips approval)
 * </pre>
 * <p>
 * When a {@link BaseCheckpointSaver} is available, the graph is compiled
 * with checkpointing enabled so state is persisted across executions.
 */
@Component
public class WorldmindGraph {

    private static final Logger log = LoggerFactory.getLogger(WorldmindGraph.class);

    private final CompiledGraph<WorldmindState> compiledGraph;

    public WorldmindGraph(
            ClassifyRequestNode classifyNode,
            UploadContextNode uploadNode,
            PlanMissionNode planNode,
            @Autowired(required = false) BaseCheckpointSaver checkpointSaver) throws Exception {

        var graph = new StateGraph<>(WorldmindState.SCHEMA, WorldmindState::new)
                .addNode("classify_request", node_async(classifyNode::apply))
                .addNode("upload_context", node_async(uploadNode::apply))
                .addNode("plan_mission", node_async(planNode::apply))
                .addNode("await_approval", node_async(
                        state -> Map.of("status", MissionStatus.AWAITING_APPROVAL.name())))
                .addEdge(START, "classify_request")
                .addEdge("classify_request", "upload_context")
                .addEdge("upload_context", "plan_mission")
                .addConditionalEdges("plan_mission",
                        edge_async(this::routeAfterPlan),
                        Map.of("await_approval", "await_approval",
                                "end", END))
                .addEdge("await_approval", END);

        // Compile with checkpointer if available
        var configBuilder = CompileConfig.builder();
        if (checkpointSaver != null) {
            configBuilder.checkpointSaver(checkpointSaver);
            log.info("Graph compiled with checkpoint saver: {}", checkpointSaver.getClass().getSimpleName());
        } else {
            log.info("Graph compiled without checkpoint saver (state will not be persisted)");
        }
        this.compiledGraph = graph.compile(configBuilder.build());
    }

    /**
     * Routes after the plan_mission node based on interaction mode.
     * In FULL_AUTO mode the graph skips the approval step and ends directly.
     */
    String routeAfterPlan(WorldmindState state) {
        if (state.interactionMode() == InteractionMode.FULL_AUTO) {
            return "end";
        }
        return "await_approval";
    }

    public CompiledGraph<WorldmindState> getCompiledGraph() {
        return compiledGraph;
    }
}
