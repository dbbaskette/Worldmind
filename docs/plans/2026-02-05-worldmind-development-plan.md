# Worldmind Development Plan (Java)

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build an agentic code assistant that accepts natural language requests and autonomously plans, implements, tests, and reviews code using a LangGraph4j control plane (on Spring Boot) orchestrating Goose worker agents in Docker containers.

**Architecture:** A hybrid system where LangGraph4j (Java) manages state, planning, routing, and persistence (the "nervous system") while Goose instances running in Docker containers (Sandboxes) perform actual code generation and file manipulation via MCP tools (the "hands"). Spring AI provides Anthropic Claude integration with structured output. Spring Boot provides REST API, DI, and observability.

**Tech Stack:** Java 21, Maven, Spring Boot 3.4, Spring AI 1.1, LangGraph4j 1.8, Goose CLI (headless), picocli, PostgreSQL, Docker, MCP servers, Anthropic Claude, Micrometer.

**Reference:** See `Spec.md` for the full architecture specification, nomenclature, and system flow.

---

## Phase Overview & Runnable Milestones

| Phase | Name | What You Can Run | Duration |
|-------|------|-----------------|----------|
| **0** | Project Scaffold | `./mvnw worldmind:help` or `java -jar worldmind.jar --help` prints CLI with all command stubs | 2-3 days |
| **1** | Brain Without Hands | `worldmind mission "add a hello endpoint"` classifies the request, gathers context, produces a mission plan, and displays it — no code generation yet | 1.5 weeks |
| **2** | First Agent | `worldmind mission "create a hello.py"` spins up a Docker container with Goose, generates the file, and writes it to your project | 2 weeks |
| **3** | The Loop | After code generation, tests run automatically; if they fail, Coder gets feedback and retries; Reviewer reviews and grants/denies the QualityGate | 2 weeks |
| **4** | Fan-Out | Multi-task missions execute in parallel — 3 Coder containers spinning up simultaneously for independent files | 1.5 weeks |
| **5** | Full Dispatch | REST API with SSE streaming, `--watch` mode, timeline inspection, mission history — the full operator experience | 1.5 weeks |
| **6** | Hardening & Observability | Security (MCP auth, command allowlists, path restrictions), Micrometer metrics, structured event logging, error recovery | 2 weeks |

**Total estimated duration: ~11-12 weeks**

---

## Phase 0: Project Scaffold

### Runnable Milestone
```bash
$ java -jar target/worldmind.jar --help
# Shows all command stubs with descriptions

$ java -jar target/worldmind.jar mission "test"
# Prints: "✶ WORLDMIND v1.0 — Mission submission coming in Phase 1..."

$ java -jar target/worldmind.jar health
# Prints: "Worldmind Core: not running | PostgreSQL: not connected | Docker: checking..."
```

You can build the JAR, run the CLI, see the command structure, and verify the project skeleton is correct before writing any business logic.

---

### Task 0.1: Initialize Maven Project with Spring Boot

**Files:**
- Create: `pom.xml`
- Create: `src/main/java/com/worldmind/WorldmindApplication.java`
- Create: `src/main/resources/application.yml`
- Create: `src/main/resources/application-dev.yml`
- Create: `.env.example`
- Create: `.gitignore`
- Create: `.mvn/wrapper/` (Maven wrapper)

**pom.xml** key structure:
- Parent: `spring-boot-starter-parent:3.4.x`
- Java 21 compiler settings
- Spring AI BOM (`spring-ai-bom:1.1.x`)
- LangGraph4j BOM (`langgraph4j-bom:1.8.x`)
- Dependencies:
  - `spring-boot-starter-web` (REST API)
  - `spring-boot-starter-actuator` (health, metrics)
  - `spring-boot-starter-data-jpa` (persistence utilities)
  - `spring-ai-starter-model-anthropic` (Claude integration)
  - `langgraph4j-core` (graph engine)
  - `langgraph4j-postgres-saver` (checkpointing)
  - `info.picocli:picocli-spring-boot-starter` (CLI)
  - `com.github.docker-java:docker-java-core` + `docker-java-transport-httpclient5` (Docker client)
  - `org.postgresql:postgresql` (JDBC driver)
- Test dependencies:
  - `spring-boot-starter-test`
  - `org.testcontainers:postgresql` (for integration tests)

