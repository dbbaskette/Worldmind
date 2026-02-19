# Troubleshooting: Merge Conflicts

This guide helps diagnose and resolve merge conflicts that can occur during parallel directive execution.

## Common Causes

### 1. Multiple Directives Targeting Same Files

**Symptom:** Merge fails with "CONFLICT" messages in logs.

**Cause:** Two or more directives modified the same file concurrently.

**Example:**
```
Directive A: targetFiles = ["src/UserService.java"]
Directive B: targetFiles = ["src/UserService.java", "src/UserController.java"]
```

**Prevention:**
- Review the generated plan before approval
- Use `SEQUENTIAL` execution strategy for interdependent work
- Ensure `targetFiles` in the plan are disjoint

### 2. LLM Not Respecting targetFiles Boundaries

**Symptom:** Directive modified files outside its declared `targetFiles`.

**Cause:** The centurion (Goose agent) may modify files not listed in `targetFiles` if it determines they're necessary for the task.

**Example:**
```
Plan says:       targetFiles = ["src/UserService.java"]
Actually touched: src/UserService.java, src/UserRepository.java, pom.xml
```

**Prevention:**
- Include all potentially affected files in `targetFiles`
- Use specific, well-scoped directives
- Review directive instructions for clarity

### 3. Race Conditions in Parallel Execution

**Symptom:** Intermittent merge failures that succeed on retry.

**Cause:** Two directives completed at nearly the same time, causing one to push to an outdated `main`.

**Diagnosis:** Check logs for retry messages:
```
WARN  Merge conflict detected, attempt 1/2, retrying after fetch...
INFO  Merge succeeded on retry
```

**Resolution:** The automatic retry mechanism handles most race conditions. If persistent:
- Increase `wave-cooldown-seconds` configuration
- Reduce `max-parallel` to lower concurrency

### 4. Semantic Conflicts

**Symptom:** Merge succeeds but code doesn't compile or tests fail.

**Cause:** Two directives made incompatible changes to different files (e.g., changed a method signature and its caller separately).

**Example:**
```
Directive A: Changed UserService.findById(Long id) to findById(UUID id)
Directive B: Added new call to UserService.findById(123L)
```

**Prevention:**
- Use `SEQUENTIAL` for related changes
- Include related files in the same directive
- Rely on the Seal of Approval quality gate to catch these issues

## Diagnosis Steps

### Step 1: Check Directive Status

```bash
./run.sh inspect <mission-id>
```

Look for failed directives and their error messages.

### Step 2: Review DirectiveScheduler Logs

Search for file overlap detection:
```bash
grep "file overlap" logs/worldmind.log
```

Expected output:
```
INFO  Directive wave computed: 3 eligible, 2 deferred due to file overlap
INFO  Deferred directive-xyz due to overlap with: [src/UserService.java]
```

### Step 3: Check Merge Retry Metrics

If using Prometheus/Grafana:
```promql
worldmind_parallel_merge_conflicts_total{resolved="false"}
worldmind_parallel_merge_retry_success_total
```

Or via actuator:
```bash
curl http://localhost:8080/actuator/prometheus | grep merge
```

### Step 4: Inspect Git State

```bash
# Check remote branches
git fetch origin
git branch -r | grep directive

# View conflict details (if merge was aborted)
cd /tmp/worldmind/mission-<id>/main
git status
git diff
```

### Step 5: Review Generated Plan

Check if the plan had overlapping `targetFiles`:

```bash
./run.sh inspect <mission-id> --show-plan
```

## Resolution Steps

### Automatic Resolution

Worldmind automatically handles most conflicts:

1. **Retry mechanism** -- Fetches latest `main` and retries rebase (up to 2 times)
2. **Wave deferral** -- File overlap detection defers conflicting directives

If automatic resolution fails, manual intervention is needed.

### Manual Resolution

#### Option 1: Retry the Mission

```bash
./run.sh retry <mission-id>
```

This retries failed directives. Useful when the conflict was due to a transient issue.

#### Option 2: Clean Up and Re-run

```bash
# Cancel the mission
./run.sh cancel <mission-id>

# Clean up any leftover branches
git fetch origin
git push origin --delete directive-<id>  # for each directive branch

# Re-submit with SEQUENTIAL strategy
./run.sh mission --strategy SEQUENTIAL "your request"
```

#### Option 3: Manual Branch Merge

If you need to preserve partial progress:

```bash
# Fetch all branches
git fetch origin

# Create a merge branch
git checkout main
git pull origin main
git checkout -b manual-merge

# Cherry-pick or merge each directive branch
git merge origin/directive-abc --no-edit
# Resolve any conflicts
git add .
git commit -m "Resolved conflicts from directive-abc"

git merge origin/directive-def --no-edit
# Resolve any conflicts
git add .
git commit -m "Resolved conflicts from directive-def"

# Push the merged result
git checkout main
git merge manual-merge
git push origin main

# Clean up
git branch -d manual-merge
git push origin --delete directive-abc directive-def
```

### Branch Cleanup Commands

Remove leftover directive branches:

```bash
# List directive branches
git fetch origin
git branch -r | grep directive

# Delete a specific branch
git push origin --delete directive-<id>

# Delete all directive branches (use with caution)
git branch -r | grep 'directive-' | sed 's/origin\///' | xargs -I {} git push origin --delete {}
```

## Prevention Strategies

### 1. Use SEQUENTIAL for New Projects

New projects often have high interdependency. Default to `SEQUENTIAL`:

```bash
./run.sh mission --strategy SEQUENTIAL "Initialize project structure with user authentication"
```

### 2. Review Generated Plans

Before approving a plan, check for:

- [ ] Overlapping `targetFiles` between directives
- [ ] Directives that might touch shared files (configs, schemas)
- [ ] High-risk directives that should be isolated

### 3. Monitor Conflict Metrics

Set up alerts for:

```promql
# Alert if merge conflicts exceed threshold
rate(worldmind_parallel_merge_conflicts_total{resolved="false"}[5m]) > 0.1

# Alert if many directives are being deferred
rate(worldmind_parallel_file_overlap_deferrals_total[5m]) > 1
```

### 4. Use Appropriate Parallelism

| Project State | Recommended Strategy |
|---------------|---------------------|
| New project | SEQUENTIAL |
| Mature codebase | PARALLEL |
| High-risk refactor | SEQUENTIAL |
| Independent features | PARALLEL |

### 5. Scope Directives Appropriately

Good directive scoping:
```
Directive 1: "Add user validation to UserService"
  targetFiles: [src/main/java/com/app/UserService.java]

Directive 2: "Add validation tests"
  targetFiles: [src/test/java/com/app/UserServiceTest.java]
```

Bad directive scoping:
```
Directive 1: "Add validation"  -- too vague
  targetFiles: []              -- no files specified

Directive 2: "Fix user issues" -- too broad
  targetFiles: [src/]          -- entire directory
```

## Related Documentation

- [Architecture: Git Worktrees](../architecture/git-worktrees.md)
- [Configuration: Parallel Execution](../configuration/parallel-execution.md)
