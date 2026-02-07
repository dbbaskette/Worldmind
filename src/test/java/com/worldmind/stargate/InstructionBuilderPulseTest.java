package com.worldmind.stargate;

import com.worldmind.core.model.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class InstructionBuilderPulseTest {

    @Test
    @DisplayName("Pulse instruction contains read-only constraints")
    void pulseInstructionContainsReadOnlyConstraints() {
        var directive = new Directive(
                "DIR-001", "PULSE", "Analyze dependency tree",
                "Check for CVEs", "Report generated", List.of(),
                DirectiveStatus.PENDING, 0, 1, FailureStrategy.SKIP, List.of(), null
        );

        var result = InstructionBuilder.buildPulseInstruction(directive, null);

        assertTrue(result.contains("READ-ONLY"));
        assertTrue(result.contains("Do NOT create, modify, or delete any files"));
        assertTrue(result.contains("Analyze dependency tree"));
        assertTrue(result.contains("Check for CVEs"));
        assertTrue(result.contains("Report generated"));
    }

    @Test
    @DisplayName("Pulse instruction includes project context when provided")
    void pulseInstructionIncludesProjectContext() {
        var directive = new Directive(
                "DIR-002", "PULSE", "Review architecture",
                "", "Architecture documented", List.of(),
                DirectiveStatus.PENDING, 0, 1, FailureStrategy.SKIP, List.of(), null
        );
        var context = new ProjectContext(
                "/app", List.of("src/Main.java", "pom.xml"),
                "Java", "Spring Boot", Map.of("spring-boot", "3.4.2"), 2, "A Spring app"
        );

        var result = InstructionBuilder.buildPulseInstruction(directive, context);

        assertTrue(result.contains("Java"));
        assertTrue(result.contains("Spring Boot"));
        assertTrue(result.contains("Review architecture"));
    }

    @Test
    @DisplayName("Pulse instruction handles null context gracefully")
    void pulseInstructionHandlesNullContext() {
        var directive = new Directive(
                "DIR-003", "PULSE", "Quick scan",
                null, "Scan done", List.of(),
                DirectiveStatus.PENDING, 0, 1, FailureStrategy.SKIP, List.of(), null
        );

        var result = InstructionBuilder.buildPulseInstruction(directive, null);

        assertNotNull(result);
        assertTrue(result.contains("Quick scan"));
        assertFalse(result.contains("Additional Context"));
    }
}
