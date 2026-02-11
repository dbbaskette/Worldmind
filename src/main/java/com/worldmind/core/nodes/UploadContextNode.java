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
        // Early-exit for retry: if project context already exists, skip re-scanning
        if (state.projectContext().isPresent()) {
            return Map.of("status", MissionStatus.SPECIFYING.name());
        }

        // When running inside Docker, always use /workspace regardless of the
        // client-supplied path (host paths don't exist inside the container).
        String workspaceVolume = System.getenv("WORKSPACE_VOLUME");
        Path projectRoot;
        if (workspaceVolume != null) {
            projectRoot = Path.of("/workspace");
        } else {
            String explicitPath = state.projectPath();
            projectRoot = (explicitPath != null && !explicitPath.isBlank())
                    ? Path.of(explicitPath)
                    : Path.of(System.getProperty("user.dir"));
        }
        try {
            ProjectContext context = scanner.scan(projectRoot);
            return Map.of(
                    "projectContext", context,
                    "status", MissionStatus.SPECIFYING.name()
            );
        } catch (IOException e) {
            return Map.of(
                    "projectContext", new ProjectContext(
                            projectRoot.toString(), List.of(), "unknown", "unknown",
                            Map.of(), 0, "Failed to scan: " + e.getMessage()
                    ),
                    "status", MissionStatus.SPECIFYING.name(),
                    "errors", List.of("Project scan failed: " + e.getMessage())
            );
        }
    }
}
