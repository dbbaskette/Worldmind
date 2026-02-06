package com.worldmind.stargate;

import com.worldmind.core.model.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class InstructionBuilderTest {

    @Test
    void buildIncludesDirectiveFields() {
        var directive = new Directive(
            "DIR-001", "FORGE", "Create hello.py",
            "Python project with pytest", "File hello.py exists and is valid Python",
            List.of(), DirectiveStatus.PENDING, 0, 3,
            FailureStrategy.RETRY, List.of(), null
        );
        var context = new ProjectContext("/tmp/project", List.of("src/", "  main.py"),
            "python", "flask", Map.of("flask", "2.3", "pytest", "7.4"), 10, "A Flask web app");

        String instruction = InstructionBuilder.build(directive, context);

        assertTrue(instruction.contains("DIR-001"));
        assertTrue(instruction.contains("Create hello.py"));
        assertTrue(instruction.contains("File hello.py exists and is valid Python"));
        assertTrue(instruction.contains("python"));
        assertTrue(instruction.contains("flask"));
        assertTrue(instruction.contains("A Flask web app"));
    }

    @Test
    void buildIncludesConstraintsSection() {
        var directive = new Directive(
            "DIR-002", "FORGE", "Add endpoint",
            "", "Endpoint returns 200",
            List.of(), DirectiveStatus.PENDING, 0, 3,
            FailureStrategy.RETRY, List.of(), null
        );
        var context = new ProjectContext("/tmp/p", List.of(), "java", "spring",
            Map.of(), 5, "");

        String instruction = InstructionBuilder.build(directive, context);

        assertTrue(instruction.contains("Constraints"));
        assertTrue(instruction.contains("Only modify files related to this directive"));
    }

    @Test
    void buildWithNullContextDoesNotThrow() {
        var directive = new Directive(
            "DIR-003", "FORGE", "Do something",
            "some context", "It works",
            List.of(), DirectiveStatus.PENDING, 0, 3,
            FailureStrategy.RETRY, List.of(), null
        );

        String instruction = InstructionBuilder.build(directive, null);

        assertTrue(instruction.contains("DIR-003"));
        assertTrue(instruction.contains("Do something"));
    }
}