**application.yml:**
```yaml
spring:
  application:
    name: worldmind
  ai:
    anthropic:
      api-key: ${ANTHROPIC_API_KEY}
      chat:
        options:
          model: claude-sonnet-4-5-20250929
          temperature: 0.0
          max-tokens: 8192
  datasource:
    url: ${DATABASE_URL:jdbc:postgresql://localhost:5432/worldmind}
    username: ${DB_USER:worldmind}
    password: ${DB_PASSWORD:worldmind}

worldmind:
  goose:
    model: ${GOOSE_MODEL:claude-sonnet-4-5-20250929}
  sandbox:
    max-parallel: 10
    timeout-seconds: 300
    memory-limit: 4g
    cpu-count: 2
```

**WorldmindApplication.java:** Standard Spring Boot main class with `@SpringBootApplication`.

After creating all files: `./mvnw clean package -DskipTests` should produce a runnable JAR.

**Commit:** `feat: initialize Maven project with Spring Boot, Spring AI, LangGraph4j`

---

### Task 0.2: Define Xandarian Archive (Java Records + Enums)

**Files:**
- Create: `src/main/java/com/worldmind/core/model/MissionStatus.java`
- Create: `src/main/java/com/worldmind/core/model/TaskStatus.java`
- Create: `src/main/java/com/worldmind/core/model/ExecutionStrategy.java`
- Create: `src/main/java/com/worldmind/core/model/InteractionMode.java`
- Create: `src/main/java/com/worldmind/core/model/FailureStrategy.java`
- Create: `src/main/java/com/worldmind/core/model/Classification.java`
- Create: `src/main/java/com/worldmind/core/model/ProjectContext.java`
- Create: `src/main/java/com/worldmind/core/model/Task.java`
- Create: `src/main/java/com/worldmind/core/model/FileRecord.java`
- Create: `src/main/java/com/worldmind/core/model/TestResult.java`
- Create: `src/main/java/com/worldmind/core/model/ReviewFeedback.java`
- Create: `src/main/java/com/worldmind/core/model/SandboxInfo.java`
- Create: `src/main/java/com/worldmind/core/model/MissionMetrics.java`
- Create: `src/main/java/com/worldmind/core/state/WorldmindState.java`
- Test: `src/test/java/com/worldmind/core/model/ModelTest.java`

**Key design:**
- All domain objects are Java records (immutable, concise)
- Enums for all status/strategy/mode fields
- `WorldmindState extends AgentState` for LangGraph4j integration
- State uses `Channel` reducers (appender for lists, overwrite for scalars)

Example records:
```java
public record Classification(
    String category,
    int complexity,
    List<String> affectedComponents,
    String planningStrategy
) {}

public record Task(
    String id,
    String agent,
    String description,
    String inputContext,
    String successCriteria,
    List<String> dependencies,
    TaskStatus status,
    int iteration,
    int maxIterations,
    FailureStrategy onFailure,
    List<FileRecord> filesAffected,
    Long elapsedMs
) {}
```

**Tests:** Verify record construction, enum values, state channel configuration.

**Commit:** `feat: Xandarian Archive state schema — Java records and LangGraph4j AgentState`

---

### Task 0.3: Build Dispatch CLI Skeleton (picocli)

**Files:**
- Create: `src/main/java/com/worldmind/dispatch/cli/WorldmindCommand.java`
- Create: `src/main/java/com/worldmind/dispatch/cli/MissionCommand.java`
- Create: `src/main/java/com/worldmind/dispatch/cli/StatusCommand.java`
- Create: `src/main/java/com/worldmind/dispatch/cli/HealthCommand.java`
- Create: `src/main/java/com/worldmind/dispatch/cli/HistoryCommand.java`
- Create: `src/main/java/com/worldmind/dispatch/cli/ConsoleOutput.java`
- Test: `src/test/java/com/worldmind/dispatch/cli/CliTest.java`

**Key design:**
- `WorldmindCommand` is the top-level `@Command` with subcommands
- Each subcommand is a separate class implementing `Runnable`
- `ConsoleOutput` handles ANSI-colored terminal output (picocli has built-in support)
- picocli-spring-boot-starter integrates with Spring DI

