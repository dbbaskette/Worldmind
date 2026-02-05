package com.worldmind.core.scanner;

import com.worldmind.core.model.ProjectContext;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Set;

/**
 * Walks a project directory and builds a {@link ProjectContext} snapshot
 * containing the file tree, detected language, framework, and dependency info.
 * <p>
 * Common build-tool and IDE directories (e.g. {@code .git}, {@code node_modules},
 * {@code target}) are automatically excluded from the scan.
 */
@Service
public class ProjectScanner {

    /** Directories to skip during the walk. */
    private static final Set<String> IGNORE_DIRS = Set.of(
            ".git", "node_modules", "target", "build", ".idea", ".vscode",
            "__pycache__", ".gradle", "dist", "out", ".mvn", ".next"
    );

    /** Individual files to skip during the walk. */
    private static final Set<String> IGNORE_FILES = Set.of(
            ".DS_Store", "Thumbs.db"
    );

    /**
     * Scans the given project root and returns a {@link ProjectContext} describing
     * the project's structure, language, framework, and dependencies.
     *
     * @param projectRoot the root directory of the target project
     * @return a populated {@link ProjectContext}
     * @throws IOException if the directory walk fails
     */
    public ProjectContext scan(Path projectRoot) throws IOException {
        var fileTree = new ArrayList<String>();
        String language = "unknown";
        String framework = "unknown";
        var dependencies = new HashMap<String, String>();

        try (var stream = Files.walk(projectRoot)) {
            stream.filter(p -> !shouldIgnore(projectRoot, p))
                  .forEach(p -> {
                      String relative = projectRoot.relativize(p).toString();
                      if (!relative.isEmpty()) {
                          fileTree.add(relative);
                      }
                  });
        }

        // Detect language and framework from manifest files
        if (Files.exists(projectRoot.resolve("pom.xml"))) {
            language = "java";
            framework = "maven";
        } else if (Files.exists(projectRoot.resolve("build.gradle"))
                || Files.exists(projectRoot.resolve("build.gradle.kts"))) {
            language = "java";
            framework = "gradle";
        } else if (Files.exists(projectRoot.resolve("package.json"))) {
            language = "javascript";
            framework = "node";
        } else if (Files.exists(projectRoot.resolve("requirements.txt"))
                || Files.exists(projectRoot.resolve("pyproject.toml"))) {
            language = "python";
            framework = "pip";
        } else if (Files.exists(projectRoot.resolve("go.mod"))) {
            language = "go";
            framework = "go-modules";
        } else if (Files.exists(projectRoot.resolve("Cargo.toml"))) {
            language = "rust";
            framework = "cargo";
        }

        // Build a human-readable summary
        String summary = String.format("%s project with %d files", language, fileTree.size());

        return new ProjectContext(
                projectRoot.toString(),
                fileTree,
                language,
                framework,
                dependencies,
                fileTree.size(),
                summary
        );
    }

    /**
     * Returns {@code true} if the given path should be excluded from the scan.
     * A path is ignored when any of its components match an entry in
     * {@link #IGNORE_DIRS} or {@link #IGNORE_FILES}.
     */
    private boolean shouldIgnore(Path root, Path path) {
        for (Path component : root.relativize(path)) {
            String name = component.toString();
            if (IGNORE_DIRS.contains(name)) return true;
            if (IGNORE_FILES.contains(name)) return true;
        }
        return false;
    }
}
