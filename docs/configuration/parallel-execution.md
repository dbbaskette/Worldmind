# Configuration: Parallel Execution

This guide covers configuration options for parallel directive execution in Worldmind.

## Execution Strategy Selection

### SEQUENTIAL vs PARALLEL

| Strategy | Description | Use Case |
|----------|-------------|----------|
| `SEQUENTIAL` | One directive at a time | New projects, high-risk changes, interdependent work |
| `PARALLEL` | Multiple directives concurrently | Mature codebases, independent features, performance |

### How Strategy is Determined

1. **Manual selection** -- Specify `--strategy SEQUENTIAL` in CLI or API
2. **LLM planner** -- The `PlanMissionNode` analyzes the request and codebase
3. **Default** -- New projects default to `SEQUENTIAL` per planner guidance

### Strategy Selection Guidelines

```
┌─────────────────────────────────────────────────────────────┐
│                    Choose Execution Strategy                │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  Is this a new project or major structural change?          │
│  ├─ YES → Use SEQUENTIAL                                    │
│  └─ NO ↓                                                    │
│                                                             │
│  Do the directives modify shared files?                     │
│  ├─ YES → Use SEQUENTIAL                                    │
│  └─ NO ↓                                                    │
│                                                             │
│  Are the changes independent features?                      │
│  ├─ YES → Use PARALLEL                                      │
│  └─ NO ↓                                                    │
│                                                             │
│  When in doubt → Use SEQUENTIAL                             │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

## Configuration Properties

### Core Parallel Execution Settings

```yaml
worldmind:
  starblaster:
    # Enable git worktree isolation for parallel execution
    # When false, directives share a single working directory
    worktrees-enabled: true

    # Maximum number of directives executing concurrently
    # Higher values = more parallelism, more resource usage
    max-parallel: 4

    # Seconds to wait between waves
    # Helps prevent merge race conditions
    wave-cooldown-seconds: 5

    # Container provider (docker or cloudfoundry)
    provider: docker
```

### Property Reference

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `worldmind.starblaster.worktrees-enabled` | boolean | `false` | Enable worktree-based isolation |
| `worldmind.starblaster.max-parallel` | int | `4` | Maximum concurrent directives |
| `worldmind.starblaster.wave-cooldown-seconds` | int | `5` | Delay between waves |
| `worldmind.starblaster.provider` | string | `docker` | Container provider |

### Environment Variables

Properties can also be set via environment variables:

```bash
export WORLDMIND_STARBLASTER_WORKTREES_ENABLED=true
export WORLDMIND_STARBLASTER_MAX_PARALLEL=4
export WORLDMIND_STARBLASTER_WAVE_COOLDOWN_SECONDS=5
```

## Tuning Parameters

### max-parallel

Controls how many directives can execute simultaneously in a single wave.

**Considerations:**
- Higher values increase throughput but require more resources (CPU, memory, disk I/O)
- Each parallel directive needs its own container and worktree
- Network bandwidth to LLM providers may become a bottleneck

**Recommendations:**

| Environment | Recommended `max-parallel` |
|-------------|---------------------------|
| Development laptop | 2-4 |
| CI/CD server | 4-8 |
| Production (dedicated) | 8-16 |
| Resource-constrained | 1-2 |

### wave-cooldown-seconds

Delay between completing one wave and starting the next.

**Purpose:**
- Allows merge operations to complete
- Reduces race conditions
- Gives git push operations time to propagate

**Recommendations:**

| Scenario | Recommended Value |
|----------|-------------------|
| Local development | 2-5 seconds |
| Remote git (GitHub/GitLab) | 5-10 seconds |
| High-latency network | 10-15 seconds |
| Frequent merge conflicts | 10-15 seconds |

### worktrees-enabled

Enables git worktree isolation for parallel execution.

**When to enable:**
- Running with `PARALLEL` strategy
- Using Docker provider (local execution)
- Need true file isolation between directives

**When to disable:**
- Using Cloud Foundry provider (uses git branches instead)
- Running with `SEQUENTIAL` strategy (no parallel execution)
- Debugging worktree-related issues

## Execution Flow with Parallel Settings

```mermaid
flowchart TD
    Start[Mission Start] --> Plan[Generate Plan]
    Plan --> Strategy{Strategy?}
    
    Strategy -->|SEQUENTIAL| SeqLoop[Execute directives one by one]
    SeqLoop --> Complete
    
    Strategy -->|PARALLEL| Wave1[Wave 1: Schedule up to max-parallel]
    Wave1 --> CheckWorktrees{worktrees-enabled?}
    
    CheckWorktrees -->|Yes| CreateWT[Create worktrees]
    CheckWorktrees -->|No| SharedDir[Use shared directory]
    
    CreateWT --> Execute1[Execute directives in parallel]
    SharedDir --> Execute1
    
    Execute1 --> Merge1[Merge completed branches]
    Merge1 --> Cooldown1[Wait wave-cooldown-seconds]
    Cooldown1 --> MoreWaves{More directives?}
    
    MoreWaves -->|Yes| Wave2[Wave 2: Schedule next batch]
    Wave2 --> Execute2[Execute directives in parallel]
    Execute2 --> Merge2[Merge completed branches]
    Merge2 --> MoreWaves
    
    MoreWaves -->|No| Complete[Mission Complete]
