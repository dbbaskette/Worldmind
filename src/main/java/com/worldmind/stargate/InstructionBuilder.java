package com.worldmind.stargate;

import com.worldmind.core.model.Directive;
import com.worldmind.core.model.ProjectContext;

/**
 * Converts a Directive and ProjectContext into a Goose-readable instruction string.
 * Pure function — no Spring dependencies.
 */
public final class InstructionBuilder {

    private InstructionBuilder() {}

    public static String build(Directive directive, ProjectContext context) {
        var sb = new StringBuilder();

        sb.append("# Directive: ").append(directive.id()).append("\n\n");

        sb.append("## Objective\n\n");
        sb.append(directive.description()).append("\n\n");

        if (directive.inputContext() != null && !directive.inputContext().isBlank()) {
            sb.append("## Additional Context\n\n");
            sb.append(directive.inputContext()).append("\n\n");
        }

        if (context != null) {
            sb.append("## Project Context\n\n");
            sb.append("- **Language:** ").append(context.language()).append("\n");
            sb.append("- **Framework:** ").append(context.framework()).append("\n");
            if (context.summary() != null && !context.summary().isBlank()) {
                sb.append("- **Summary:** ").append(context.summary()).append("\n");
            }
            if (context.dependencies() != null && !context.dependencies().isEmpty()) {
                sb.append("- **Dependencies:** ").append(formatDependencies(context)).append("\n");
            }
            if (context.fileTree() != null && !context.fileTree().isEmpty()) {
                sb.append("\n### File Structure\n\n```\n");
                sb.append(String.join("\n", context.fileTree())).append("\n```\n");
            }
            sb.append("\n");
        }

        sb.append("## Success Criteria\n\n");
        sb.append(directive.successCriteria()).append("\n\n");

        sb.append("## Constraints\n\n");
        sb.append("- Only modify files related to this directive\n");
        sb.append("- Do not modify test files (Gauntlet handles tests)\n");
        sb.append("- Commit nothing — file changes are detected externally\n");
        sb.append("- If you encounter an error, attempt to fix it before reporting failure\n");

        return sb.toString();
    }

    private static String formatDependencies(ProjectContext context) {
        return String.join(", ", context.dependencies().entrySet().stream()
            .map(e -> e.getKey() + ":" + e.getValue())
            .sorted()
            .toList());
    }
}
