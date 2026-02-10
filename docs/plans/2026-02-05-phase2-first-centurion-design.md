# Phase 2: First Centurion — Design Document

> Centurion Forge in a Docker Starblaster with Goose, executing directives from the planning pipeline.

## Goal

Extend Worldmind so that after planning, it can actually execute directives by spinning up a Docker container running Goose (Centurion Forge), which generates code in the user's project directory.

## Runnable Milestone

```bash
$ java -jar target/worldmind.jar mission "Create a hello.py that prints hello world"

MISSION WMND-2026-A1B2
DIRECTIVES:
  1. [FORGE] Create hello.py

Proceed? [Y/n] y

[STARBLASTER] Opening for Centurion Forge...
[CENTURION FORGE] Directive 001: Create hello.py
[STARBLASTER] Container starblaster-forge-001 running
[CENTURION FORGE] Directive complete. 1 file created.
[STARBLASTER] Torn down.

Mission complete. 1 file created.

$ cat hello.py
print("Hello, World!")
```

---

## Design Decisions

### Goose Model Provider

- **Dev (default):** LM Studio on host, OpenAI-compatible API at `http://host.docker.internal:1234/v1`
- **Prod:** Anthropic API with `ANTHROPIC_API_KEY`
- Selected via `GOOSE_PROVIDER` env var (`openai` or `anthropic`)

### Starblaster Provider Abstraction

Container orchestration is behind a `StarblasterProvider` interface so Docker can be swapped for Cloud Foundry or other platforms:

```java
public interface StarblasterProvider {
    String openStarblaster(StarblasterRequest request);
    int waitForCompletion(String starblasterId, int timeoutSeconds);
    String captureOutput(String starblasterId);
    void teardownStarblaster(String starblasterId);
}
```

- `DockerStarblasterProvider` — Phase 2 implementation (Docker Java client)
- `CloudFoundryStarblasterProvider` — future production implementation
- Active provider selected via `worldmind.starblaster.provider` config property

### Instruction Format

Goose receives a markdown instruction file:

```markdown
# Directive: <id>
## Objective
<description>
## Project Context
<summary, language, framework, key files>
## Success Criteria
<criteria>
## Constraints
- Only modify files related to this directive
- Do not modify test files
- Commit nothing
```

### Graph Extension

Sequential dispatch loop added after approval:

```
plan_mission → [conditional] → await_approval → dispatch_centurion → [conditional]
                             → dispatch_centurion (FULL_AUTO)

dispatch_centurion → [conditional]
    → dispatch_centurion  (more pending)
    → END                 (all complete)
```

Parallel fan-out deferred to Phase 4.

---

## Components

### 1. Docker Image (`docker/centurion-forge/`)

- Extends `centurion-base` (Python 3.12, Node.js 20, Goose CLI, MCP filesystem server)
- Adds `goose-config.yaml` template reading env vars for provider/model/URL
- Entry point: `goose run --instructions /instructions/directive.md`

### 2. StarblasterProvider Interface + DockerStarblasterProvider

- `StarblasterRequest` record: directive, projectPath, envVars, resourceLimits
- `DockerStarblasterProvider`: creates container with bind mounts (project dir + instruction file), env vars, resource limits, extra host for `host.docker.internal`
- Wait for completion with timeout, capture stdout/stderr, teardown

### 3. StarblasterManager

- Spring `@Service` delegating to active `StarblasterProvider`
- File change detection: snapshot before/after, diff for created/modified files
- Timeout enforcement, resource tracking

### 4. InstructionBuilder

- Pure function: `Directive` + `ProjectContext` → markdown string
- No Spring dependencies

### 5. StarblasterBridge

- Thin orchestrator: build instruction → write temp file → snapshot → open starblaster → wait → capture → diff → build records → teardown
- Returns updated `Directive` (status, filesAffected, elapsedMs) + `StarblasterInfo`

### 6. DispatchCenturionNode

- LangGraph4j node: reads next pending directive, calls bridge, returns state updates
- Conditional edge loops back for more directives or exits

### 7. Configuration

```yaml
worldmind:
  starblaster:
    provider: docker           # docker | cloudfoundry
    max-parallel: 10
    timeout-seconds: 300
    memory-limit: 4g
    cpu-count: 2
  goose:
    provider: ${GOOSE_PROVIDER:openai}
    model: ${GOOSE_MODEL:qwen2.5-coder-32b}
    lm-studio-url: ${LM_STUDIO_URL:http://host.docker.internal:1234/v1}
```

---

## Testing Strategy

| Level | What | Requires |
|-------|------|----------|
| Unit | InstructionBuilder, StarblasterManager (mocked provider), StarblasterBridge (mocked manager), DispatchCenturionNode (mocked bridge) | Nothing |
| Docker integration | DockerStarblasterProviderTest — real container with simple command, verify file creation | Docker |
| End-to-end | ForgeIntegrationTest — full pipeline, real file generated | Docker + LM Studio (or API key) |

Integration tests tagged `@Tag("integration")`, skipped in normal builds.

---

## Files to Create/Modify

**New files:**
- `src/main/java/com/worldmind/starblaster/StarblasterProvider.java`
- `src/main/java/com/worldmind/starblaster/StarblasterRequest.java`
- `src/main/java/com/worldmind/starblaster/DockerStarblasterProvider.java`
- `src/main/java/com/worldmind/starblaster/StarblasterManager.java`
- `src/main/java/com/worldmind/starblaster/StarblasterBridge.java`
- `src/main/java/com/worldmind/starblaster/InstructionBuilder.java`
- `src/main/java/com/worldmind/starblaster/StarblasterProperties.java`
- `src/main/java/com/worldmind/core/nodes/DispatchCenturionNode.java`
- `docker/centurion-forge/Dockerfile`
- `docker/centurion-forge/goose-config.yaml`
- `src/test/java/com/worldmind/starblaster/InstructionBuilderTest.java`
- `src/test/java/com/worldmind/starblaster/StarblasterManagerTest.java`
- `src/test/java/com/worldmind/starblaster/StarblasterBridgeTest.java`
- `src/test/java/com/worldmind/core/nodes/DispatchCenturionNodeTest.java`
- `src/test/java/com/worldmind/starblaster/DockerStarblasterProviderTest.java`
- `src/test/java/com/worldmind/integration/ForgeIntegrationTest.java`

**Modified files:**
- `src/main/java/com/worldmind/core/graph/WorldmindGraph.java` — add dispatch node + loop
- `src/main/java/com/worldmind/dispatch/cli/MissionCommand.java` — show starblaster activity
- `src/main/java/com/worldmind/dispatch/cli/ConsoleOutput.java` — starblaster display methods
- `src/main/resources/application.yml` — goose provider config, LM Studio URL
- `docker-compose.yml` — optionally add LM Studio service reference
