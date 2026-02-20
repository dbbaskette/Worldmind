# ✶ WORLDMIND

### Agentic Code Assistant — System Architecture Specification

**The Hybrid Architecture: LangGraph4j Control Plane + Goose Worker Nodes**

Version 2.0 · February 2026 · Draft

*Inspired by the Xandarian Worldmind — the sentient supercomputer of the Nova Corps*

-----

## Table of Contents

1. [Executive Summary](#1-executive-summary)
1. [Architectural Overview](#2-architectural-overview)
1. [Control Plane: Worldmind Core (LangGraph4j)](#3-control-plane-worldmind-core-langgraph4j)
1. [Worker Plane: Goose Agents](#4-worker-plane-goose-agents)
1. [Tool Layer: Nova Force (MCP Servers)](#5-tool-layer-nova-force-mcp-servers)
1. [Infrastructure Layer: Sandboxes](#6-infrastructure-layer-sandboxes)
1. [Interface Layer: Dispatch](#7-interface-layer-dispatch)
1. [Execution Patterns](#8-execution-patterns)
1. [Observability and Metrics](#9-observability-and-metrics)
1. [Performance Requirements](#10-performance-requirements)
1. [Technology Stack](#11-technology-stack)
1. [Implementation Roadmap](#12-implementation-roadmap)
1. [Future Considerations](#13-future-considerations)
1. [Nomenclature Reference](#14-nomenclature-reference)

-----

## 1. Executive Summary

Worldmind is a server-based agentic code assistant that accepts natural language development requests and autonomously plans, implements, tests, and reviews code. It employs a hybrid architecture that combines the deterministic orchestration capabilities of **LangGraph4j** (the Java port of LangGraph) with the autonomous coding power of **Goose** worker agents, all built on a **Spring Boot** foundation with **Spring AI** for LLM integration.

The system is designed around a clear separation of concerns: the **Worldmind control plane** (built on LangGraph4j + Spring Boot) manages state, planning, routing, persistence, and the software development lifecycle, while **Goose instances** (deployed as Agent workers inside containerized Sandboxes) perform the actual code generation, file manipulation, and command execution through their native MCP integration.

This architecture resolves the central tension identified in framework comparison research: LangGraph4j provides the engineering rigor required for production-grade orchestration (cyclic graphs, per-step checkpointing, conditional edges for dynamic routing) while Goose provides the developer-centric autonomy required for effective code generation (native filesystem interaction, self-correcting build loops, MCP-native tool access). Spring Boot provides the enterprise-grade application framework, dependency injection, and REST API infrastructure that ties it all together.

> **The Hybrid Principle**
>
> Worldmind is the nervous system. Goose is the hands. LangGraph4j decides *what* to do and in *what order*. Goose *does it*. Neither is sufficient alone: LangGraph4j without Goose lacks developer autonomy; Goose without LangGraph4j lacks state persistence, deterministic routing, and scalable orchestration.

-----

## 2. Architectural Overview

The system is organized into five layers, each with distinct responsibilities and technology choices. Communication between layers follows defined protocols, with MCP serving as the universal tool interface.

### 2.1 Layer Architecture

|Layer             |Worldmind Name|Technology                       |Responsibility                                                                                                                                                                                                                                  |
|------------------|--------------|--------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
|**Interface**     |Dispatch      |Spring Boot REST + CLI (picocli)|Accepts operator requests via REST API or CLI. Streams mission progress events via SSE. Provides mission management (submit, status, cancel, history).                                                                                           |
|**Orchestration** |Worldmind Core|LangGraph4j StateGraph          |The control plane. Houses Agent Prime (planner), the mission state machine, conditional routing logic, parallel fan-out for tasks, and the Postgres-backed checkpointer for state persistence.                                         |
|**Agent**         |Agents    |Goose (headless mode)           |The worker layer. Each Agent is a Goose instance running in a containerized Sandbox, connected to the Worldmind via a thin bridge that translates LangGraph4j state into Goose instructions and Goose output back into state updates.      |
|**Tool**          |Nova Force    |MCP Servers                     |Provides capabilities to Agents: filesystem access, git operations, shell execution, database queries, code search. Each MCP server is a Force channel. Goose connects to these natively as an MCP client.                                  |
|**Infrastructure**|Sandboxes     |Docker / Kubernetes / cf        |Container lifecycle management. Spins up isolated environments for each Agent deployment. Manages resource limits, volume mounts, network isolation, and cleanup.                                                                           |

### 2.2 System Flow Overview

The end-to-end flow for a typical mission proceeds through these stages:

1. **Request Intake:** The operator submits a natural language request via the Dispatch CLI or API. Example: *"Add a REST endpoint for user profiles with validation and tests."*
1. **Upload (Context Gathering):** Worldmind Core invokes Agent Researcher (research agent) to scan the project via Force::Terrain and Force::Chronicle MCP servers. Project structure, conventions, dependencies, and relevant source files are assembled into the Xandarian Archive (shared state).
1. **Mission Planning:** Agent Prime analyzes the request against the gathered context and produces a Mission: an ordered set of Tasks with agent assignments, dependencies, quality gates, and failure strategies.
1. **Approval:** In Interactive mode, the Mission is presented to the operator via Dispatch for review and approval. In Supervised mode, it auto-approves and proceeds. The operator can edit, reorder, or reject tasks.
1. **Agent Deployment:** For each eligible Task, Worldmind opens a Sandbox (spins up a Docker container), provisions it with the project volume and appropriate Force channels, and deploys a Goose instance configured for the specific task.
1. **Execution:** The Goose Agent executes its Task autonomously within the Sandbox, using MCP tools to read files, write code, run commands, and self-correct. Its output is captured and written back to the Xandarian Archive.
1. **Quality Gate (QualityGate of Approval):** After each Task completes, the Worldmind evaluates the quality gate. For code generation, this typically means routing to Agent Reviewer (reviewer) or Agent Tester (tester). If the QualityGate is denied, the Worldmind routes feedback back to the originating Agent for revision.
1. **Convergence:** When all Tasks are fulfilled and all QualityGates granted, Worldmind marks the Mission complete, commits the changes via Force::Chronicle, and reports results to the operator via Dispatch.

### 2.3 Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────┐
│                      DISPATCH (Interface Layer)                      │
│              CLI (picocli) + REST API (Spring Boot MVC)              │
│                         SSE Event Stream                             │
└──────────────────────────────┬──────────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────────┐
│                   WORLDMIND CORE (Control Plane)                     │
│                    LangGraph4j StateGraph (Java 21)                  │
│                    Spring AI (Anthropic Claude)                      │
│                                                                      │
│  ┌──────────┐   ┌──────────┐   ┌───────────────────┐               │
│  │ Classify  │──▶│ Upload   │──▶│ Agent Prime   │               │
│  │ Request   │   │ Context  │   │ (Plan Mission)    │               │
│  └──────────┘   └──────────┘   └─────────┬─────────┘               │
│                                           │                          │
│                                    ┌──────▼──────┐                   │
│                                    │  Execute     │                   │
│                                    │  Tasks  │  ◀── Parallel    │
│                                    │  (Fan-Out)   │      Fan-Out     │
│                                    └──┬───┬───┬──┘                   │
│                                       │   │   │                      │
│  ┌─────────────┐  ┌─────────────┐  ┌─┴───┴───┴──────────────┐      │
│  │  Evaluate   │◀─│  Dispatch   │◀─│  Parallel Branches      │      │
│  │  QualityGate       │  │  Agent  │  │  (1 per Task)      │      │
│  └──────┬──────┘  └─────────────┘  └────────────────────────┘      │
│         │                                                            │
│    ┌────┴────┐                                                       │
│    │Converge │──▶ Mission Complete / Failed                          │
│    │Results  │                                                       │
│    └─────────┘                                                       │
│                                                                      │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │            Xandarian Archive (Shared State)                  │    │
│  │       PostgreSQL-backed LangGraph4j Checkpointer             │    │
│  └─────────────────────────────────────────────────────────────┘    │
└──────────────────────────────┬──────────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────────┐
│                   SANDBOXES (Infrastructure)                       │
│                   Docker (dev) / Kubernetes (prod)                    │
│                                                                      │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐                 │
│  │  Sandbox A  │  │  Sandbox B  │  │  Sandbox C  │                │
│  │ ┌─────────┐ │  │ ┌─────────┐ │  │ ┌─────────┐ │                │
│  │ │  Goose  │ │  │ │  Goose  │ │  │ │  Goose  │ │                │
│  │ │ (Coder) │ │  │ │ (Reviewer) │ │  │ │(Tester)│ │                │
│  │ └────┬────┘ │  │ └────┬────┘ │  │ └────┬────┘ │                │
│  │      │      │  │      │      │  │      │      │                │
│  │  MCP Client │  │  MCP Client │  │  MCP Client │                │
│  └──────┼──────┘  └──────┼──────┘  └──────┼──────┘                │
└─────────┼────────────────┼────────────────┼────────────────────────┘
          │                │                │
          ▼                ▼                ▼
┌─────────────────────────────────────────────────────────────────────┐
│                    NOVA FORCE (MCP Tool Layer)                       │
│                                                                      │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐                │
│  │Force::Terrain│ │Force::Chronc.│ │ Force::Spark │                │
│  │ (Filesystem) │ │    (Git)     │ │   (Shell)    │                │
│  └──────────────┘ └──────────────┘ └──────────────┘                │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐                │
│  │Force::Archive│ │Force::Signal │ │Force::Lattice│                │
│  │  (Database)  │ │ (RAG/Search) │ │ (Lang Server)│                │
│  └──────────────┘ └──────────────┘ └──────────────┘                │
└─────────────────────────────────────────────────────────────────────┘
```

-----

## 3. Control Plane: Worldmind Core (LangGraph4j)

The control plane is the LangGraph4j StateGraph that defines the Worldmind's behavior. It is the single source of truth for mission state and the deterministic backbone that ensures reliable execution. LangGraph4j is the Java port of LangGraph, providing identical graph semantics in a JVM-native implementation.

### 3.1 Why LangGraph4j + Spring AI

Framework comparison research identified several properties that make LangGraph4j the right choice for the control plane:

- **Cyclic graph architecture:** Unlike DAG-based workflow engines, LangGraph4j supports cycles natively. This is essential for the build-test-fix loop where a Coding Node passes to a Testing Node which, on failure, routes back to the Coding Node. This self-correcting cycle is the core pattern of autonomous software engineering.
- **Conditional edges for dynamic routing:** The `addConditionalEdges()` API allows graph routing to be determined at runtime by inspecting the current state. Combined with Spring AI's structured output capabilities, this enables LLM-driven routing decisions with deterministic execution paths.
- **Per-step checkpointing:** LangGraph4j persists state after every super-step via its CheckpointSaver (Postgres-backed via `PostgresSaver`). If Worldmind crashes or redeploys during a long-running build, it resumes exactly where it left off.
- **Spring AI integration:** LangGraph4j integrates with Spring AI's ChatClient, providing access to Anthropic Claude with structured output (via `BeanOutputConverter` to Java records), tool calling (via `@Tool` annotation), and MCP client/server support.
- **Java 21 virtual threads:** Parallel Task execution leverages Java 21 virtual threads for efficient I/O-bound concurrency when managing multiple Goose Sandboxes simultaneously.
- **Time-travel debugging:** The checkpointing system enables replay of any execution from any prior state, which is invaluable for debugging failed Missions.

### 3.2 State Schema: The Xandarian Archive

The Xandarian Archive is the `AgentState` subclass that serves as LangGraph4j's shared state. Every node in the graph reads from and writes to this schema. At the API boundary, state is projected into Java records for type-safe access.

|Field               |Type                            |Description                                                                                                               |
|--------------------|--------------------------------|--------------------------------------------------------------------------------------------------------------------------|
|`mission_id`        |`String`                        |Unique identifier. Format: `WMND-YYYY-NNNN`.                                                                              |
|`request`           |`String`                        |Original operator request, verbatim.                                                                                      |
|`classification`    |`Classification` (record)       |Request category, complexity (1–5), affected components, planning strategy.                                               |
|`context`           |`ProjectContext` (record)       |Gathered project metadata: file tree, conventions, dependencies, relevant source excerpts, git state.                     |
|`tasks`        |`List<Task>` (record list) |Ordered list of all Tasks with their current status, results, and iteration counts.                                  |
|`active_sandboxes`  |`Map<String, SandboxInfo>`     |Registry of currently running containers: Sandbox ID, Agent type, Task ID, resource usage.                      |
|`files_created`     |`List<FileRecord>` (record list)|All files created or modified during the Mission, with paths, content hashes, and originating Task.                  |
|`test_results`      |`List<TestResult>` (record list)|Aggregated test execution results across all Tasks.                                                                  |
|`review_feedback`   |`List<ReviewFeedback>`          |Accumulated review feedback from Agent Reviewer, keyed by Task and iteration.                                       |
|`execution_strategy`|`ExecutionStrategy` (enum)      |`LINEAR` | `PARALLEL` | `ITERATIVE`. Determines how the graph routes Tasks.                                          |
|`interaction_mode`  |`InteractionMode` (enum)        |`INTERACTIVE` | `SUPERVISED` | `AUTONOMOUS`. Controls operator involvement.                                                   |
|`status`            |`MissionStatus` (enum)          |`UPLOADING` | `PLANNING` | `AWAITING_APPROVAL` | `EXECUTING` | `REPLANNING` | `COMPLETED` | `FAILED` | `CANCELLED`.       |
|`metrics`           |`MissionMetrics` (record)       |Token usage, elapsed time per Task, retry counts, quality gate pass rates.                                           |
|`created_at`        |`Instant`                       |Mission creation timestamp (ISO 8601).                                                                                    |
|`updated_at`        |`Instant`                       |Last state modification timestamp.                                                                                        |

### 3.3 Graph Topology

The LangGraph4j StateGraph defines the following node topology. Each node is a Java method (or lambda implementing `NodeAction<WorldmindState>`) that receives the Xandarian Archive, performs work, and returns a partial state update as `Map<String, Object>`.

1. **`classify_request`** — Receives the raw request. Uses Spring AI's ChatClient with `BeanOutputConverter` to produce a `Classification` record (category, complexity, affected components). Routes to `upload_context`.
1. **`upload_context`** — Invokes Agent Researcher via a Goose Sandbox to scan the project. Researcher connects to Force::Terrain and Force::Chronicle to gather structure, conventions, and relevant source files. Writes `ProjectContext` to the Archive. Routes to `plan_mission`.
1. **`plan_mission`** — Agent Prime node. Uses the Classification and ProjectContext with Spring AI structured output to generate a Mission with ordered Tasks. This is the most complex LLM call in the system. Routes to `await_approval` (Interactive) or `execute_tasks` (Supervised/Autonomous).
1. **`await_approval`** — Pauses execution and emits the Mission to the Dispatch interface. Waits for operator input (approve, edit, reject). On approval, routes to `execute_tasks`. On edit, routes back to `plan_mission` with modifications. On reject, routes to `mission_cancelled`.
1. **`execute_tasks`** — The fan-out node. Spawns one execution branch per eligible Task (respecting dependency ordering). Each branch targets the `dispatch_agent` node with the Task's localized state.
1. **`dispatch_agent`** — Opens a Sandbox for the Task's assigned Agent type. Translates the Task's input spec into a Goose instruction set. Launches the Goose instance and waits for completion. Captures output and writes results to the Archive.
1. **`evaluate_quality_gate`** — Quality gate evaluation. Checks the Task's completion criteria (tests pass, lint clean, review approved). If the QualityGate is granted, marks the Task as fulfilled. If denied, applies the Task's `on_failure` strategy (retry, replan, escalate, skip).
1. **`converge_results`** — Aggregation node. Runs after all Task branches complete. Merges file changes, test results, and review feedback. Performs final validation. Routes to `mission_complete` or `mission_failed`.
1. **`replan`** — Triggered when `evaluate_quality_gate` determines a replan is needed. Agent Prime re-evaluates remaining Tasks in light of the failure and generates a revised Mission segment. Routes back to `execute_tasks`.

### 3.4 Conditional Routing Logic

The graph's conditional edges encode the decision logic that makes the system deterministic rather than relying on LLM judgment for routing:

|From Node         |To Node             |Condition                                                         |
|------------------|--------------------|------------------------------------------------------------------|
|`plan_mission`    |`await_approval`    |`interaction_mode == INTERACTIVE`                                 |
|`plan_mission`    |`execute_tasks`|`interaction_mode in [SUPERVISED, AUTONOMOUS]`                    |
|`evaluate_quality_gate`   |`dispatch_agent`|`quality_gate == DENIED AND on_failure == RETRY AND retries < max_retries`|
|`evaluate_quality_gate`   |`replan`            |`quality_gate == DENIED AND on_failure == REPLAN`                         |
|`evaluate_quality_gate`   |`await_approval`    |`quality_gate == DENIED AND on_failure == ESCALATE`                       |
|`evaluate_quality_gate`   |`converge_results`  |`quality_gate == DENIED AND on_failure == SKIP`                           |
|`evaluate_quality_gate`   |`converge_results`  |`quality_gate == GRANTED AND all_tasks_resolved`                     |
|`evaluate_quality_gate`   |*(wait)*            |`quality_gate == GRANTED AND pending_tasks_exist`                    |
|`converge_results`|`mission_complete`  |All quality gates passed                                          |
|`converge_results`|`replan`            |Integration failures detected                                     |

### 3.5 Persistence and Recovery

The Worldmind uses LangGraph4j's `PostgresSaver` to persist the Xandarian Archive after every graph node execution:

- **Crash recovery:** If the Worldmind server crashes or restarts during a Mission, the graph resumes from the last checkpointed state. Completed Tasks are not re-executed. In-progress Sandboxes are detected and either re-attached or re-provisioned.
- **Time-travel debugging:** Operators can inspect the Archive at any prior checkpoint, replaying the decision path that led to a failure. This is exposed via the Dispatch API as a mission timeline view.
- **Long-running mission support:** Complex Missions may run for hours. The checkpointing system ensures that progress is never lost, even across server deployments or scaling events.

-----

## 4. Worker Plane: Goose Agents

Each Agent is a Goose instance running in headless mode inside a containerized Sandbox. Goose was selected as the worker engine because of its native MCP integration, robust filesystem capabilities, and autonomous self-correction loops.

### 4.1 Why Goose

Framework comparison research identified Goose's strengths and limitations clearly. Its strengths align perfectly with the worker role:

- **MCP-native:** Goose is built as an MCP super-client. It connects natively to MCP servers for filesystem, git, shell, database, and search operations. Agents inherit the full Nova Force capability set without any adapter code.
- **Developer-centric autonomy:** Goose behaves like a developer at a terminal. It reads files, writes code, runs builds, reads error output, and self-corrects. This internal reasoning loop is exactly what a Agent needs to execute a Task autonomously.
- **Self-correcting build loops:** When a command fails (e.g., a build error or test failure), Goose reads the error output, reasons about the fix, and retries. This happens inside the Goose instance's own context, before the result ever reaches the Worldmind's quality gate. This reduces the number of outer-loop retries significantly.
- **Sandboxed execution:** Running Goose inside a Docker container (Sandbox) provides natural isolation. Each Agent operates on a mounted project volume with controlled network access and resource limits.

Its known limitations are mitigated by the hybrid architecture:

- **No native API** *(mitigated)*: Goose is CLI-first. The Sandbox Bridge wraps the Goose CLI process, translating LangGraph4j state into Goose instructions and capturing structured output.
- **No recursion** *(mitigated)*: Goose subagents cannot spawn their own subagents. This is irrelevant because all task decomposition happens in the LangGraph4j control plane. Goose instances are workers, not orchestrators.
- **Session-based state** *(mitigated)*: Goose does not persist state across sessions. Each Agent deployment handles a single Task. All persistent state lives in the Xandarian Archive, managed by LangGraph4j.

### 4.2 Agent Roster

|Agent             |Rank      |Goose Config                                  |Force Channels                  |Behavioral Profile                                                                                                                                            |
|----------------------|----------|----------------------------------------------|--------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------|
|**Agent Prime**   |Nova Prime|Planner mode (runs in LangGraph4j, not Goose) |Terrain (read), Chronicle (read)|Request classification, context analysis, mission planning, execution management. Operates within the control plane, not in a Sandbox.                       |
|**Agent Researcher**   |Millennian|Research mode                                 |Terrain, Chronicle, Signal      |Read-only. Scans project structure, reads source files, searches documentation. Produces structured context summaries. Never writes files.                    |
|**Agent Coder**   |Agent |Code-gen mode                                 |Terrain, Chronicle, Spark       |Read-write. Generates new code, creates files, modifies existing files. Runs build commands to validate. Uses self-correction loops on build failures.        |
|**Agent Reviewer**   |Agent |Review mode                                   |Terrain, Chronicle, Signal      |Read-only. Analyzes code for quality, security, consistency, and correctness. Produces structured review feedback. Grants or denies the QualityGate of Approval.     |
|**Agent Tester**|Agent |Test mode                                     |Terrain, Spark                  |Read-write (test files only). Generates tests following project conventions. Executes test suites. Reports pass/fail with coverage metrics.                   |
|**Agent Refactorer**   |Agent |Refactor mode                                 |Terrain, Chronicle, Spark       |Read-write. Restructures code without changing behavior. Runs existing tests before and after to verify behavioral equivalence.                               |

### 4.3 The Sandbox Bridge

The Sandbox Bridge is the critical integration layer between LangGraph4j and Goose. It translates between the two systems:

1. **Task → Goose instruction:** The bridge takes a `Task` record from the Xandarian Archive and constructs a Goose instruction file. This file includes the task description, relevant context (file excerpts, conventions, constraints), specific instructions, and success criteria. The instruction is written to a file inside the Sandbox volume.
1. **Goose launch:** The bridge executes `goose run --instructions task.md` inside the Sandbox container. The Goose process runs headlessly, connecting to the pre-configured MCP servers (Force channels) available inside the container.
1. **Output capture:** The bridge monitors the Goose process via stdout (JSON-structured output mode). It captures: files created/modified, commands executed and their exit codes, errors encountered and self-corrections applied, and the final completion status.
1. **Result → Archive:** The bridge translates the captured output into a `TaskResult` record and writes it back to the Xandarian Archive. This includes file records (paths + content hashes), command logs, and a success/failure determination.

> **Bridge Design Principle**
>
> The Sandbox Bridge should be *thin*. It translates between LangGraph4j state and Goose instructions, and between Goose output and LangGraph4j state. It should not contain business logic, routing decisions, or quality evaluation. Those responsibilities belong to the control plane.

### 4.4 MCP Permission Matrix

Each Agent type receives a tailored MCP server configuration inside its Sandbox. This enforces the principle of least privilege.

|Force Channel            |Researcher|Coder      |Reviewer|Tester              |Refactorer      |
|-------------------------|-----|-----------|-----|----------------------|-----------|
|**Terrain** (Filesystem) |Read |Read/Write |Read |Read/Write (test dirs)|Read/Write |
|**Chronicle** (Git)      |Read |Read/Commit|Read |—                     |Read/Commit|
|**Spark** (Shell)        |—    |Full       |—    |Test commands only    |Full       |
|**Signal** (Search)      |Full |—          |Full |—                     |—          |
|**Archive** (Database)   |Read |—          |—    |—                     |—          |
|**Lattice** (Lang Server)|—    |Diagnostics|Full |—                     |Diagnostics|

-----

## 5. Tool Layer: Nova Force (MCP Servers)

The Nova Force is the collection of MCP servers that provide capabilities to the Agent agents. Each MCP server runs as an independent process (or container) and exposes tools via the Model Context Protocol. Goose connects to these natively as an MCP client. The Worldmind itself can also leverage Spring AI's MCP client support for control-plane operations.

### 5.1 Force Channel Specifications

|Channel             |Implementation        |Key Tools Exposed                                                           |Deployment Model                                                                                                            |
|--------------------|----------------------|----------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------|
|**Force::Terrain**  |MCP Filesystem Server |`read_file`, `write_file`, `list_directory`, `search_files`, `file_exists`  |Sidecar container per Sandbox, mounted to the project volume. Path restrictions enforced per Agent type.               |
|**Force::Chronicle**|MCP Git Server        |`git_status`, `git_diff`, `git_log`, `git_blame`, `git_commit`, `git_branch`|Shared service. Single instance per project. Write operations gated by Agent type.                                      |
|**Force::Spark**    |MCP Shell Server      |`run_command`, `run_script`, `get_env`, `list_processes`                    |Sidecar container per Sandbox. Command allowlisting enforced per Agent type.                                           |
|**Force::Archive**  |gp-mcp-server (custom)|`query`, `describe_table`, `list_schemas`, `explain_plan`                   |Shared service. Connection pooling, query validation, and parameterized queries per the gp-mcp-server security architecture.|
|**Force::Signal**   |MCP RAG/Search Server |`search_codebase`, `search_docs`, `semantic_search`, `get_definition`       |Shared service. Indexes the project codebase and documentation. Updated incrementally as Agents modify files.           |
|**Force::Lattice**  |MCP Language Server   |`get_diagnostics`, `get_completions`, `find_references`, `get_symbols`      |Per-language sidecar. Python LSP for Python projects, TypeScript LSP for TS projects.                                       |

### 5.2 Security Model

The Nova Force security model follows the principle of least privilege at multiple levels:

- **Agent-level restrictions:** Each Agent type has a defined set of Force channels and permission levels (read-only, read-write, restricted-write). These are enforced in the Goose MCP configuration injected by the Sandbox Bridge.
- **Sandbox-level isolation:** Each Sandbox container has its own network namespace, filesystem mount, and resource limits. Agents cannot access other Agents' Sandboxes or the host system.
- **Force channel authentication:** MCP servers require authentication tokens issued by the Worldmind. Tokens are scoped to the specific Mission and Task, with expiration times matching the expected Task duration.
- **Command allowlisting:** Force::Spark (shell execution) uses an allowlist model. Only approved commands (build tools, test runners, linters) are permitted. The allowlist is configurable per project and per Agent type.
- **Write path restrictions:** Force::Terrain (filesystem) enforces path-based write restrictions. Agent Tester can only write to test directories. Agent Reviewer cannot write at all. These restrictions are enforced at the MCP server level, not just in the Goose configuration.

-----

## 6. Infrastructure Layer: Sandboxes

Sandboxes are the containerized execution environments in which Agents operate. The Sandbox layer manages the full container lifecycle: provisioning, configuration, monitoring, and cleanup.

### 6.1 Sandbox Lifecycle

1. **Provision:** When the Worldmind dispatches a Task, the Sandbox manager (a Spring-managed `@Service`) selects the appropriate container image (based on Agent type and project language), provisions it with the project volume mount, injects the MCP server configuration, and starts the container via the Docker Java client.
1. **Configure:** The Sandbox Bridge writes the Goose instruction file and MCP configuration to the container volume. Environment variables are set for the Goose process: model selection, token limits, and connection strings for the Force channel MCP servers.
1. **Execute:** The Goose process runs inside the container. The Bridge monitors it via stdout and process health checks. Resource usage (CPU, memory, disk) is tracked and reported to the Xandarian Archive.
1. **Capture:** On Goose process completion, the Bridge captures the final state: files modified on the volume, exit code, structured output logs, and any error information.
1. **Teardown:** After result capture, the Sandbox is torn down. The container is stopped and removed. Volume data (file modifications) has already been committed to the shared project volume. Temporary artifacts are cleaned up.

### 6.2 Container Images

Sandbox container images are purpose-built for each Agent type and project language combination:

- **Base image:** A Goose-ready base with the Goose CLI, MCP client libraries, and common development tools pre-installed.
- **Language layers:** Stacked on top of the base — Python (with pip, poetry, pytest), Node.js (with npm, jest), Java (with Maven/Gradle, JUnit), Go (with go test), etc. Multiple language layers can be combined for polyglot projects.
- **Agent profile:** A lightweight configuration layer that sets the Goose system prompt, MCP server permissions, and behavioral constraints for the specific Agent type.

### 6.3 Kubernetes Deployment (Production)

In production, Sandboxes run as Kubernetes Jobs:

- **Job per Task:** Each Task dispatches a K8s Job with the appropriate container image, resource requests/limits, and volume claims.
- **Project volumes:** The project codebase is mounted via a PersistentVolumeClaim (PVC) shared across all Sandboxes in a Mission. This ensures all Agents see each other's file modifications. Concurrent write conflicts are managed by the Worldmind's dependency ordering (parallel Tasks operate on non-overlapping files).
- **Resource scaling:** Complex Missions with many parallel Tasks can scale horizontally across K8s nodes. The Sandbox manager respects resource quotas and can queue Tasks when capacity is constrained.
- **Observability:** Container logs are forwarded to the Worldmind's event stream. K8s metrics (CPU, memory, disk I/O) are collected per Sandbox and surfaced in the Dispatch mission dashboard.

### 6.4 Development Mode (Docker)

For local development, Sandboxes run as Docker containers:

```bash
# Sandbox spin-up (simplified)
docker run -d \
  --name sandbox-coder-001 \
  -v /path/to/project:/workspace \
  -v /tmp/task-001.md:/instructions/task.md \
  -e GOOSE_MODEL=claude-sonnet-4-5-20250929 \
  -e MCP_TERRAIN_URL=http://host.docker.internal:3001 \
  -e MCP_CHRONICLE_URL=http://host.docker.internal:3002 \
  -e MCP_SPARK_URL=http://host.docker.internal:3003 \
  --memory=4g --cpus=2 \
  ghcr.io/dbbaskette/agent-coder:latest \
  goose run --instructions /instructions/task.md
```

-----

## 7. Interface Layer: Dispatch

Dispatch is the operator's interface to the Worldmind. It provides both a CLI for interactive use and a REST API for programmatic integration.

### 7.1 Dispatch CLI

The CLI is the primary interface for v1. Built with picocli (Java), it communicates with the Worldmind Core via the Dispatch REST API.

#### Key Commands

|Command                                        |Description                                               |
|-----------------------------------------------|----------------------------------------------------------|
|`worldmind mission "<request>"`                |Submit a new Mission. Enters interactive planning flow.   |
|`worldmind mission --auto "<request>"`         |Submit a Mission in Supervised mode (auto-approve).       |
|`worldmind status <mission-id>`                |Show current Mission status with Task progress.      |
|`worldmind status --watch <mission-id>`        |Stream real-time Mission events to the terminal.          |
|`worldmind log <mission-id>`                   |Show the complete Mission execution log.                  |
|`worldmind timeline <mission-id>`              |Show the checkpointed state timeline (time-travel view).  |
|`worldmind cancel <mission-id>`                |Cancel a running Mission. Tears down active Sandboxes.    |
|`worldmind history`                            |List recent Missions with status and summary.             |
|`worldmind inspect <mission-id> <task-id>`|Show detailed output from a specific Agent deployment.|
|`worldmind force-channels`                     |List available Nova Force channels and their status.      |
|`worldmind agents`                         |Show the Agent roster with availability.              |

#### Example Session

```
$ worldmind mission "Add a REST endpoint for user profiles with validation"

✶ WORLDMIND v1.0
──────────────────────────────────
Uploading project context... 47 files indexed.

MISSION WMND-2026-0042
Objective: Add REST endpoint for user profiles with input validation
Strategy: Iterative (max 3 cycles)

TASKS:
  1. [RESEARCHER]    Reconnaissance — scan project patterns
  2. [CODER]    Implement UserProfileController
  3. [CODER]    Implement UserProfileService
  4. [TESTER] Write unit + integration tests
  5. [REVIEWER]    Final code review

Proceed? [Y/n/edit] y

Deploying agents...

  ✓ Task 1  [RESEARCHER]     Reconnaissance complete        4.2s
  ✓ Task 2  [CODER]     UserProfileController created   8.7s
  ✓ Task 3  [CODER]     UserProfileService created      6.3s
  ○ Task 4  [TESTER]  Running tests...
  ─ Task 5  [REVIEWER]     Awaiting dependencies

  ✓ Task 4  [TESTER]  12 tests passing, 94% coverage  5.1s
  ✓ Task 5  [REVIEWER]     QualityGate of Approval granted        7.8s

Mission WMND-2026-0042 complete.
5 files created, 2 modified, 12 tests passing.
Changes committed to branch: worldmind/WMND-2026-0042
```

### 7.2 Dispatch REST API

|Method|Endpoint                                |Description                                                      |
|------|----------------------------------------|-----------------------------------------------------------------|
|`POST`|`/api/v1/missions`                      |Submit a new Mission. Body: `{ request, mode, project_path }`.   |
|`GET` |`/api/v1/missions/{id}`                 |Get Mission status including all Task statuses.             |
|`GET` |`/api/v1/missions/{id}/events`          |SSE stream of real-time Mission events.                          |
|`POST`|`/api/v1/missions/{id}/approve`         |Approve a Mission plan (Interactive mode).                       |
|`POST`|`/api/v1/missions/{id}/edit`            |Submit plan modifications (Interactive mode).                    |
|`POST`|`/api/v1/missions/{id}/cancel`          |Cancel a running Mission.                                        |
|`GET` |`/api/v1/missions/{id}/timeline`        |Get checkpointed state history.                                  |
|`GET` |`/api/v1/missions/{id}/tasks/{did}`|Get detailed Task result.                                   |
|`GET` |`/api/v1/sandboxes`                     |List active Sandboxes with resource usage.                       |
|`GET` |`/api/v1/health`                        |System health: Worldmind Core, Force channels, Sandbox capacity.|

### 7.3 API Response Format

```json
{
  "mission_id": "WMND-2026-0042",
  "status": "executing",
  "objective": "Add REST endpoint for user profiles",
  "strategy": "iterative",
  "tasks": [
    {
      "id": "task_001",
      "agent": "researcher",
      "description": "Reconnaissance",
      "status": "fulfilled",
      "quality_gate": "approved",
      "elapsed_ms": 4200
    },
    {
      "id": "task_002",
      "agent": "coder",
      "description": "Implement UserProfileController",
      "status": "running",
      "quality_gate": null,
      "elapsed_ms": null
    }
  ],
  "archive": {
    "files_created": 3,
    "files_modified": 1,
    "tests_passed": 0,
    "iteration": 1
  }
}
```

-----

## 8. Execution Patterns

The Worldmind supports three execution patterns, selected by Agent Prime based on the request classification and Mission structure.

### 8.1 Linear Execution

Tasks execute sequentially, each waiting for the prior to complete. Used for simple, dependency-heavy tasks where each step depends on the output of the previous one.

```
[Researcher] ──▶ [Coder] ──▶ [Tester] ──▶ [Reviewer] ──▶ Complete
```

**Example:** A bug fix where the fix depends on research, the test depends on the fix, and the review depends on both.

### 8.2 Parallel Fan-Out

Multiple Tasks execute concurrently when they share no dependencies. This is the primary pattern for feature implementation where multiple files can be generated independently. Fan-out uses LangGraph4j's parallel branch execution with `CompletableFuture` and Java 21 virtual threads.

```
                  ┌──▶ [Coder: Controller] ──┐
                  │                           │
[Researcher] ──▶ Fan-Out──▶ [Coder: Service]    ──┼──▶ [Tester] ──▶ [Reviewer]
                  │                           │
                  └──▶ [Coder: Repository] ──┘
```

The fan-out mechanism enables true dynamic spawning: if Agent Prime plans 3 parallel Tasks, the graph fans out to 3 concurrent Goose Sandboxes. If it plans 8, it fans to 8. The topology is determined at runtime by the planner's output, not by the graph definition.

### 8.3 Iterative Cycles (Build-Test-Fix Loop)

The most critical pattern for code quality. After code generation, the output flows to review or testing. If the quality gate fails, the graph cycles back to the originating Agent with accumulated feedback.

```
         ┌──────────────────────────────┐
         │                              │
         ▼                              │
[Coder] ──▶ [Tester] ──▶ QualityGate? ──NO──┘
                              │
                             YES
                              │
                              ▼
                          [Reviewer] ──▶ Complete
```

Key controls for iterative cycles:

- **`max_iterations`:** Default 3. Configurable per Task. Prevents infinite loops.
- **Cumulative feedback:** Each iteration includes all prior feedback, preventing the Agent from repeating previously identified mistakes.
- **Oscillation detection:** The Worldmind monitors for alternating patterns (Agent alternates between two approaches) and escalates to the operator early rather than exhausting the iteration budget.
- **Progressive escalation:** First failure → retry with feedback. Second failure → retry with stronger constraints. Third failure → escalate to operator with full context.

-----

## 9. Observability and Metrics

### 9.1 Structured Event Stream

The Worldmind emits structured events at every significant state transition. These events power the Dispatch CLI progress display, the API's SSE stream, and operational monitoring.

|Event                |Payload                                                  |Consumer                            |
|---------------------|---------------------------------------------------------|------------------------------------|
|`mission.created`    |Full Mission plan, operator request, classification      |Dispatch (display plan), audit log  |
|`mission.approved`   |Mission ID, approval source (operator/auto)              |Dispatch, audit log                 |
|`sandbox.opened`    |Sandbox ID, Agent type, Task ID, container info|Dispatch (progress), resource monitor|
|`task.started`  |Task ID, Agent name, input summary              |Dispatch (progress updates)         |
|`task.progress` |Intermediate output: files created, commands run         |Dispatch (live feed), debugging     |
|`task.fulfilled`|Result summary, files modified, quality gate outcome     |Dispatch, metrics                   |
|`quality_gate.denied`        |Task ID, failure details, `on_failure` action       |Dispatch (alert), audit log         |
|`mission.replanned`  |Revised Task list, reason for replan                |Dispatch, audit log                 |
|`mission.completed`  |Final summary, all results, total metrics                |Dispatch, metrics, audit log        |
|`mission.escalated`  |Reason, context, operator options                        |Dispatch (notification)             |

### 9.2 Log Format

All system logs use the Worldmind vocabulary consistently:

```
[WORLDMIND]        Initializing Xandarian Archive...
[WORLDMIND]        Nova Force channels online: Terrain, Chronicle, Spark
[WORLDMIND]        Agent roster loaded: Prime, Coder, Reviewer, Tester, Refactorer, Researcher
[DISPATCH]         Mission request received: "Add user profile endpoint"
[WORLDMIND]        Uploading project context... 47 files indexed.
[AGENT PRIME]  Mission planned: 5 tasks, 3 agents assigned.
[SANDBOX]         Opening for Agent Coder...
[AGENT CODER]  Task 002: Implement UserProfileController.
[FORCE::TERRAIN]   3 files created.
[AGENT CODER]  Task 002 complete. 3 files, 187 lines.
[AGENT REVIEWER]  QualityGate of Approval granted.
[WORLDMIND]        Mission WMND-2026-0042 complete. All tasks fulfilled.
```

### 9.3 Metrics

The following metrics are collected per Mission and aggregated for system-wide monitoring, exposed via Spring Boot Actuator and Micrometer:

- **Planning latency:** Time from request intake to Mission plan approval.
- **Execution time:** Total wall-clock time and per-Task breakdown.
- **Token consumption:** LLM tokens used, broken down by Agent Prime (planning), Agent workers (execution), and quality gate evaluation.
- **QualityGate of Approval rate:** Percentage of Tasks that pass their quality gate on first attempt.
- **Iteration depth:** Average and max number of build-test-fix cycles per Mission.
- **Sandbox utilization:** Container count, resource usage, queue depth, average Sandbox lifetime.
- **Escalation rate:** Percentage of Missions requiring operator intervention, categorized by reason.

-----

## 10. Performance Requirements

|Metric                                   |Target  |Notes                                                                          |
|-----------------------------------------|--------|-------------------------------------------------------------------------------|
|Time to first plan (complexity 1–2)      |< 10s   |Includes classification and minimal context upload.                            |
|Time to first plan (complexity 3–5)      |< 30s   |Includes full context upload and multi-step planning.                          |
|Sandbox spin-up time                    |< 5s    |From `dispatch_agent` to Goose process running. Requires pre-pulled images.|
|Task execution (simple file gen)    |< 30s   |Single file generation with validation.                                        |
|Task execution (complex multi-file) |< 3 min |Multiple files with build verification.                                        |
|Full Mission (complexity 3, 5 Tasks)|< 10 min|End-to-end including planning, execution, and review cycles.                   |
|Checkpoint write latency                 |< 100ms |Postgres write per super-step. Must not bottleneck graph execution.            |
|Event stream latency                     |< 500ms |From state transition to Dispatch UI update.                                   |
|Concurrent Missions per instance         |5 (v1)  |Scaling horizontally via K8s replicas for higher throughput.                   |
|Max parallel Sandboxes per Mission       |10 (v1) |Configurable. Limited by K8s resource quotas.                                  |

-----

## 11. Technology Stack

|Component            |Technology                                  |Rationale                                                                                                                         |
|---------------------|--------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------|
|**Language**         |Java 21                                     |Virtual threads for concurrent I/O, records for data modeling, pattern matching. LTS release with modern features.                |
|**Application Frame**|Spring Boot 3.4                             |Enterprise-grade DI, REST API, actuator metrics, JPA, security. The standard for production Java applications.                   |
|**AI Integration**   |Spring AI 1.1                               |Anthropic Claude integration, structured output via BeanOutputConverter, @Tool annotation, MCP client/server support.             |
|**Control Plane**    |LangGraph4j 1.8                             |Java port of LangGraph. Cyclic graphs, conditional edges, per-step checkpointing, parallel execution. 1:1 feature parity.        |
|**Worker Engine**    |Goose (headless CLI)                        |MCP-native, developer-centric autonomy, self-correcting build loops. Strongest filesystem/code interaction of any agent framework.|
|**State Persistence**|PostgreSQL (via LangGraph4j PostgresSaver)  |Battle-tested, supports the checkpoint schema natively, enables time-travel queries.                                              |
|**Container Runtime**|Docker (dev) / Kubernetes (prod)            |Standard container orchestration. K8s Jobs map perfectly to the Sandbox lifecycle.                                               |
|**Docker Client**    |Docker Java (com.github.docker-java)        |Mature Java client for Docker Engine API. Sandbox lifecycle management.                                                          |
|**CLI Interface**    |picocli                                     |Modern Java CLI framework. ANSI color support, subcommands, tab completion. Fast startup with GraalVM native image.               |
|**Build Tool**       |Maven                                       |Standard Java build tool. BOM support for Spring AI and LangGraph4j dependency management.                                        |
|**MCP Servers**      |Per-channel (see Force specs)               |Standard MCP protocol. Mix of community servers and custom implementations.                                                       |
|**LLM Provider**     |Anthropic Claude (via Spring AI)            |Agent Prime uses Claude Sonnet 4.5 for planning. Workers use configurable models via Goose.                                   |
|**Observability**    |Micrometer + Spring Boot Actuator           |Events emitted as structured JSON. Prometheus-compatible metrics via Actuator endpoints.                                          |

-----

## 12. Implementation Roadmap

### Phase 1: Foundation (Weeks 1–4)

- [ ] Spring Boot project with LangGraph4j StateGraph: `classify_request`, `plan_mission`, `execute_tasks` nodes
- [ ] Xandarian Archive schema (Java records for Mission, Task, state fields; AgentState for graph)
- [ ] PostgresSaver integration for state persistence
- [ ] Single Agent type: Agent Coder (code generator) running Goose in Docker
- [ ] Sandbox Bridge: Task-to-Goose-instruction translation, output capture
- [ ] Force::Terrain (Filesystem MCP) server configured for Goose
- [ ] Basic Dispatch CLI: `worldmind mission`, `worldmind status`

### Phase 2: The Loop (Weeks 5–8)

- [ ] Add Agent Reviewer (reviewer) and Agent Tester (tester)
- [ ] Implement the iterative build-test-fix cycle with conditional routing
- [ ] Quality gate evaluation (`evaluate_quality_gate` node)
- [ ] Force::Spark (Shell MCP) for build and test execution
- [ ] Force::Chronicle (Git MCP) for branch management and commits
- [ ] Retry, replan, and escalate failure strategies

### Phase 3: Scale (Weeks 9–12)

- [ ] Parallel Task execution via fan-out with virtual threads
- [ ] Add Agent Researcher (research) and Agent Refactorer (refactorer)
- [ ] Kubernetes deployment with K8s Jobs for Sandboxes
- [ ] Full Dispatch CLI with `--watch` mode, `timeline`, and `inspect` commands
- [ ] Dispatch REST API with SSE event streaming
- [ ] Observability: structured events, Micrometer metrics

### Phase 4: Polish (Weeks 13–16)

- [ ] Force::Signal (RAG/Search MCP) for codebase-aware planning
- [ ] Force::Lattice (Language Server MCP) for static analysis augmentation
- [ ] Oscillation detection and progressive escalation in iterative cycles
- [ ] Multi-language Sandbox images (Python, Node, Java, Go)
- [ ] Security hardening: token-scoped MCP auth, command allowlisting, path restrictions
- [ ] Performance optimization: image pre-pulling, warm Sandbox pools, context budget tuning

-----

## 13. Future Considerations

- **Dynamic Agent registration:** Allow new Agent types to be registered at runtime via a capability manifest. Agent Prime would discover available Agents and incorporate them into planning without code changes.
- **Multi-project Missions:** Support Missions that span multiple repositories or services, with cross-project dependency tracking and coordinated commits.
- **Learning from outcomes:** Track which planning strategies succeed for which request types. Use this history to improve Agent Prime's planning over time.
- **Web UI:** A browser-based Dispatch dashboard for Mission visualization, real-time Agent monitoring, and interactive plan editing. Built as a separate frontend consuming the Dispatch REST API.
- **CI/CD integration:** Trigger Missions from CI/CD pipelines (e.g., "fix this failing test" triggered by a GitHub Actions failure). Agents commit fixes directly to branches for review.
- **Worker engine alternatives:** The Sandbox Bridge is designed to be worker-agnostic. Future Agent implementations could use Claude Code, Aider, or custom LLM agents instead of Goose, selected per Agent type based on task requirements.

-----

## 14. Nomenclature Reference

|Technical Concept   |Worldmind Name       |Description                                                      |
|--------------------|---------------------|-----------------------------------------------------------------|
|Orchestration Server|**Worldmind**        |The LangGraph4j control plane that plans and manages execution.  |
|CLI / API Interface |**Dispatch**         |The operator's interface. Dispatches missions to the Worldmind.  |
|Specialist Agents   |**Agents**       |Goose worker instances dispatched to execute Tasks.         |
|Planner Agent       |**Agent Prime**  |The lead Agent responsible for mission planning.             |
|Agent Containers    |**Sandboxes**        |Docker/K8s environments in which Agents operate.             |
|Execution Plans     |**Missions**         |Structured plans with ordered Tasks.                        |
|Plan Steps          |**Tasks**       |Individual tasks assigned to specific Agents.                |
|MCP Servers / Tools |**Nova Force**       |The MCP tool layer. Each server is a Force channel.              |
|Shared State        |**Xandarian Archive**|The LangGraph4j state object (Java records + Postgres).          |
|Context Gathering   |**Upload**           |Scanning the codebase to build the context window.               |
|Quality Gates       |**QualityGate of Approval** |Criteria for Task success. Granted or denied.               |
|User                |**Operator**         |The human using the system.                                      |

-----

> **Architectural Invariant**
>
> The separation between the LangGraph4j control plane and the Goose worker plane is the system's most important architectural decision. The control plane must never execute code directly. The worker plane must never make routing decisions. Violating this boundary collapses the hybrid model into a monolith and loses the advantages of both frameworks.

-----

*Worldmind — Tell it what to build. It plans the mission, deploys the agents, and delivers the code.*
