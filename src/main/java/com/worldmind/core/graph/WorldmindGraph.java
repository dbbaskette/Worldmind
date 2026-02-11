package com.worldmind.core.graph;

import com.worldmind.core.model.InteractionMode;
import com.worldmind.core.model.MissionStatus;
import com.worldmind.core.nodes.*;
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
 * the Worldmind planning and execution pipeline.
 * <p>
 * Phase 4 graph topology (wave-based parallel execution):
 * <pre>
 *   START -> classify_request -> upload_context -> generate_spec -> plan_mission
 *         -> [routeAfterPlan]
 *            -> await_approval -> END
 *            -> schedule_wave -> [routeAfterSchedule]
 *               -> parallel_dispatch -> evaluate_wave -> [routeAfterWaveEval]
 *                  -> schedule_wave (loop back for next wave)
 *                  -> converge_results -> END
 *               -> converge_results -> END  (empty wave = all done)
 * </pre>
 */
@Component
public class WorldmindGraph {

    private static final Logger log = LoggerFactory.getLogger(WorldmindGraph.class);

    private final CompiledGraph<WorldmindState> compiledGraph;

    public WorldmindGraph(
            ClassifyRequestNode classifyNode,
            UploadContextNode uploadNode,
            GenerateSpecNode generateSpecNode,
            PlanMissionNode planNode,
            ScheduleWaveNode scheduleWaveNode,
            ParallelDispatchNode parallelDispatchNode,
            EvaluateWaveNode evaluateWaveNode,
            ConvergeResultsNode convergeNode,
            @Autowired(required = false) BaseCheckpointSaver checkpointSaver) throws Exception {

        var graph = new StateGraph<>(WorldmindState.SCHEMA, WorldmindState::new)
                .addNode("classify_request", node_async(classifyNode::apply))
                .addNode("upload_context", node_async(uploadNode::apply))
                .addNode("generate_spec", node_async(generateSpecNode::apply))
                .addNode("plan_mission", node_async(planNode::apply))
                .addNode("await_approval", node_async(
                        state -> Map.of("status", MissionStatus.AWAITING_APPROVAL.name())))
                .addNode("schedule_wave", node_async(scheduleWaveNode::apply))
                .addNode("parallel_dispatch", node_async(parallelDispatchNode::apply))
                .addNode("evaluate_wave", node_async(evaluateWaveNode::apply))
                .addNode("converge_results", node_async(convergeNode::apply))
                .addEdge(START, "classify_request")
                .addEdge("classify_request", "upload_context")
                .addEdge("upload_context", "generate_spec")
                .addEdge("generate_spec", "plan_mission")
                .addConditionalEdges("plan_mission",
                        edge_async(this::routeAfterPlan),
                        Map.of("await_approval", "await_approval",
                                "schedule_wave", "schedule_wave"))
                .addEdge("await_approval", END)
                .addConditionalEdges("schedule_wave",
                        edge_async(this::routeAfterSchedule),
                        Map.of("parallel_dispatch", "parallel_dispatch",
                                "converge_results", "converge_results"))
                .addEdge("parallel_dispatch", "evaluate_wave")
                .addConditionalEdges("evaluate_wave",
                        edge_async(this::routeAfterWaveEval),
                        Map.of("schedule_wave", "schedule_wave",
                                "converge_results", "converge_results"))
                .addEdge("converge_results", END);

        var configBuilder = CompileConfig.builder()
                .recursionLimit(100);
        if (checkpointSaver != null) {
            configBuilder.checkpointSaver(checkpointSaver);
            log.info("Graph compiled with checkpoint saver: {}", checkpointSaver.getClass().getSimpleName());
        } else {
            log.info("Graph compiled without checkpoint saver (state will not be persisted)");
        }
        this.compiledGraph = graph.compile(configBuilder.build());
    }

    /**
     * Routes after plan_mission based on interaction mode.
     * FULL_AUTO skips approval and starts scheduling waves directly.
     */
    String routeAfterPlan(WorldmindState state) {
        if (state.interactionMode() == InteractionMode.FULL_AUTO) {
            return "schedule_wave";
        }
        return "await_approval";
    }

    /**
     * Routes after schedule_wave.
     * Empty wave means all directives are done -> converge.
     * Non-empty wave -> dispatch.
     */
    String routeAfterSchedule(WorldmindState state) {
        if (state.waveDirectiveIds().isEmpty()) {
            return "converge_results";
        }
        return "parallel_dispatch";
    }

    /**
     * Routes after evaluate_wave.
     * If mission FAILED or all directives completed -> converge.
     * Otherwise -> schedule next wave.
     */
    String routeAfterWaveEval(WorldmindState state) {
        if (state.status() == MissionStatus.FAILED) {
            return "converge_results";
        }
        // Check if all directives are in completedIds
        var completedIds = state.completedDirectiveIds();
        var allIds = state.directives().stream().map(d -> d.id()).toList();
        if (completedIds.containsAll(allIds)) {
            return "converge_results";
        }
        return "schedule_wave";
    }

    public CompiledGraph<WorldmindState> getCompiledGraph() {
        return compiledGraph;
    }
}
