package com.worldmind.core.nodes;

import com.worldmind.core.model.Directive;
import com.worldmind.core.model.DirectiveStatus;
import com.worldmind.core.model.ExecutionStrategy;
import com.worldmind.core.model.FailureStrategy;
import com.worldmind.core.model.MissionStatus;
import com.worldmind.core.state.WorldmindState;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Generates a mission plan consisting of one or more {@link Directive}s.
 * <p>
 * Stub implementation -- returns a single placeholder directive.
 */
@Component
public class PlanMissionNode {

    public Map<String, Object> apply(WorldmindState state) {
        var directive = new Directive(
                "DIR-001", "FORGE", "Placeholder directive",
                "", "Stub success criteria",
                List.of(), DirectiveStatus.PENDING, 0, 3,
                FailureStrategy.RETRY, List.of(), null
        );
        return Map.of(
                "directives", List.of(directive),
                "executionStrategy", ExecutionStrategy.SEQUENTIAL.name(),
                "status", MissionStatus.AWAITING_APPROVAL.name()
        );
    }
}