```

## Provider-Specific Configuration

### Docker Provider

```yaml
worldmind:
  starblaster:
    provider: docker
    worktrees-enabled: true      # Recommended for Docker
    image-registry: ghcr.io/dbbaskette
    image-prefix: starblaster
```

Docker provider runs centurions in local containers with filesystem access. Worktrees provide true isolation.

### Cloud Foundry Provider

```yaml
worldmind:
  starblaster:
    provider: cloudfoundry
    worktrees-enabled: false     # CF uses git branches instead
```

CF provider runs centurions as CF tasks. Each task clones the repository and pushes to its own branch. Worktrees are not used because tasks don't share filesystem.

## Monitoring Parallel Execution

### Key Metrics

| Metric | What to Watch |
|--------|---------------|
| `worldmind_wave_executions_total` | Total waves executed by strategy |
| `worldmind_wave_directive_count` | Directives per wave (should match `max-parallel`) |
| `worldmind_parallel_active_worktrees` | Should not exceed `max-parallel` |
| `worldmind_parallel_file_overlap_deferrals_total` | High values = too much overlap |
| `worldmind_parallel_merge_conflicts_total` | High unresolved = tune settings |

### Prometheus Queries

```promql
# Average directives per wave
avg(worldmind_wave_directive_count)

# Percentage of directives deferred due to overlap
sum(rate(worldmind_parallel_file_overlap_deferrals_total[5m])) /
sum(rate(worldmind_directive_dispatched_total[5m])) * 100

# Merge conflict resolution rate
sum(worldmind_parallel_merge_conflicts_total{resolved="true"}) /
sum(worldmind_parallel_merge_conflicts_total) * 100
```

### Grafana Dashboard Suggestions

1. **Wave Throughput** -- Waves per minute by strategy
2. **Parallel Utilization** -- Active worktrees vs max-parallel
3. **Conflict Rate** -- Deferrals and merge conflicts over time
4. **Mission Duration** -- Compare SEQUENTIAL vs PARALLEL execution time

## Troubleshooting Configuration Issues

### Symptoms and Solutions

| Symptom | Likely Cause | Solution |
|---------|--------------|----------|
| Directives execute sequentially despite PARALLEL | `worktrees-enabled: false` or file overlap | Enable worktrees or reduce overlap |
| OOM errors during parallel execution | `max-parallel` too high | Reduce `max-parallel` |
| Frequent merge conflicts | `wave-cooldown-seconds` too low | Increase cooldown |
| Directives stuck waiting | File overlap detection | Review plan for overlapping `targetFiles` |
| Slow wave startup | Container pull time | Pre-pull images or use local registry |

### Debug Logging

Enable debug logging for parallel execution:

```yaml
logging:
  level:
    com.worldmind.core.scheduler: DEBUG
    com.worldmind.core.nodes.ParallelDispatchNode: DEBUG
    com.worldmind.starblaster.WorktreeExecutionContext: DEBUG
    com.worldmind.starblaster.cf.GitWorkspaceManager: DEBUG
```

## Example Configurations

### Conservative (Stability-focused)

```yaml
worldmind:
  starblaster:
    worktrees-enabled: true
    max-parallel: 2
    wave-cooldown-seconds: 10
```

### Aggressive (Performance-focused)

```yaml
worldmind:
  starblaster:
    worktrees-enabled: true
    max-parallel: 8
    wave-cooldown-seconds: 2
```

### CI/CD Pipeline

```yaml
worldmind:
  starblaster:
    worktrees-enabled: true
    max-parallel: 4
    wave-cooldown-seconds: 5
    provider: docker
```

### Cloud Foundry Production

```yaml
worldmind:
  starblaster:
    worktrees-enabled: false
    max-parallel: 4
    wave-cooldown-seconds: 10
    provider: cloudfoundry
```

## Related Documentation

- [Architecture: Git Worktrees](../architecture/git-worktrees.md)
- [Troubleshooting: Merge Conflicts](../troubleshooting/merge-conflicts.md)
- [Deployment: Cloud Foundry](../deployment/cloudfoundry.md)
