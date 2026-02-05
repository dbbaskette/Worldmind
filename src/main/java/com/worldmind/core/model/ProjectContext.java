package com.worldmind.core.model;

import java.util.List;
import java.util.Map;

/**
 * Snapshot of the target project's structure and metadata, gathered during the upload phase.
 */
public record ProjectContext(
    String rootPath,
    List<String> fileTree,
    String language,
    String framework,
    Map<String, String> dependencies,
    int fileCount,
    String summary
) {}
