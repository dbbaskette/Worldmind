# Phase 2: First Centurion — Design Document

> Centurion Forge in a Docker Stargate with Goose, executing directives from the planning pipeline.

## Goal

Extend Worldmind so that after planning, it can actually execute directives by spinning up a Docker container running Goose (Centurion Forge), which generates code in the user's project directory.

## Runnable Milestone

```bash
$ java -jar target/worldmind.jar mission "Create a hello.py that prints hello world"

MISSION WMND-2026-A1B2
DIRECTIVES:
  1. [FORGE] Create hello.py

Proceed? [Y/n] y

[STARGATE] Opening for Centurion Forge...
[CENTURION FORGE] Directive 001: Create hello.py
[STARGATE] Container stargate-forge-001 running
[CENTURION FORGE] Directive complete. 1 file created.
[STARGATE] Torn down.

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

### Stargate Provider Abstraction

Container orchestration is behind a `StargateProvider` interface so Docker can be swapped for Cloud Foundry or other platforms:

```java
public interface StargateProvider {
    String openStargate(StargateRequest request);
    int waitForCompletion(String stargateId, int timeoutSeconds);
    String captureOutput(String stargateId);
    void teardownStargate(String stargateId);
}
```

- `DockerStargateProvider` — Phase 2 implementation (Docker Java client)
- `CloudFoundryStargateProvider` — future production implementation
- Active provider selected via `worldmind.stargate.provider` config property

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

### 2. StargateProvider Interface + DockerStargateProvider

- `StargateRequest` record: directive, projectPath, envVars, resourceLimits
- `DockerStargateProvider`: creates container with bind mounts (project dir + instruction file), env vars, resource limits, extra host for `host.docker.internal`
- Wait for completion with timeout, capture stdout/stderr, teardown

### 3. StargateManager

- Spring `@Service` delegating to active `StargateProvider`
- File change detection: snapshot before/after, diff for created/modified files
- Timeout enforcement, resource tracking

### 4. InstructionBuilder

- Pure function: `Directive` + `ProjectContext` → markdown string
- No Spring dependencies

### 5. StargateBridge

- Thin orchestrator: build instruction → write temp file → snapshot → open stargate → wait → capture → diff → build records → teardown
- Returns updated `Directive` (status, filesAffected, elapsedMs) + `StargateInfo`

### 6. DispatchCenturionNode

- LangGraph4j node: reads next pending directive, calls bridge, returns state updates
- Conditional edge loops back for more directives or exits

### 7. Configuration

```yaml
worldmind:
  stargate:
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
| Unit | InstructionBuilder, StargateManager (mocked provider), StargateBridge (mocked manager), DispatchCenturionNode (mocked bridge) | Nothing |
| Docker integration | DockerStargateProviderTest — real container with simple command, verify file creation | Docker |
| End-to-end | ForgeIntegrationTest — full pipeline, real file generated | Docker + LM Studio (or API key) |

Integration tests tagged `@Tag("integration")`, skipped in normal builds.

---

## Files to Create/Modify

**New files:**
- `src/main/java/com/worldmind/stargate/StargateProvider.java`
- `src/main/java/com/worldmind/stargate/StargateRequest.java`
- `src/main/java/com/worldmind/stargate/DockerStargateProvider.java`
- `src/main/java/com/worldmind/stargate/StargateManager.java`
- `src/main/java/com/worldmind/stargate/StargateBridge.java`
- `src/main/java/com/worldmind/stargate/InstructionBuilder.java`
- `src/main/java/com/worldmind/stargate/StargateProperties.java`
- `src/main/java/com/worldmind/core/nodes/DispatchCenturionNode.java`
- `docker/centurion-forge/Dockerfile`
- `docker/centurion-forge/goose-config.yaml`
- `src/test/java/com/worldmind/stargate/InstructionBuilderTest.java`
- `src/test/java/com/worldmind/stargate/StargateManagerTest.java`
- `src/test/java/com/worldmind/stargate/StargateBridgeTest.java`
- `src/test/java/com/worldmind/core/nodes/DispatchCenturionNodeTest.java`
- `src/test/java/com/worldmind/stargate/DockerStargateProviderTest.java`
- `src/test/java/com/worldmind/integration/ForgeIntegrationTest.java`

**Modified files:**
- `src/main/java/com/worldmind/core/graph/WorldmindGraph.java` — add dispatch node + loop
- `src/main/java/com/worldmind/dispatch/cli/MissionCommand.java` — show stargate activity
- `src/main/java/com/worldmind/dispatch/cli/ConsoleOutput.java` — stargate display methods
- `src/main/resources/application.yml` — goose provider config, LM Studio URL
- `docker-compose.yml` — optionally add LM Studio service reference
