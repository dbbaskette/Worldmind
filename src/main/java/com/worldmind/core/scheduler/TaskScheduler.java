package com.worldmind.core.scheduler;

import com.worldmind.core.model.Task;
import com.worldmind.core.model.ExecutionStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Computes the next wave of eligible tasks based on dependency satisfaction,
 * completed tasks, execution strategy, and concurrency limits.
 * 
 * <p>For parallel execution, also detects file overlap conflicts: if two tasks
 * both target the same file, they cannot run in the same wave (one would overwrite
 * the other's changes). Such conflicts are automatically serialized.
 */
@Service
public class TaskScheduler {

    private static final Logger log = LoggerFactory.getLogger(TaskScheduler.class);

    /**
     * Compute the next wave of task IDs eligible for dispatch.
     *
     * @param tasks   all tasks in the mission plan
     * @param completedIds IDs of tasks already completed (PASSED, SKIPPED, or FAILED+SKIP)
     * @param strategy     execution strategy (SEQUENTIAL caps at 1, PARALLEL up to maxParallel)
     * @param maxParallel  maximum concurrent tasks
     * @return list of task IDs to dispatch; empty if all done
     */
    public List<String> computeNextWave(List<Task> tasks, Set<String> completedIds,
                                         ExecutionStrategy strategy, int maxParallel) {
        int limit = strategy == ExecutionStrategy.SEQUENTIAL ? 1 : maxParallel;

        log.info("computeNextWave: {} tasks, {} completed, strategy={}, maxParallel={}",
                tasks.size(), completedIds.size(), strategy, limit);

        // Build a lookup: agent type → completed task IDs of that type.
        // This lets us resolve dependencies expressed as agent names (e.g. "RESEARCHER")
        // in addition to concrete task IDs (e.g. "TASK-001").
        var completedTypes = new java.util.HashMap<String, String>();
        for (var d : tasks) {
            if (completedIds.contains(d.id()) && d.agent() != null) {
                completedTypes.put(d.agent().toUpperCase(), d.id());
            }
        }

        var wave = new ArrayList<String>();
        
        // Track files claimed by tasks in this wave (for conflict detection)
        var claimedFiles = new HashSet<String>();
        
        for (var task : tasks) {
            if (wave.size() >= limit) break;
            if (completedIds.contains(task.id())) {
                log.debug("  {} [{}] — already completed", task.id(), task.agent());
                continue;
            }
            if (!allDependenciesSatisfied(task, completedIds, completedTypes)) {
                log.debug("  {} [{}] — deps unsatisfied: {}", task.id(), task.agent(), task.dependencies());
                continue;
            }
            
            // Check for file overlap with already-scheduled wave tasks
            if (strategy == ExecutionStrategy.PARALLEL && hasFileOverlap(task, claimedFiles)) {
                log.info("  {} [{}] — file overlap with wave, deferring to next wave (targets: {})", 
                        task.id(), task.agent(), task.targetFiles());
                continue;
            }
            
            log.debug("  {} [{}] — eligible (deps: {})", task.id(), task.agent(), task.dependencies());
            wave.add(task.id());
            
            // Claim this task's target files
            if (task.targetFiles() != null && !task.targetFiles().isEmpty()) {
                claimedFiles.addAll(task.targetFiles());
                log.debug("  {} claims files: {}", task.id(), task.targetFiles());
            }
        }

        // Log summary of file overlap deferrals for parallel execution
        if (strategy == ExecutionStrategy.PARALLEL && !wave.isEmpty()) {
            List<String> deferred = tasks.stream()
                    .filter(d -> !completedIds.contains(d.id()))
                    .filter(d -> !wave.contains(d.id()))
                    .filter(d -> allDependenciesSatisfied(d, completedIds, completedTypes))
                    .map(Task::id)
                    .toList();
            if (!deferred.isEmpty()) {
                log.info("File overlap: {} task(s) deferred to next wave due to file conflicts: {}", 
                        deferred.size(), deferred);
            }
        }

        return wave;
    }

    private boolean allDependenciesSatisfied(Task task, Set<String> completedIds,
                                              java.util.Map<String, String> completedTypes) {
        if (task.dependencies() == null || task.dependencies().isEmpty()) return true;
        for (var dep : task.dependencies()) {
            // Check as task ID first, then as agent type name
            if (!completedIds.contains(dep) && !completedTypes.containsKey(dep.toUpperCase())) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * Checks if a task's target files overlap with already-claimed files in the wave.
     * Uses flexible matching: files match if they're equal OR if one is a suffix of the other
     * (to handle relative vs. absolute paths).
     */
    private boolean hasFileOverlap(Task task, Set<String> claimedFiles) {
        List<String> targets = task.targetFiles();
        if (targets == null || targets.isEmpty() || claimedFiles.isEmpty()) {
            return false;
        }
        
        for (String target : targets) {
            for (String claimed : claimedFiles) {
                if (filesMatch(target, claimed)) {
                    log.debug("File overlap: {} conflicts with claimed {}", target, claimed);
                    return true;
                }
            }
        }
        return false;
    }
    
    /**
     * Checks if two file paths refer to the same file.
     * Handles relative vs. absolute paths by checking if one is a suffix of the other.
     */
    private boolean filesMatch(String file1, String file2) {
        String n1 = normalizePath(file1);
        String n2 = normalizePath(file2);
        
        if (n1.equals(n2)) return true;
        return n1.endsWith("/" + n2) || n2.endsWith("/" + n1);
    }
    
    private String normalizePath(String path) {
        return path.startsWith("./") ? path.substring(2) : path;
    }
}
