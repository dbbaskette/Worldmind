package com.worldmind.core.scheduler;

import com.worldmind.core.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DirectiveSchedulerTest {

    private DirectiveScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new DirectiveScheduler();
    }

    private Directive directive(String id, List<String> deps) {
        return new Directive(id, "FORGE", "Do " + id, "", "Done", deps,
                DirectiveStatus.PENDING, 0, 3, FailureStrategy.RETRY, List.of(), List.of(), null);
    }

    @Test
    @DisplayName("3 independent directives -> wave of 3")
    void threeIndependentDirectives() {
        var directives = List.of(directive("A", List.of()), directive("B", List.of()), directive("C", List.of()));
        var result = scheduler.computeNextWave(directives, Set.of(), ExecutionStrategy.PARALLEL, 10);
        assertEquals(3, result.size());
        assertTrue(result.containsAll(List.of("A", "B", "C")));
    }

    @Test
    @DisplayName("Linear chain A->B->C -> waves of 1")
    void linearChain() {
        var directives = List.of(
                directive("A", List.of()),
                directive("B", List.of("A")),
                directive("C", List.of("B"))
        );

        // Wave 1: only A is eligible
        var wave1 = scheduler.computeNextWave(directives, Set.of(), ExecutionStrategy.PARALLEL, 10);
        assertEquals(List.of("A"), wave1);

        // Wave 2: A completed, B eligible
        var wave2 = scheduler.computeNextWave(directives, Set.of("A"), ExecutionStrategy.PARALLEL, 10);
        assertEquals(List.of("B"), wave2);

        // Wave 3: A,B completed, C eligible
        var wave3 = scheduler.computeNextWave(directives, Set.of("A", "B"), ExecutionStrategy.PARALLEL, 10);
        assertEquals(List.of("C"), wave3);
    }

    @Test
    @DisplayName("Diamond A->{B,C}->D -> waves [A], [B,C], [D]")
    void diamondDependency() {
        var directives = List.of(
                directive("A", List.of()),
                directive("B", List.of("A")),
                directive("C", List.of("A")),
                directive("D", List.of("B", "C"))
        );

        var wave1 = scheduler.computeNextWave(directives, Set.of(), ExecutionStrategy.PARALLEL, 10);
        assertEquals(List.of("A"), wave1);

        var wave2 = scheduler.computeNextWave(directives, Set.of("A"), ExecutionStrategy.PARALLEL, 10);
        assertEquals(2, wave2.size());
        assertTrue(wave2.containsAll(List.of("B", "C")));

        var wave3 = scheduler.computeNextWave(directives, Set.of("A", "B", "C"), ExecutionStrategy.PARALLEL, 10);
        assertEquals(List.of("D"), wave3);
    }

    @Test
    @DisplayName("SEQUENTIAL strategy caps wave to 1")
    void sequentialCapsToOne() {
        var directives = List.of(directive("A", List.of()), directive("B", List.of()), directive("C", List.of()));
        var result = scheduler.computeNextWave(directives, Set.of(), ExecutionStrategy.SEQUENTIAL, 10);
        assertEquals(1, result.size());
    }

    @Test
    @DisplayName("maxParallel limits wave size")
    void maxParallelLimitsWaveSize() {
        var directives = List.of(
                directive("A", List.of()), directive("B", List.of()),
                directive("C", List.of()), directive("D", List.of())
        );
        var result = scheduler.computeNextWave(directives, Set.of(), ExecutionStrategy.PARALLEL, 2);
        assertEquals(2, result.size());
    }

    @Test
    @DisplayName("Completed directives excluded from waves")
    void completedDirectivesExcluded() {
        var directives = List.of(directive("A", List.of()), directive("B", List.of()), directive("C", List.of()));
        var result = scheduler.computeNextWave(directives, Set.of("A", "B"), ExecutionStrategy.PARALLEL, 10);
        assertEquals(List.of("C"), result);
    }

    @Test
    @DisplayName("Directives with unsatisfied deps excluded even if not completed")
    void unsatisfiedDepsExcluded() {
        var directives = List.of(
                directive("A", List.of()),
                directive("B", List.of("A")),
                directive("C", List.of("B"))
        );
        // Only A completed; B is eligible but C is not (depends on B)
        var result = scheduler.computeNextWave(directives, Set.of("A"), ExecutionStrategy.PARALLEL, 10);
        assertEquals(List.of("B"), result);
    }

    @Test
    @DisplayName("All directives completed returns empty list")
    void allCompletedReturnsEmpty() {
        var directives = List.of(directive("A", List.of()), directive("B", List.of()));
        var result = scheduler.computeNextWave(directives, Set.of("A", "B"), ExecutionStrategy.PARALLEL, 10);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("Empty directives list returns empty wave")
    void emptyDirectivesReturnsEmpty() {
        var result = scheduler.computeNextWave(List.of(), Set.of(), ExecutionStrategy.PARALLEL, 10);
        assertTrue(result.isEmpty());
    }

    // --- File Overlap Tests ---

    private Directive directiveWithFiles(String id, List<String> deps, List<String> targetFiles) {
        return new Directive(id, "FORGE", "Do " + id, "", "Done", deps,
                DirectiveStatus.PENDING, 0, 3, FailureStrategy.RETRY, targetFiles, List.of(), null);
    }

    @Test
    @DisplayName("PARALLEL: Directives with overlapping files are serialized")
    void parallelOverlappingFilesSerialized() {
        var directives = List.of(
                directiveWithFiles("A", List.of(), List.of("index.html", "style.css")),
                directiveWithFiles("B", List.of(), List.of("index.html", "game.js")),  // overlaps on index.html
                directiveWithFiles("C", List.of(), List.of("other.js"))
        );

        var wave1 = scheduler.computeNextWave(directives, Set.of(), ExecutionStrategy.PARALLEL, 10);
        // A and C can run together, but B must wait (overlaps with A on index.html)
        assertEquals(2, wave1.size());
        assertTrue(wave1.contains("A"));
        assertTrue(wave1.contains("C"));
        assertFalse(wave1.contains("B"));

        // After A completes, B can run
        var wave2 = scheduler.computeNextWave(directives, Set.of("A", "C"), ExecutionStrategy.PARALLEL, 10);
        assertEquals(List.of("B"), wave2);
    }

    @Test
    @DisplayName("PARALLEL: Non-overlapping files run in same wave")
    void parallelNonOverlappingFilesAllowed() {
        var directives = List.of(
                directiveWithFiles("A", List.of(), List.of("backend/service.java")),
                directiveWithFiles("B", List.of(), List.of("frontend/app.tsx")),
                directiveWithFiles("C", List.of(), List.of("tests/test.java"))
        );

        var wave = scheduler.computeNextWave(directives, Set.of(), ExecutionStrategy.PARALLEL, 10);
        assertEquals(3, wave.size());
        assertTrue(wave.containsAll(List.of("A", "B", "C")));
    }

    @Test
    @DisplayName("SEQUENTIAL: File overlap doesn't matter - only one at a time anyway")
    void sequentialIgnoresFileOverlap() {
        var directives = List.of(
                directiveWithFiles("A", List.of(), List.of("index.html")),
                directiveWithFiles("B", List.of(), List.of("index.html"))  // same file
        );

        var wave = scheduler.computeNextWave(directives, Set.of(), ExecutionStrategy.SEQUENTIAL, 10);
        assertEquals(1, wave.size());  // Sequential always returns 1
    }

    @Test
    @DisplayName("PARALLEL: Relative vs absolute path matching")
    void parallelPathNormalization() {
        var directives = List.of(
                directiveWithFiles("A", List.of(), List.of("./src/index.html")),
                directiveWithFiles("B", List.of(), List.of("src/index.html"))  // same file, different format
        );

        var wave = scheduler.computeNextWave(directives, Set.of(), ExecutionStrategy.PARALLEL, 10);
        assertEquals(1, wave.size());  // Only one can run - they target the same file
    }

    @Test
    @DisplayName("PARALLEL: Suffix matching for partial paths")
    void parallelSuffixPathMatching() {
        var directives = List.of(
                directiveWithFiles("A", List.of(), List.of("public/index.html")),
                directiveWithFiles("B", List.of(), List.of("/full/path/to/project/public/index.html"))
        );

        var wave = scheduler.computeNextWave(directives, Set.of(), ExecutionStrategy.PARALLEL, 10);
        assertEquals(1, wave.size());  // Suffix match detects overlap
    }

    @Test
    @DisplayName("PARALLEL: Empty targetFiles don't block anything")
    void parallelEmptyTargetFilesNoBlock() {
        var directives = List.of(
                directiveWithFiles("A", List.of(), List.of()),  // No target files (e.g., PULSE)
                directiveWithFiles("B", List.of(), List.of("index.html")),
                directive("C", List.of())  // Using old helper which has null/empty targetFiles
        );

        var wave = scheduler.computeNextWave(directives, Set.of(), ExecutionStrategy.PARALLEL, 10);
        assertEquals(3, wave.size());  // All can run - no overlap detection on empty targets
    }

    @Test
    @DisplayName("PARALLEL: Multiple files overlap detection")
    void parallelMultipleFilesOverlap() {
        var directives = List.of(
                directiveWithFiles("A", List.of(), List.of("a.js", "b.js", "c.js")),
                directiveWithFiles("B", List.of(), List.of("d.js", "b.js", "e.js")),  // b.js overlaps
                directiveWithFiles("C", List.of(), List.of("f.js", "g.js"))
        );

        var wave1 = scheduler.computeNextWave(directives, Set.of(), ExecutionStrategy.PARALLEL, 10);
        assertEquals(2, wave1.size());
        assertTrue(wave1.contains("A"));
        assertTrue(wave1.contains("C"));
        assertFalse(wave1.contains("B"));  // Blocked by A's b.js
    }
}