**Commands (all stubs for now):**
- `worldmind mission "<request>"` — prints banner + "coming in Phase 1"
- `worldmind status <id>` — stub
- `worldmind health` — prints system health placeholders
- `worldmind history` — prints "no missions yet"
- `worldmind cancel <id>` — stub
- `worldmind inspect <id> <did>` — stub

**Commit:** `feat: Dispatch CLI skeleton with picocli — all command stubs`

---

### Task 0.4: Docker Compose for Development Infrastructure

**Files:**
- Create: `docker-compose.yml`
- Create: `docker/agent-base/Dockerfile`

**docker-compose.yml:** PostgreSQL 16 with healthcheck. Same as before — the database layer is language-agnostic.

**Agent base Dockerfile:** Placeholder with Python 3.12 base + Goose CLI + Node.js (for MCP servers). The Goose worker containers are language-agnostic — they don't run Java.

**Commit:** `feat: Docker Compose with PostgreSQL and base Agent Dockerfile`

---

## Phase 1: Brain Without Hands

### Runnable Milestone
```bash
$ docker compose up -d postgres
$ java -jar target/worldmind.jar mission "Add a REST endpoint for user profiles with validation"

✶ WORLDMIND v1.0
──────────────────────────────────
[WORLDMIND] Classifying request...
[WORLDMIND] Category: feature | Complexity: 3 | Components: api, service, model
[WORLDMIND] Uploading project context... 23 files indexed.

MISSION WMND-2026-0001
Objective: Add REST endpoint for user profiles with validation
Strategy: parallel

TASKS:
  1. [CODER]    Implement UserProfile model and schema
  2. [CODER]    Implement UserProfileController
  3. [CODER]    Implement UserProfileService
  4. [TESTER] Write unit + integration tests
  5. [REVIEWER]    Final code review

Proceed? [Y/n/edit] n
Mission cancelled.
```

The system classifies the request, scans the project, generates a mission plan with tasks, and asks for approval. No code is actually generated yet, but the entire planning pipeline is live and persisted to PostgreSQL.

---

### Task 1.1: LangGraph4j Graph Skeleton

**Files:**
- Create: `src/main/java/com/worldmind/core/graph/WorldmindGraph.java`
- Create: `src/main/java/com/worldmind/core/graph/GraphConfig.java`
- Create: `src/main/java/com/worldmind/core/nodes/ClassifyRequestNode.java`
- Create: `src/main/java/com/worldmind/core/nodes/UploadContextNode.java`
- Create: `src/main/java/com/worldmind/core/nodes/PlanMissionNode.java`
- Test: `src/test/java/com/worldmind/core/graph/GraphTest.java`

**Key design:**
- `WorldmindGraph` builds and compiles the `StateGraph<WorldmindState>`
- `GraphConfig` is a Spring `@Configuration` that creates the graph as a `@Bean`
- Each node class implements `NodeAction<WorldmindState>` returning `Map<String, Object>`
- Phase 1: classify → upload → plan → await_approval (stub) or END

```java
var graph = new StateGraph<>(WorldmindState.SCHEMA, WorldmindState::new)
    .addNode("classify_request", node_async(classifyNode::apply))
    .addNode("upload_context", node_async(uploadNode::apply))
    .addNode("plan_mission", node_async(planNode::apply))
    .addNode("await_approval", node_async(state -> Map.of("status", "awaiting_approval")))
    .addEdge(START, "classify_request")
    .addEdge("classify_request", "upload_context")
    .addEdge("upload_context", "plan_mission")
    .addConditionalEdges("plan_mission", edge_async(this::routeAfterPlan))
    .addEdge("await_approval", END);
```

**Tests:** Graph compiles, has expected nodes, conditional routing works.

**Commit:** `feat: LangGraph4j StateGraph skeleton with classify/upload/plan nodes`

---

### Task 1.2: Classify Request Node (Spring AI Structured Output)

**Files:**
- Modify: `src/main/java/com/worldmind/core/nodes/ClassifyRequestNode.java`
- Create: `src/main/java/com/worldmind/core/llm/LlmService.java`
- Test: `src/test/java/com/worldmind/core/nodes/ClassifyRequestNodeTest.java`

