package com.worldmind.core.nodes;

import com.worldmind.core.model.MissionStatus;
import com.worldmind.core.model.ProjectContext;
import com.worldmind.core.state.WorldmindState;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Scans the target project directory and produces a {@link ProjectContext}
 * snapshot for downstream planning nodes.
 * <p>
 * Stub implementation -- returns an empty project context.
 */
@Component
public class UploadContextNode {

    public Map<String, Object> apply(WorldmindState state) {
        var context = new ProjectContext(
                ".", List.of(), "unknown", "unknown", Map.of(), 0,
                "No project scanned yet"
        );
        return Map.of(
                "projectContext", context,
                "status", MissionStatus.PLANNING.name()
        );
    }
}
