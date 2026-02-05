package com.worldmind.core.graph;

import com.worldmind.core.model.InteractionMode;
import com.worldmind.core.model.MissionStatus;
import com.worldmind.core.nodes.ClassifyRequestNode;
import com.worldmind.core.nodes.PlanMissionNode;
import com.worldmind.core.nodes.UploadContextNode;
import com.worldmind.core.state.WorldmindState;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.StateGraph;
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
 */
@Component
public class WorldmindGraph {

    private final CompiledGraph<WorldmindState> compiledGraph;

    public WorldmindGraph(
            ClassifyRequestNode classifyNode,
            UploadContextNode uploadNode,
            PlanMissionNode planNode) throws Exception {

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

        this.compiledGraph = graph.compile();
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