**Key design:**
- `LlmService` wraps Spring AI's `ChatClient` as a Spring `@Service`
- Uses `BeanOutputConverter<Classification>` for structured output
- System prompt instructs the LLM to classify the request into category, complexity, components, strategy
- Returns `Map.of("classification", classification, "status", "uploading")`

```java
@Service
public class LlmService {
    private final ChatClient chatClient;

    public LlmService(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    public <T> T structuredCall(String systemPrompt, String userPrompt, Class<T> outputType) {
        var converter = new BeanOutputConverter<>(outputType);
        String response = chatClient.prompt()
            .system(systemPrompt)
            .user(userPrompt + "\n\n" + converter.getFormat())
            .call()
            .content();
        return converter.convert(response);
    }
}
```

**Tests:** Mock `LlmService`, verify classification is produced with valid fields.

**Commit:** `feat: classify_request node with Spring AI structured output`

---

### Task 1.3: Upload Context Node (Project Scanner)

**Files:**
- Modify: `src/main/java/com/worldmind/core/nodes/UploadContextNode.java`
- Create: `src/main/java/com/worldmind/core/scanner/ProjectScanner.java`
- Test: `src/test/java/com/worldmind/core/scanner/ProjectScannerTest.java`

**Key design:**
- `ProjectScanner` is a Spring `@Service` that walks the project directory
- Uses `java.nio.file.Files.walk()` to build file tree
- Detects dependency manifests (pom.xml, package.json, requirements.txt, etc.)
- Respects ignore patterns (.git, node_modules, target/, build/, etc.)
- Returns `ProjectContext` record

**Tests:** Use `@TempDir` for isolated filesystem tests.

**Commit:** `feat: upload_context node with project structure scanning`

---

### Task 1.4: Plan Mission Node (Spring AI Structured Output)

**Files:**
- Modify: `src/main/java/com/worldmind/core/nodes/PlanMissionNode.java`
- Create: `src/main/java/com/worldmind/core/model/MissionPlan.java` (record for LLM output)
- Test: `src/test/java/com/worldmind/core/nodes/PlanMissionNodeTest.java`

**Key design:**
- Uses `LlmService.structuredCall()` with `MissionPlan` output type
- System prompt describes Agent roster, planning rules, task ordering
- Converts `MissionPlan` (LLM output) into `List<Task>` records
- Returns tasks + execution_strategy + status

**Commit:** `feat: plan_mission node with LLM-powered task generation`

---

### Task 1.5: PostgreSQL Checkpointer Integration

**Files:**
- Create: `src/main/java/com/worldmind/core/persistence/CheckpointerConfig.java`
- Modify: `src/main/java/com/worldmind/core/graph/GraphConfig.java`
- Test: `src/test/java/com/worldmind/core/persistence/CheckpointerTest.java`

**Key design:**
- `CheckpointerConfig` is a Spring `@Configuration` that creates `PostgresSaver` bean
- Uses `PostgresSaver.builder()` with Spring datasource properties
- `createTables(true)` on first startup
- Graph compilation uses the checkpointer: `graph.compile(checkpointer)`

**Tests:** Use Testcontainers PostgreSQL for integration tests.

**Commit:** `feat: PostgreSQL checkpointer integration for state persistence`

---

### Task 1.6: Wire CLI to Graph — End-to-End Planning

**Files:**
- Create: `src/main/java/com/worldmind/core/engine/MissionEngine.java`
- Modify: `src/main/java/com/worldmind/dispatch/cli/MissionCommand.java`
- Modify: `src/main/java/com/worldmind/dispatch/cli/ConsoleOutput.java`
- Test: `src/test/java/com/worldmind/core/engine/MissionEngineTest.java`

**Key design:**
- `MissionEngine` is a Spring `@Service` that:
  - Generates a mission ID (`WMND-YYYY-XXXX`)
  - Constructs initial state
  - Invokes the compiled graph
  - Returns final state
- `MissionCommand` calls `MissionEngine.runMission()` and uses `ConsoleOutput` to display:
  - Classification results
  - Project context summary
  - Task table
  - Approval prompt (interactive mode)
- `ConsoleOutput` uses picocli ANSI colors for the Worldmind-themed display

**Tests:** Mock engine for CLI tests. Integration test with real graph (mock LLM).

**Commit:** `feat: end-to-end planning pipeline — CLI to LangGraph4j to plan display`

---

