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

}
