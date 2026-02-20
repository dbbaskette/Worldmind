package com.worldmind.sandbox;

import com.worldmind.core.model.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class InstructionBuilderTest {

    @Test
    void buildIncludesTaskFields() {
        var task = new Task(
            "TASK-001", "CODER", "Create hello.py",
            "Python project with pytest", "File hello.py exists and is valid Python",
            List.of(), TaskStatus.PENDING, 0, 3,
            FailureStrategy.RETRY, List.of(), List.of(), null
        );
        var context = new ProjectContext("/tmp/project", List.of("src/", "  main.py"),
            "python", "flask", Map.of("flask", "2.3", "pytest", "7.4"), 10, "A Flask web app");

        String instruction = InstructionBuilder.build(task, context);

        assertTrue(instruction.contains("TASK-001"));
        assertTrue(instruction.contains("Create hello.py"));
        assertTrue(instruction.contains("File hello.py exists and is valid Python"));
        assertTrue(instruction.contains("python"));
        assertTrue(instruction.contains("flask"));
        assertTrue(instruction.contains("A Flask web app"));
    }

    @Test
    void buildIncludesConstraintsSection() {
        var task = new Task(
            "TASK-002", "CODER", "Add endpoint",
            "", "Endpoint returns 200",
            List.of(), TaskStatus.PENDING, 0, 3,
            FailureStrategy.RETRY, List.of(), List.of(), null
        );
        var context = new ProjectContext("/tmp/p", List.of(), "java", "spring",
            Map.of(), 5, "");

        String instruction = InstructionBuilder.build(task, context);

        assertTrue(instruction.contains("Constraints"));
        assertTrue(instruction.contains("Only modify files related to this task"));
    }

    @Test
    void buildWithNullContextDoesNotThrow() {
        var task = new Task(
            "TASK-003", "CODER", "Do something",
            "some context", "It works",
            List.of(), TaskStatus.PENDING, 0, 3,
            FailureStrategy.RETRY, List.of(), List.of(), null
        );

        String instruction = InstructionBuilder.build(task, null);

        assertTrue(instruction.contains("TASK-003"));
        assertTrue(instruction.contains("Do something"));
    }

    // --- Tester instruction tests ---

    @Test
    void testerIncludesCoderTaskId() {
        var task = coderTask("TASK-100", "Implement user service");
        var context = sampleContext();
        var fileChanges = List.of(new FileRecord("src/UserService.java", "created", 45));

        String instruction = InstructionBuilder.buildTesterInstruction(task, context, fileChanges);

        assertTrue(instruction.contains("TASK-100"));
        assertTrue(instruction.contains("Test Task for: TASK-100"));
    }

    @Test
    void testerIncludesFileChanges() {
        var task = coderTask("TASK-101", "Add REST endpoint");
        var context = sampleContext();
        var fileChanges = List.of(
            new FileRecord("src/Controller.java", "modified", 30),
            new FileRecord("src/Route.java", "created", 15)
        );

        String instruction = InstructionBuilder.buildTesterInstruction(task, context, fileChanges);

        assertTrue(instruction.contains("modified: src/Controller.java (30 lines)"));
        assertTrue(instruction.contains("created: src/Route.java (15 lines)"));
    }

    @Test
    void testerIncludesTestInstructions() {
        var task = coderTask("TASK-102", "Fix bug");
        var context = sampleContext();

        String instruction = InstructionBuilder.buildTesterInstruction(task, context, List.of());

        assertTrue(instruction.contains("Run all existing tests"));
        assertTrue(instruction.contains("Do NOT fix any code"));
    }

    @Test
    void testerHandlesEmptyFileChanges() {
        var task = coderTask("TASK-103", "Refactor module");
        var context = sampleContext();

        String instruction = InstructionBuilder.buildTesterInstruction(task, context, List.of());

        assertTrue(instruction.contains("No file changes recorded"));
    }

    @Test
    void testerIncludesProjectContext() {
        var task = coderTask("TASK-104", "Add feature");
        var context = sampleContext();

        String instruction = InstructionBuilder.buildTesterInstruction(task, context, List.of());

        assertTrue(instruction.contains("java"));
        assertTrue(instruction.contains("spring"));
    }

    // --- Reviewer instruction tests ---

    @Test
    void reviewerIncludesCoderTaskId() {
        var task = coderTask("TASK-200", "Create data layer");
        var context = sampleContext();
        var fileChanges = List.of(new FileRecord("src/Repository.java", "created", 60));
        var testResult = new TestResult("TASK-200", true, 10, 0, "All passed", 1500L);

        String instruction = InstructionBuilder.buildReviewerInstruction(task, context, fileChanges, testResult);

        assertTrue(instruction.contains("TASK-200"));
        assertTrue(instruction.contains("Code Review for: TASK-200"));
    }

    @Test
    void reviewerIncludesFileChanges() {
        var task = coderTask("TASK-201", "Update config");
        var context = sampleContext();
        var fileChanges = List.of(
            new FileRecord("src/Config.java", "modified", 20),
            new FileRecord("src/Props.java", "created", 10)
        );
        var testResult = new TestResult("TASK-201", true, 5, 0, "OK", 800L);

        String instruction = InstructionBuilder.buildReviewerInstruction(task, context, fileChanges, testResult);

        assertTrue(instruction.contains("modified: src/Config.java (20 lines)"));
        assertTrue(instruction.contains("created: src/Props.java (10 lines)"));
    }

    @Test
    void reviewerIncludesTestResults() {
        var task = coderTask("TASK-202", "Add validation");
        var context = sampleContext();
        var fileChanges = List.of(new FileRecord("src/Validator.java", "created", 35));
        var testResult = new TestResult("TASK-202", true, 12, 2, "2 failures", 2000L);

        String instruction = InstructionBuilder.buildReviewerInstruction(task, context, fileChanges, testResult);

        assertTrue(instruction.contains("Tests passed: true"));
        assertTrue(instruction.contains("Total: 12, Failed: 2"));
        assertTrue(instruction.contains("Duration: 2000ms"));
    }

    @Test
    void reviewerHandlesNullTestResult() {
        var task = coderTask("TASK-203", "Refactor service");
        var context = sampleContext();
        var fileChanges = List.of(new FileRecord("src/Service.java", "modified", 25));

        String instruction = InstructionBuilder.buildReviewerInstruction(task, context, fileChanges, null);

        assertTrue(instruction.contains("No test results available"));
    }

    @Test
    void reviewerIncludesReviewInstructions() {
        var task = coderTask("TASK-204", "Add security filter");
        var context = sampleContext();
        var fileChanges = List.of(new FileRecord("src/Filter.java", "created", 40));
        var testResult = new TestResult("TASK-204", true, 8, 0, "OK", 1200L);

        String instruction = InstructionBuilder.buildReviewerInstruction(task, context, fileChanges, testResult);

        assertTrue(instruction.contains("quality score"));
        assertTrue(instruction.contains("Review each modified file"));
    }

    @Test
    void reviewerIncludesProjectContext() {
        var task = coderTask("TASK-205", "Update persistence");
        var context = sampleContext();
        var fileChanges = List.of(new FileRecord("src/Repo.java", "modified", 18));
        var testResult = new TestResult("TASK-205", true, 6, 0, "OK", 900L);

        String instruction = InstructionBuilder.buildReviewerInstruction(task, context, fileChanges, testResult);

        assertTrue(instruction.contains("java"));
        assertTrue(instruction.contains("spring"));
    }

    // --- Refactorer instruction tests ---

    @Test
    void refactorerIncludesRefactoringTaskHeader() {
        var task = coderTask("TASK-300", "Extract helper methods");
        var context = sampleContext();
        var baseline = new TestResult("TASK-300", true, 15, 0, "All passed", 1000L);

        String instruction = InstructionBuilder.buildRefactorerInstruction(task, context, baseline);

        assertTrue(instruction.contains("Refactoring Task: TASK-300"));
        assertTrue(instruction.contains("BEHAVIORAL EQUIVALENCE"));
    }

    @Test
    void refactorerIncludesBaselineTestInfo() {
        var task = coderTask("TASK-301", "Reduce duplication");
        var context = sampleContext();
        var baseline = new TestResult("TASK-301", true, 20, 2, "Some failures", 1500L);

        String instruction = InstructionBuilder.buildRefactorerInstruction(task, context, baseline);

        assertTrue(instruction.contains("Tests passed: true"));
        assertTrue(instruction.contains("Total: 20, Failed: 2"));
    }

    @Test
    void refactorerHandlesNullBaseline() {
        var task = coderTask("TASK-302", "Improve naming");
        var context = sampleContext();

        String instruction = InstructionBuilder.buildRefactorerInstruction(task, context, null);

        assertTrue(instruction.contains("No baseline tests available"));
    }

    // --- Test helpers ---

    private Task coderTask(String id, String description) {
        return new Task(
            id, "CODER", description,
            "", "It works",
            List.of(), TaskStatus.PENDING, 0, 3,
            FailureStrategy.RETRY, List.of(), List.of(), null
        );
    }

    private ProjectContext sampleContext() {
        return new ProjectContext("/tmp/project", List.of("src/", "  main.java"),
            "java", "spring", Map.of("spring-boot", "3.4"), 10, "A Spring app");
    }
}