## Phase 2: First Agent

### Runnable Milestone
```bash
$ java -jar target/worldmind.jar mission "Create a hello.py that prints hello world"

✶ WORLDMIND v1.0
──────────────────────────────────
MISSION WMND-2026-A1B2
TASKS:
  1. [CODER] Create hello.py

Proceed? [Y/n] y

[SANDBOX] Opening for Agent Coder...
[AGENT CODER] Task 001: Create hello.py
[SANDBOX] Container sandbox-coder-001 running
[AGENT CODER] Task complete. 1 file created.
[SANDBOX] Torn down.

✓ Mission complete. 1 file created.

$ cat hello.py
print("Hello, World!")
```

---

### Task 2.1: Build Agent Coder Docker Image

**Files:**
- Modify: `docker/agent-base/Dockerfile` — install Goose CLI, Node.js, MCP filesystem server
- Create: `docker/agent-coder/Dockerfile` — extends base with Coder-specific config
- Create: `docker/agent-coder/goose-config.yaml`

**Commit:** `feat: Agent Coder Docker image with Goose CLI`

---

### Task 2.2: Sandbox Manager (Docker Java Client)

**Files:**
- Create: `src/main/java/com/worldmind/sandbox/SandboxManager.java`
- Create: `src/main/java/com/worldmind/sandbox/AgentDispatcher.java`
- Create: `src/main/java/com/worldmind/sandbox/InstructionBuilder.java`
- Test: `src/test/java/com/worldmind/sandbox/SandboxManagerTest.java`

**Key design:**
- `SandboxManager` is a Spring `@Service` using Docker Java client
- `InstructionBuilder` constructs Goose instruction markdown from a `Task` record
- `AgentDispatcher` orchestrates: build instruction → open sandbox → wait → capture → teardown
- File change detection: snapshot before/after to find created/modified files

**Commit:** `feat: Sandbox Manager and Bridge for Docker-based Goose execution`

---

### Task 2.3: Dispatch Agent Node

**Files:**
- Create: `src/main/java/com/worldmind/core/nodes/DispatchAgentNode.java`
- Modify: `src/main/java/com/worldmind/core/graph/WorldmindGraph.java`
- Test: `src/test/java/com/worldmind/core/nodes/DispatchAgentNodeTest.java`

**Key design:**
- Node finds next pending task, calls `AgentDispatcher.executeTask()`
- Updates task status and files_created in state
- Graph wired: execute_tasks → dispatch_agent → conditional (more pending? loop : END)

**Commit:** `feat: dispatch_agent node with Sandbox Bridge integration`

---

### Task 2.4: End-to-End Single Agent Test

**Files:**
- Test: `src/test/java/com/worldmind/integration/CoderIntegrationTest.java`

Full pipeline integration test (requires Docker + API key): mission → classify → plan → dispatch → file created.

**Commit:** `test: end-to-end Agent Coder integration test`

---

## Phase 3: The Loop

### Runnable Milestone
Build-test-fix cycle working: Reviewer denies quality_gate → feedback routes to Coder → Coder fixes → Tester re-runs tests → Reviewer approves.

---

### Task 3.1: Agent Tester (Tester) Image & Config
Build Docker image, instruction template for test generation/execution, parse test output into `TestResult`.

### Task 3.2: Agent Reviewer (Reviewer) Image & Config
Build Docker image, instruction template for code review, parse output into `ReviewFeedback`.

### Task 3.3: Evaluate QualityGate Node
Quality gate logic: check test results + review feedback → grant/deny quality_gate → apply failure strategy (retry/replan/escalate/skip).

### Task 3.4: Wire Iterative Cycle into Graph
dispatch → evaluate_quality_gate → conditional routing back to dispatch for retries. Cyclic graph edges.

### Task 3.5: Converge Results Node
Aggregate all task outcomes, merge file changes, compute metrics, determine mission success/failure.

### Task 3.6: Force::Spark (Shell MCP) Configuration
MCP shell server with command allowlists per agent type.

### Task 3.7: Force::Chronicle (Git MCP) Configuration
MCP git server. Auto-commit to `worldmind/<mission-id>` branch at convergence.

---

## Phase 4: Fan-Out ✅

### Runnable Milestone
Multiple Goose containers running simultaneously for independent tasks. Wave-based parallel execution with dependency-aware scheduling visible in CLI output.

