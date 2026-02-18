package com.worldmind.core.scheduler;

import com.worldmind.core.model.Directive;
import com.worldmind.core.model.ExecutionStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Computes the next wave of eligible directives based on dependency satisfaction,
 * completed directives, execution strategy, and concurrency limits.
 * 
 * <p>For parallel execution, also detects file overlap conflicts: if two directives
 * both target the same file, they cannot run in the same wave (one would overwrite
 * the other's changes). Such conflicts are automatically serialized.
 */
@Service
public class DirectiveScheduler {

    private static final Logger log = LoggerFactory.getLogger(DirectiveScheduler.class);

    /**
     * Compute the next wave of directive IDs eligible for dispatch.
     *
     * @param directives   all directives in the mission plan
     * @param completedIds IDs of directives already completed (PASSED, SKIPPED, or FAILED+SKIP)
     * @param strategy     execution strategy (SEQUENTIAL caps at 1, PARALLEL up to maxParallel)
     * @param maxParallel  maximum concurrent directives
     * @return list of directive IDs to dispatch; empty if all done
     */
    public List<String> computeNextWave(List<Directive> directives, Set<String> completedIds,
                                         ExecutionStrategy strategy, int maxParallel) {
        int limit = strategy == ExecutionStrategy.SEQUENTIAL ? 1 : maxParallel;

        log.debug("computeNextWave: {} directives, {} completed {}, strategy={}, limit={}",
                directives.size(), completedIds.size(), completedIds, strategy, limit);

        // Build a lookup: centurion type → completed directive IDs of that type.
        // This lets us resolve dependencies expressed as centurion names (e.g. "PULSE")
        // in addition to concrete directive IDs (e.g. "DIR-001").
        var completedTypes = new java.util.HashMap<String, String>();
        for (var d : directives) {
            if (completedIds.contains(d.id()) && d.centurion() != null) {
                completedTypes.put(d.centurion().toUpperCase(), d.id());
            }
        }

        var wave = new ArrayList<String>();
        
        // Track files claimed by directives in this wave (for conflict detection)
        var claimedFiles = new HashSet<String>();
        
        for (var directive : directives) {
            if (wave.size() >= limit) break;
            if (completedIds.contains(directive.id())) {
                log.debug("  {} [{}] — already completed", directive.id(), directive.centurion());
                continue;
            }
            if (!allDependenciesSatisfied(directive, completedIds, completedTypes)) {
                log.debug("  {} [{}] — deps unsatisfied: {}", directive.id(), directive.centurion(), directive.dependencies());
                continue;
            }
            
            // Check for file overlap with already-scheduled wave directives
            if (strategy == ExecutionStrategy.PARALLEL && hasFileOverlap(directive, claimedFiles)) {
                log.info("  {} [{}] — file overlap with wave, deferring to next wave (targets: {})", 
                        directive.id(), directive.centurion(), directive.targetFiles());
                continue;
            }
            
            log.debug("  {} [{}] — eligible (deps: {})", directive.id(), directive.centurion(), directive.dependencies());
            wave.add(directive.id());
            
            // Claim this directive's target files
            if (directive.targetFiles() != null) {
                claimedFiles.addAll(directive.targetFiles());
            }
        }

        return wave;
    }

    private boolean allDependenciesSatisfied(Directive directive, Set<String> completedIds,
                                              java.util.Map<String, String> completedTypes) {
        if (directive.dependencies() == null || directive.dependencies().isEmpty()) return true;
        for (var dep : directive.dependencies()) {
            // Check as directive ID first, then as centurion type name
            if (!completedIds.contains(dep) && !completedTypes.containsKey(dep.toUpperCase())) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * Checks if a directive's target files overlap with already-claimed files in the wave.
     * Uses flexible matching: files match if they're equal OR if one is a suffix of the other
     * (to handle relative vs. absolute paths).
     */
    private boolean hasFileOverlap(Directive directive, Set<String> claimedFiles) {
        List<String> targets = directive.targetFiles();
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
