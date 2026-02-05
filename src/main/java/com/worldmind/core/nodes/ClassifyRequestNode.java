package com.worldmind.core.nodes;

import com.worldmind.core.model.Classification;
import com.worldmind.core.model.MissionStatus;
import com.worldmind.core.state.WorldmindState;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Classifies the incoming user request to determine its category, complexity,
 * affected components, and planning strategy.
 * <p>
 * Stub implementation -- returns a hardcoded classification.
 */
@Component
public class ClassifyRequestNode {

    public Map<String, Object> apply(WorldmindState state) {
        var classification = new Classification(
                "feature", 3, List.of("api"), "sequential"
        );
        return Map.of(
                "classification", classification,
                "status", MissionStatus.UPLOADING.name()
        );
    }
}