**Status: COMPLETE** — 295 unit tests pass, JAR builds.

---

### Task 4.1: Wave Execution State Channels ✅
Added 4 new state channels to `WorldmindState`: `completedTaskIds` (appender), `waveTaskIds` (base), `waveCount` (base), `waveDispatchResults` (base). Created `WaveDispatchResult` record for per-task dispatch results.

### Task 4.2: TaskScheduler Service ✅
Dependency-aware `TaskScheduler.computeNextWave()` — filters tasks by completion status and dependency satisfaction, respects `ExecutionStrategy` (SEQUENTIAL caps wave to 1), bounded by `maxParallel`. 10 unit tests covering diamond deps, linear chains, and edge cases.

### Task 4.3: ScheduleWaveNode ✅
Graph node that calls `TaskScheduler`, writes `waveTaskIds` and increments `waveCount`. Empty wave signals all tasks complete (routes to converge).

### Task 4.4: ParallelDispatchNode ✅
Dispatches all tasks in a wave concurrently via `CompletableFuture.supplyAsync()` on `Executors.newVirtualThreadPerTaskExecutor()`. Bounded by `Semaphore(maxParallel)`. Applies retry context augmentation. Collects `WaveDispatchResult`, `SandboxInfo`, and errors.

### Task 4.5: EvaluateWaveNode ✅
Batch quality_gate evaluation for all tasks in a wave. Non-CODER tasks auto-pass. CODER tasks run TESTER + REVIEWER through `QualityGateEvaluationService`. Handles RETRY (re-enter next wave), SKIP (mark complete), ESCALATE (mission failed). 9 unit tests.

### Task 4.6: Rewire WorldmindGraph ✅
Replaced `dispatch_agent → evaluate_quality_gate` index-based loop with wave-based loop: `schedule_wave → parallel_dispatch → evaluate_wave → schedule_wave`. New routing: `routeAfterSchedule` (empty wave → converge), `routeAfterWaveEval` (all done or failed → converge, else → next wave). Updated GraphTest, CheckpointerTest, MissionEngineTest.

### Task 4.7: Agent Researcher Docker Image ✅
Created `docker/agent-researcher/Dockerfile` (FROM agent-base). Added `InstructionBuilder.buildResearcherInstruction()` with read-only constraints. Works through existing AgentDispatcher infrastructure.

### Task 4.8: CLI Output & Metrics ✅
Added `wavesExecuted` and `aggregateDurationMs` to `MissionMetrics`. Added `wave()`, `parallelProgress()`, `waveComplete()` to `ConsoleOutput`. Updated `ConvergeResultsNode` to compute wave metrics. Updated `MissionCommand` for wave-aware display.

---

## Phase 5: Full Dispatch

### Runnable Milestone
REST API live at `http://localhost:8000`. SSE streaming works. CLI `--watch`, `inspect`, `history`, `timeline` all functional.

---

### Task 5.1: Dispatch REST API (Spring MVC Controllers)
Spring `@RestController` implementing all endpoints from Spec Section 7.2. Mission submission as async `@Async` task.

### Task 5.2: SSE Event Streaming
`SseEmitter` or WebFlux `Flux` for real-time mission events. Event bus collects events from graph node transitions.

### Task 5.3: CLI Watch Mode
`worldmind status --watch <id>` connects to SSE endpoint and renders live progress with picocli ANSI output.

### Task 5.4: CLI History, Timeline, Inspect
Query PostgreSQL checkpoints for mission history and timeline. Retrieve task logs for inspect.

### Task 5.5: `worldmind serve` Command
Starts embedded Spring Boot web server. `worldmind serve --port 8000`.

---

## Phase 6: Hardening & Observability

### Runnable Milestone
Full production health checks, MCP auth, command allowlists, Prometheus-compatible metrics.

---

### Task 6.1: MCP Authentication & Token Scoping
Mission-scoped JWT tokens for MCP servers. Token includes mission ID, task ID, agent type, expiration.

### Task 6.2: Command Allowlisting for Force::Spark
Per-agent command allowlists configured in `application.yml`.

### Task 6.3: Path Restrictions for Force::Terrain
Per-agent filesystem path restrictions. Tester: test dirs only. Reviewer: read-only.

