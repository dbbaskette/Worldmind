package com.worldmind.core.nodes;

import com.worldmind.core.model.MissionStatus;
import com.worldmind.core.model.ProjectContext;
import com.worldmind.core.scanner.ProjectScanner;
import com.worldmind.core.state.WorldmindState;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Scans the target project directory and produces a {@link ProjectContext}
 * snapshot for downstream planning nodes.
 * <p>
 * Delegates to {@link ProjectScanner} for the actual filesystem walk and
 * language/framework detection.
 */
@Component
public class UploadContextNode {

    private final ProjectScanner scanner;

    public UploadContextNode(ProjectScanner scanner) {
        this.scanner = scanner;
    }

    public Map<String, Object> apply(WorldmindState state) {
        // Default to current directory; could be configured via state later
        Path projectRoot = Path.of(System.getProperty("user.dir"));
        try {
            ProjectContext context = scanner.scan(projectRoot);
            return Map.of(
                    "projectContext", context,
                    "status", MissionStatus.PLANNING.name()
            );
        } catch (IOException e) {
            return Map.of(
                    "projectContext", new ProjectContext(
                            projectRoot.toString(), List.of(), "unknown", "unknown",
                            Map.of(), 0, "Failed to scan: " + e.getMessage()
                    ),
                    "status", MissionStatus.PLANNING.name(),
                    "errors", List.of("Project scan failed: " + e.getMessage())
            );
        }
    }
}
