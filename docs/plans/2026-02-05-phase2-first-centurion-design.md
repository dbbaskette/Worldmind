# Phase 2: First Agent — Design Document

> Agent Coder in a Docker Sandbox with Goose, executing tasks from the planning pipeline.

## Goal

Extend Worldmind so that after planning, it can actually execute tasks by spinning up a Docker container running Goose (Agent Coder), which generates code in the user's project directory.

## Runnable Milestone

```bash
$ java -jar target/worldmind.jar mission "Create a hello.py that prints hello world"

MISSION WMND-2026-A1B2
TASKS:
  1. [CODER] Create hello.py

Proceed? [Y/n] y

[SANDBOX] Opening for Agent Coder...
[AGENT CODER] Task 001: Create hello.py
[SANDBOX] Container sandbox-coder-001 running
[AGENT CODER] Task complete. 1 file created.
[SANDBOX] Torn down.

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

### Sandbox Provider Abstraction

Container orchestration is behind a `SandboxProvider` interface so Docker can be swapped for Cloud Foundry or other platforms:

```java
public interface SandboxProvider {
    String openSandbox(AgentRequest request);
    int waitForCompletion(String sandboxId, int timeoutSeconds);
    String captureOutput(String sandboxId);
    void teardownSandbox(String sandboxId);
}
```

- `DockerSandboxProvider` — Phase 2 implementation (Docker Java client)
- `CloudFoundrySandboxProvider` — future production implementation
- Active provider selected via `worldmind.sandbox.provider` config property

### Instruction Format

Goose receives a markdown instruction file:

```markdown
# Task: <id>
## Objective
<description>
## Project Context
<summary, language, framework, key files>
## Success Criteria
<criteria>
## Constraints
- Only modify files related to this task
- Do not modify test files
- Commit nothing
```

### Graph Extension

Sequential dispatch loop added after approval:

```
plan_mission → [conditional] → await_approval → dispatch_agent → [conditional]
                             → dispatch_agent (FULL_AUTO)

dispatch_agent → [conditional]
    → dispatch_agent  (more pending)
    → END                 (all complete)
```

Parallel fan-out deferred to Phase 4.

---

## Components

### 1. Docker Image (`docker/agent-coder/`)

- Extends `agent-base` (Python 3.12, Node.js 20, Goose CLI, MCP filesystem server)
- Adds `goose-config.yaml` template reading env vars for provider/model/URL
- Entry point: `goose run --instructions /instructions/task.md`

### 2. SandboxProvider Interface + DockerSandboxProvider

- `AgentRequest` record: task, projectPath, envVars, resourceLimits
- `DockerSandboxProvider`: creates container with bind mounts (project dir + instruction file), env vars, resource limits, extra host for `host.docker.internal`
- Wait for completion with timeout, capture stdout/stderr, teardown

### 3. SandboxManager

- Spring `@Service` delegating to active `SandboxProvider`
- File change detection: snapshot before/after, diff for created/modified files
- Timeout enforcement, resource tracking

### 4. InstructionBuilder

- Pure function: `Task` + `ProjectContext` → markdown string
- No Spring dependencies

### 5. AgentDispatcher

- Thin orchestrator: build instruction → write temp file → snapshot → open sandbox → wait → capture → diff → build records → teardown
- Returns updated `Task` (status, filesAffected, elapsedMs) + `SandboxInfo`

### 6. DispatchAgentNode

- LangGraph4j node: reads next pending task, calls bridge, returns state updates
- Conditional edge loops back for more tasks or exits

### 7. Configuration

```yaml
worldmind:
  sandbox:
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
| Unit | InstructionBuilder, SandboxManager (mocked provider), AgentDispatcher (mocked manager), DispatchAgentNode (mocked bridge) | Nothing |
| Docker integration | DockerSandboxProviderTest — real container with simple command, verify file creation | Docker |
| End-to-end | CoderIntegrationTest — full pipeline, real file generated | Docker + LM Studio (or API key) |

Integration tests tagged `@Tag("integration")`, skipped in normal builds.

---

## Files to Create/Modify

**New files:**
- `src/main/java/com/worldmind/sandbox/SandboxProvider.java`
- `src/main/java/com/worldmind/sandbox/AgentRequest.java`
- `src/main/java/com/worldmind/sandbox/DockerSandboxProvider.java`
- `src/main/java/com/worldmind/sandbox/SandboxManager.java`
- `src/main/java/com/worldmind/sandbox/AgentDispatcher.java`
- `src/main/java/com/worldmind/sandbox/InstructionBuilder.java`
- `src/main/java/com/worldmind/sandbox/SandboxProperties.java`
- `src/main/java/com/worldmind/core/nodes/DispatchAgentNode.java`
- `docker/agent-coder/Dockerfile`
- `docker/agent-coder/goose-config.yaml`
- `src/test/java/com/worldmind/sandbox/InstructionBuilderTest.java`
- `src/test/java/com/worldmind/sandbox/SandboxManagerTest.java`
- `src/test/java/com/worldmind/sandbox/AgentDispatcherTest.java`
- `src/test/java/com/worldmind/core/nodes/DispatchAgentNodeTest.java`
- `src/test/java/com/worldmind/sandbox/DockerSandboxProviderTest.java`
- `src/test/java/com/worldmind/integration/CoderIntegrationTest.java`

**Modified files:**
- `src/main/java/com/worldmind/core/graph/WorldmindGraph.java` — add dispatch node + loop
- `src/main/java/com/worldmind/dispatch/cli/MissionCommand.java` — show sandbox activity
- `src/main/java/com/worldmind/dispatch/cli/ConsoleOutput.java` — sandbox display methods
- `src/main/resources/application.yml` — goose provider config, LM Studio URL
- `docker-compose.yml` — optionally add LM Studio service reference
