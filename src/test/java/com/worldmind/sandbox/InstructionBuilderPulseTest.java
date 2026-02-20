package com.worldmind.sandbox;

import com.worldmind.core.model.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class InstructionBuilderResearcherTest {

    @Test
    @DisplayName("Researcher instruction contains read-only constraints")
    void researcherInstructionContainsReadOnlyConstraints() {
        var task = new Task(
                "TASK-001", "RESEARCHER", "Analyze dependency tree",
                "Check for CVEs", "Report generated", List.of(),
                TaskStatus.PENDING, 0, 1, FailureStrategy.SKIP, List.of(), List.of(), null
        );

        var result = InstructionBuilder.buildResearcherInstruction(task, null);

        assertTrue(result.contains("READ-ONLY"));
        assertTrue(result.contains("Do NOT create, modify, or delete any files"));
        assertTrue(result.contains("Analyze dependency tree"));
        assertTrue(result.contains("Check for CVEs"));
        assertTrue(result.contains("Report generated"));
    }

    @Test
    @DisplayName("Researcher instruction includes project context when provided")
    void researcherInstructionIncludesProjectContext() {
        var task = new Task(
                "TASK-002", "RESEARCHER", "Review architecture",
                "", "Architecture documented", List.of(),
                TaskStatus.PENDING, 0, 1, FailureStrategy.SKIP, List.of(), List.of(), null
        );
        var context = new ProjectContext(
                "/app", List.of("src/Main.java", "pom.xml"),
                "Java", "Spring Boot", Map.of("spring-boot", "3.4.2"), 2, "A Spring app"
        );

        var result = InstructionBuilder.buildResearcherInstruction(task, context);

        assertTrue(result.contains("Java"));
        assertTrue(result.contains("Spring Boot"));
        assertTrue(result.contains("Review architecture"));
    }

    @Test
    @DisplayName("Researcher instruction handles null context gracefully")
    void researcherInstructionHandlesNullContext() {
        var task = new Task(
                "TASK-003", "RESEARCHER", "Quick scan",
                null, "Scan done", List.of(),
                TaskStatus.PENDING, 0, 1, FailureStrategy.SKIP, List.of(), List.of(), null
        );

        var result = InstructionBuilder.buildResearcherInstruction(task, null);

        assertNotNull(result);
        assertTrue(result.contains("Quick scan"));
        assertFalse(result.contains("Additional Context"));
    }
}
