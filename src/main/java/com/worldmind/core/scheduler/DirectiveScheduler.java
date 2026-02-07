package com.worldmind.core.scheduler;

import com.worldmind.core.model.Directive;
import com.worldmind.core.model.ExecutionStrategy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Computes the next wave of eligible directives based on dependency satisfaction,
 * completed directives, execution strategy, and concurrency limits.
 */
@Service
public class DirectiveScheduler {

    /**
     * Compute the next wave of directive IDs eligible for dispatch.
     *
     * @param directives   all directives in the mission plan
     * @param completedIds IDs of directives already completed (PASSED, SKIPPED, or FAILED+SKIP)
     * @param strategy     execution strategy (SEQUENTIAL caps at 1, PARALLEL/ADAPTIVE up to maxParallel)
     * @param maxParallel  maximum concurrent directives
     * @return list of directive IDs to dispatch; empty if all done
     */
    public List<String> computeNextWave(List<Directive> directives, Set<String> completedIds,
                                         ExecutionStrategy strategy, int maxParallel) {
        int limit = strategy == ExecutionStrategy.SEQUENTIAL ? 1 : maxParallel;
        var wave = new ArrayList<String>();

        for (var directive : directives) {
            if (wave.size() >= limit) break;
            if (completedIds.contains(directive.id())) continue;
            if (!allDependenciesSatisfied(directive, completedIds)) continue;
            wave.add(directive.id());
        }

        return wave;
    }

    private boolean allDependenciesSatisfied(Directive directive, Set<String> completedIds) {
        if (directive.dependencies() == null || directive.dependencies().isEmpty()) return true;
        return completedIds.containsAll(directive.dependencies());
    }
}
