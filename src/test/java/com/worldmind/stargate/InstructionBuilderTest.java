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

    // --- Gauntlet instruction tests ---

    @Test
    void gauntletIncludesForgeDirectiveId() {
        var directive = forgeDirective("DIR-100", "Implement user service");
        var context = sampleContext();
        var fileChanges = List.of(new FileRecord("src/UserService.java", "created", 45));

        String instruction = InstructionBuilder.buildGauntletInstruction(directive, context, fileChanges);

        assertTrue(instruction.contains("DIR-100"));
        assertTrue(instruction.contains("Test Directive for: DIR-100"));
    }

    @Test
    void gauntletIncludesFileChanges() {
        var directive = forgeDirective("DIR-101", "Add REST endpoint");
        var context = sampleContext();
        var fileChanges = List.of(
            new FileRecord("src/Controller.java", "modified", 30),
            new FileRecord("src/Route.java", "created", 15)
        );

        String instruction = InstructionBuilder.buildGauntletInstruction(directive, context, fileChanges);

        assertTrue(instruction.contains("modified: src/Controller.java (30 lines)"));
        assertTrue(instruction.contains("created: src/Route.java (15 lines)"));
    }

    @Test
    void gauntletIncludesTestInstructions() {
        var directive = forgeDirective("DIR-102", "Fix bug");
        var context = sampleContext();

        String instruction = InstructionBuilder.buildGauntletInstruction(directive, context, List.of());

        assertTrue(instruction.contains("Run all existing tests"));
        assertTrue(instruction.contains("Do NOT fix any code"));
    }

    @Test
    void gauntletHandlesEmptyFileChanges() {
        var directive = forgeDirective("DIR-103", "Refactor module");
        var context = sampleContext();

        String instruction = InstructionBuilder.buildGauntletInstruction(directive, context, List.of());

        assertTrue(instruction.contains("No file changes recorded"));
    }

    @Test
    void gauntletIncludesProjectContext() {
        var directive = forgeDirective("DIR-104", "Add feature");
        var context = sampleContext();

        String instruction = InstructionBuilder.buildGauntletInstruction(directive, context, List.of());

        assertTrue(instruction.contains("java"));
        assertTrue(instruction.contains("spring"));
    }

    // --- Vigil instruction tests ---

    @Test
    void vigilIncludesForgeDirectiveId() {
        var directive = forgeDirective("DIR-200", "Create data layer");
        var context = sampleContext();
        var fileChanges = List.of(new FileRecord("src/Repository.java", "created", 60));
        var testResult = new TestResult("DIR-200", true, 10, 0, "All passed", 1500L);

        String instruction = InstructionBuilder.buildVigilInstruction(directive, context, fileChanges, testResult);

        assertTrue(instruction.contains("DIR-200"));
        assertTrue(instruction.contains("Code Review for: DIR-200"));
    }

    @Test
    void vigilIncludesFileChanges() {
        var directive = forgeDirective("DIR-201", "Update config");
        var context = sampleContext();
        var fileChanges = List.of(
            new FileRecord("src/Config.java", "modified", 20),
            new FileRecord("src/Props.java", "created", 10)
        );
        var testResult = new TestResult("DIR-201", true, 5, 0, "OK", 800L);

        String instruction = InstructionBuilder.buildVigilInstruction(directive, context, fileChanges, testResult);

        assertTrue(instruction.contains("modified: src/Config.java (20 lines)"));
        assertTrue(instruction.contains("created: src/Props.java (10 lines)"));
    }

    @Test
    void vigilIncludesTestResults() {
        var directive = forgeDirective("DIR-202", "Add validation");
        var context = sampleContext();
        var fileChanges = List.of(new FileRecord("src/Validator.java", "created", 35));
        var testResult = new TestResult("DIR-202", true, 12, 2, "2 failures", 2000L);

        String instruction = InstructionBuilder.buildVigilInstruction(directive, context, fileChanges, testResult);

        assertTrue(instruction.contains("Tests passed: true"));
        assertTrue(instruction.contains("Total: 12, Failed: 2"));
        assertTrue(instruction.contains("Duration: 2000ms"));
    }

    @Test
    void vigilHandlesNullTestResult() {
        var directive = forgeDirective("DIR-203", "Refactor service");
        var context = sampleContext();
        var fileChanges = List.of(new FileRecord("src/Service.java", "modified", 25));

        String instruction = InstructionBuilder.buildVigilInstruction(directive, context, fileChanges, null);

        assertTrue(instruction.contains("No test results available"));
    }

    @Test
    void vigilIncludesReviewInstructions() {
        var directive = forgeDirective("DIR-204", "Add security filter");
        var context = sampleContext();
        var fileChanges = List.of(new FileRecord("src/Filter.java", "created", 40));
        var testResult = new TestResult("DIR-204", true, 8, 0, "OK", 1200L);

        String instruction = InstructionBuilder.buildVigilInstruction(directive, context, fileChanges, testResult);

        assertTrue(instruction.contains("quality score"));
        assertTrue(instruction.contains("Review each modified file"));
    }

    @Test
    void vigilIncludesProjectContext() {
        var directive = forgeDirective("DIR-205", "Update persistence");
        var context = sampleContext();
        var fileChanges = List.of(new FileRecord("src/Repo.java", "modified", 18));
        var testResult = new TestResult("DIR-205", true, 6, 0, "OK", 900L);

        String instruction = InstructionBuilder.buildVigilInstruction(directive, context, fileChanges, testResult);

        assertTrue(instruction.contains("java"));
        assertTrue(instruction.contains("spring"));
    }

    // --- Test helpers ---

    private Directive forgeDirective(String id, String description) {
        return new Directive(
            id, "FORGE", description,
            "", "It works",
            List.of(), DirectiveStatus.PENDING, 0, 3,
            FailureStrategy.RETRY, List.of(), null
        );
    }

    private ProjectContext sampleContext() {
        return new ProjectContext("/tmp/project", List.of("src/", "  main.java"),
            "java", "spring", Map.of("spring-boot", "3.4"), 10, "A Spring app");
    }
}