### Task 6.4: Structured Logging
SLF4J/Logback with structured JSON output using Worldmind vocabulary. MDC for mission/task context.

### Task 6.5: Micrometer Metrics
Spring Boot Actuator + Micrometer counters/histograms for all metrics in Spec Section 9.3. `/actuator/prometheus` endpoint.

### Task 6.6: Agent Refactorer (Refactorer) Image
Last agent type. Runs tests before/after to verify behavioral equivalence.

### Task 6.7: Oscillation Detection
Monitor iterative cycles for alternating patterns. Escalate early if detected.

### Task 6.8: Health Command (Real Implementation)
Check PostgreSQL, Docker, MCP servers, sandbox pool status. Display with ANSI colors.

---

## Project Structure at Completion

```
worldmind/
├── pom.xml
├── docker-compose.yml
├── .env.example
├── .gitignore
├── .mvn/wrapper/
├── Spec.md
├── docs/plans/
│   └── 2026-02-05-worldmind-development-plan.md
├── docker/
│   ├── agent-base/Dockerfile
│   ├── agent-coder/
│   ├── agent-tester/
│   ├── agent-reviewer/
│   ├── agent-researcher/
│   ├── agent-refactorer/
│   └── mcp-servers/
│       ├── spark/
│       └── chronicle/
├── src/
│   ├── main/
│   │   ├── java/com/worldmind/
│   │   │   ├── WorldmindApplication.java
│   │   │   ├── core/
│   │   │   │   ├── model/          # Java records (Classification, Task, etc.)
│   │   │   │   ├── state/          # WorldmindState (AgentState subclass)
│   │   │   │   ├── graph/          # WorldmindGraph, GraphConfig
│   │   │   │   ├── nodes/          # ClassifyRequest, UploadContext, PlanMission, etc.
│   │   │   │   ├── engine/         # MissionEngine
│   │   │   │   ├── llm/            # LlmService (Spring AI ChatClient wrapper)
│   │   │   │   ├── persistence/    # CheckpointerConfig
│   │   │   │   ├── scanner/        # ProjectScanner
│   │   │   │   ├── scheduler/      # TaskScheduler
│   │   │   │   ├── events/         # EventBus, WorldmindEvent
│   │   │   │   └── oscillation/    # OscillationDetector
│   │   │   ├── sandbox/
│   │   │   │   ├── SandboxManager.java
│   │   │   │   ├── AgentDispatcher.java
│   │   │   │   └── InstructionBuilder.java
│   │   │   ├── dispatch/
│   │   │   │   ├── cli/            # picocli commands
│   │   │   │   ├── api/            # Spring MVC controllers
│   │   │   │   └── events/         # SSE streaming
│   │   │   └── force/
│   │   │       ├── auth/           # MCP token service
│   │   │       ├── SparkConfig.java
│   │   │       └── TerrainConfig.java
│   │   └── resources/
│   │       ├── application.yml
│   │       └── application-dev.yml
│   └── test/
│       └── java/com/worldmind/
│           ├── core/
│           │   ├── model/ModelTest.java
│           │   ├── graph/GraphTest.java
│           │   ├── nodes/          # Per-node tests
│           │   ├── engine/MissionEngineTest.java
│           │   ├── scanner/ProjectScannerTest.java
│           │   └── persistence/CheckpointerTest.java
│           ├── sandbox/SandboxManagerTest.java
│           ├── dispatch/cli/CliTest.java
│           ├── dispatch/api/ApiTest.java
│           └── integration/CoderIntegrationTest.java
```

---

## Dependencies Between Phases

```
Phase 0 (Scaffold)
    │
    ▼
Phase 1 (Brain Without Hands)  ← Can demo planning pipeline
    │
    ▼
Phase 2 (First Agent)      ← Can demo actual code generation
    │
    ▼
Phase 3 (The Loop)             ← Can demo build-test-fix cycle
    │
    ▼
Phase 4 (Fan-Out)              ← Can demo parallel execution
    │
    ▼
Phase 5 (Full Dispatch)        ← Can demo REST API + full CLI
    │
    ▼
Phase 6 (Hardening)            ← Production ready
```

Each phase builds on the previous one. Every phase ends with a runnable milestone where you can exercise the system and verify it works before proceeding.
