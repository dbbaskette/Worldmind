package com.worldmind.core.nodes;

import com.worldmind.core.llm.LlmService;
import com.worldmind.core.model.MissionStatus;
import com.worldmind.core.novaforce.NovaForceToolProvider;
import com.worldmind.core.state.WorldmindState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * LangGraph4j node that runs after mission convergence to perform post-mission actions.
 *
 * <p>When Nexus is enabled, this node has access to:
 * <ul>
 *   <li><b>Xandar</b> (GitHub, read/write) — creates pull requests with summaries of changes</li>
 *   <li><b>Archive</b> (read/write) — records mission outcome, patterns learned, and PR link</li>
 * </ul>
 *
 * <p>When Nexus is disabled, this node is a no-op passthrough.
 */
@Component
public class PostMissionNode {

    private static final Logger log = LoggerFactory.getLogger(PostMissionNode.class);

    private static final String SYSTEM_PROMPT = """
            You are a post-mission processor for Worldmind, an agentic code assistant.
            A mission has just completed. Your job is to:
            1. Summarize what was accomplished across all directives
            2. If GitHub tools are available, create a pull request with a clear title \
            and description summarizing all changes
            3. If Archive tools are available, record the mission outcome and any \
            patterns learned for future reference

            Be concise and factual in your summaries.
            """;

    private static final String TOOL_GUIDANCE = """

            You have access to GitHub (Xandar) and the mission Archive. \
            Create a pull request with a clear title and description summarizing all changes. \
            Record the mission outcome and PR link in the Archive.""";

    private final LlmService llmService;
    private final NovaForceToolProvider novaForceToolProvider;

    public PostMissionNode(LlmService llmService,
                           @Autowired(required = false) NovaForceToolProvider novaForceToolProvider) {
        this.llmService = llmService;
        this.novaForceToolProvider = novaForceToolProvider;
    }

    public Map<String, Object> apply(WorldmindState state) {
        var tools = novaForceToolProvider != null ? novaForceToolProvider.getToolsForNode("postmission") : null;
        boolean hasTools = tools != null && tools.length > 0;

        if (!hasTools) {
            log.info("PostMission node — no Nexus tools available, skipping post-mission actions");
            return Map.of();
        }

        log.info("PostMission node — {} tool(s) available, executing post-mission actions", tools.length);

        String status = state.status() != null ? state.status().name() : "UNKNOWN";
        var directives = state.directives();

        var summaryBuilder = new StringBuilder();
        summaryBuilder.append("Mission ").append(state.missionId()).append(" — Status: ").append(status).append("\n");
        summaryBuilder.append("Request: ").append(state.request()).append("\n\n");
        summaryBuilder.append("Directives:\n");
        for (var d : directives) {
            summaryBuilder.append("- ").append(d.id()).append(" [").append(d.centurion()).append("] ")
                    .append(d.status()).append(": ").append(d.description()).append("\n");
        }

        if (state.metrics().isPresent()) {
            var m = state.metrics().get();
            summaryBuilder.append("\nMetrics: ")
                    .append(m.directivesCompleted()).append(" completed, ")
                    .append(m.directivesFailed()).append(" failed, ")
                    .append(m.testsRun()).append(" tests (").append(m.testsPassed()).append(" passed)\n");
        }

        String userPrompt = summaryBuilder.toString();
        String systemPrompt = SYSTEM_PROMPT + TOOL_GUIDANCE;

        try {
            // Use a free-form call with tools — PostMission doesn't need structured output
            llmService.structuredCallWithTools(systemPrompt, userPrompt, String.class, tools);
            log.info("PostMission actions completed for mission {}", state.missionId());
        } catch (Exception e) {
            log.warn("PostMission actions failed for mission {}: {}", state.missionId(), e.getMessage());
        }

        return Map.of();
    }
}
