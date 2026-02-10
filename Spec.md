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
1. [Worker Plane: Goose Centurions](#4-worker-plane-goose-centurions)
1. [Tool Layer: Nova Force (MCP Servers)](#5-tool-layer-nova-force-mcp-servers)
1. [Infrastructure Layer: Starblasters](#6-infrastructure-layer-starblasters)
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

The system is designed around a clear separation of concerns: the **Worldmind control plane** (built on LangGraph4j + Spring Boot) manages state, planning, routing, persistence, and the software development lifecycle, while **Goose instances** (deployed as Centurion workers inside containerized Starblasters) perform the actual code generation, file manipulation, and command execution through their native MCP integration.

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
|**Orchestration** |Worldmind Core|LangGraph4j StateGraph          |The control plane. Houses Centurion Prime (planner), the mission state machine, conditional routing logic, parallel fan-out for directives, and the Postgres-backed checkpointer for state persistence.                                         |
|**Agent**         |Centurions    |Goose (headless mode)           |The worker layer. Each Centurion is a Goose instance running in a containerized Starblaster, connected to the Worldmind via a thin bridge that translates LangGraph4j state into Goose instructions and Goose output back into state updates.      |
|**Tool**          |Nova Force    |MCP Servers                     |Provides capabilities to Centurions: filesystem access, git operations, shell execution, database queries, code search. Each MCP server is a Force channel. Goose connects to these natively as an MCP client.                                  |
|**Infrastructure**|Starblasters     |Docker / Kubernetes / cf        |Container lifecycle management. Spins up isolated environments for each Centurion deployment. Manages resource limits, volume mounts, network isolation, and cleanup.                                                                           |

### 2.2 System Flow Overview

The end-to-end flow for a typical mission proceeds through these stages:

1. **Request Intake:** The operator submits a natural language request via the Dispatch CLI or API. Example: *"Add a REST endpoint for user profiles with validation and tests."*
1. **Upload (Context Gathering):** Worldmind Core invokes Centurion Pulse (research agent) to scan the project via Force::Terrain and Force::Chronicle MCP servers. Project structure, conventions, dependencies, and relevant source files are assembled into the Xandarian Archive (shared state).
1. **Mission Planning:** Centurion Prime analyzes the request against the gathered context and produces a Mission: an ordered set of Directives with agent assignments, dependencies, quality gates, and failure strategies.
1. **Approval:** In Interactive mode, the Mission is presented to the operator via Dispatch for review and approval. In Supervised mode, it auto-approves and proceeds. The operator can edit, reorder, or reject directives.
1. **Centurion Deployment:** For each eligible Directive, Worldmind opens a Starblaster (spins up a Docker container), provisions it with the project volume and appropriate Force channels, and deploys a Goose instance configured for the specific task.
1. **Execution:** The Goose Centurion executes its Directive autonomously within the Starblaster, using MCP tools to read files, write code, run commands, and self-correct. Its output is captured and written back to the Xandarian Archive.
1. **Quality Gate (Seal of Approval):** After each Directive completes, the Worldmind evaluates the quality gate. For code generation, this typically means routing to Centurion Vigil (reviewer) or Centurion Gauntlet (tester). If the Seal is denied, the Worldmind routes feedback back to the originating Centurion for revision.
1. **Convergence:** When all Directives are fulfilled and all Seals granted, Worldmind marks the Mission complete, commits the changes via Force::Chronicle, and reports results to the operator via Dispatch.

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
│  │ Classify  │──▶│ Upload   │──▶│ Centurion Prime   │               │
│  │ Request   │   │ Context  │   │ (Plan Mission)    │               │
│  └──────────┘   └──────────┘   └─────────┬─────────┘               │
│                                           │                          │
│                                    ┌──────▼──────┐                   │
│                                    │  Execute     │                   │
│                                    │  Directives  │  ◀── Parallel    │
│                                    │  (Fan-Out)   │      Fan-Out     │
│                                    └──┬───┬───┬──┘                   │
│                                       │   │   │                      │
│  ┌─────────────┐  ┌─────────────┐  ┌─┴───┴───┴──────────────┐      │
│  │  Evaluate   │◀─│  Dispatch   │◀─│  Parallel Branches      │      │
│  │  Seal       │  │  Centurion  │  │  (1 per Directive)      │      │
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
│                   STARBLASTERS (Infrastructure)                       │
│                   Docker (dev) / Kubernetes (prod)                    │
│                                                                      │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐                 │
│  │  Starblaster A  │  │  Starblaster B  │  │  Starblaster C  │                │
│  │ ┌─────────┐ │  │ ┌─────────┐ │  │ ┌─────────┐ │                │
│  │ │  Goose  │ │  │ │  Goose  │ │  │ │  Goose  │ │                │
│  │ │ (Forge) │ │  │ │ (Vigil) │ │  │ │(Gauntlet)│ │                │
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
- **Java 21 virtual threads:** Parallel Directive execution leverages Java 21 virtual threads for efficient I/O-bound concurrency when managing multiple Goose Starblasters simultaneously.
- **Time-travel debugging:** The checkpointing system enables replay of any execution from any prior state, which is invaluable for debugging failed Missions.

### 3.2 State Schema: The Xandarian Archive

The Xandarian Archive is the `AgentState` subclass that serves as LangGraph4j's shared state. Every node in the graph reads from and writes to this schema. At the API boundary, state is projected into Java records for type-safe access.

|Field               |Type                            |Description                                                                                                               |
|--------------------|--------------------------------|--------------------------------------------------------------------------------------------------------------------------|
|`mission_id`        |`String`                        |Unique identifier. Format: `WMND-YYYY-NNNN`.                                                                              |
|`request`           |`String`                        |Original operator request, verbatim.                                                                                      |
|`classification`    |`Classification` (record)       |Request category, complexity (1–5), affected components, planning strategy.                                               |
|`context`           |`ProjectContext` (record)       |Gathered project metadata: file tree, conventions, dependencies, relevant source excerpts, git state.                     |
|`directives`        |`List<Directive>` (record list) |Ordered list of all Directives with their current status, results, and iteration counts.                                  |
|`active_starblasters`  |`Map<String, StarblasterInfo>`     |Registry of currently running containers: Starblaster ID, Centurion type, Directive ID, resource usage.                      |
|`files_created`     |`List<FileRecord>` (record list)|All files created or modified during the Mission, with paths, content hashes, and originating Directive.                  |
|`test_results`      |`List<TestResult>` (record list)|Aggregated test execution results across all Directives.                                                                  |
|`review_feedback`   |`List<ReviewFeedback>`          |Accumulated review feedback from Centurion Vigil, keyed by Directive and iteration.                                       |
|`execution_strategy`|`ExecutionStrategy` (enum)      |`LINEAR` | `PARALLEL` | `ITERATIVE`. Determines how the graph routes Directives.                                          |
|`interaction_mode`  |`InteractionMode` (enum)        |`INTERACTIVE` | `SUPERVISED` | `AUTONOMOUS`. Controls operator involvement.                                                   |
|`status`            |`MissionStatus` (enum)          |`UPLOADING` | `PLANNING` | `AWAITING_APPROVAL` | `EXECUTING` | `REPLANNING` | `COMPLETED` | `FAILED` | `CANCELLED`.       |
|`metrics`           |`MissionMetrics` (record)       |Token usage, elapsed time per Directive, retry counts, quality gate pass rates.                                           |
|`created_at`        |`Instant`                       |Mission creation timestamp (ISO 8601).                                                                                    |
|`updated_at`        |`Instant`                       |Last state modification timestamp.                                                                                        |

### 3.3 Graph Topology

The LangGraph4j StateGraph defines the following node topology. Each node is a Java method (or lambda implementing `NodeAction<WorldmindState>`) that receives the Xandarian Archive, performs work, and returns a partial state update as `Map<String, Object>`.

1. **`classify_request`** — Receives the raw request. Uses Spring AI's ChatClient with `BeanOutputConverter` to produce a `Classification` record (category, complexity, affected components). Routes to `upload_context`.
1. **`upload_context`** — Invokes Centurion Pulse via a Goose Starblaster to scan the project. Pulse connects to Force::Terrain and Force::Chronicle to gather structure, conventions, and relevant source files. Writes `ProjectContext` to the Archive. Routes to `plan_mission`.
1. **`plan_mission`** — Centurion Prime node. Uses the Classification and ProjectContext with Spring AI structured output to generate a Mission with ordered Directives. This is the most complex LLM call in the system. Routes to `await_approval` (Interactive) or `execute_directives` (Supervised/Autonomous).
1. **`await_approval`** — Pauses execution and emits the Mission to the Dispatch interface. Waits for operator input (approve, edit, reject). On approval, routes to `execute_directives`. On edit, routes back to `plan_mission` with modifications. On reject, routes to `mission_cancelled`.
1. **`execute_directives`** — The fan-out node. Spawns one execution branch per eligible Directive (respecting dependency ordering). Each branch targets the `dispatch_centurion` node with the Directive's localized state.
1. **`dispatch_centurion`** — Opens a Starblaster for the Directive's assigned Centurion type. Translates the Directive's input spec into a Goose instruction set. Launches the Goose instance and waits for completion. Captures output and writes results to the Archive.
1. **`evaluate_seal`** — Quality gate evaluation. Checks the Directive's completion criteria (tests pass, lint clean, review approved). If the Seal is granted, marks the Directive as fulfilled. If denied, applies the Directive's `on_failure` strategy (retry, replan, escalate, skip).
1. **`converge_results`** — Aggregation node. Runs after all Directive branches complete. Merges file changes, test results, and review feedback. Performs final validation. Routes to `mission_complete` or `mission_failed`.
1. **`replan`** — Triggered when `evaluate_seal` determines a replan is needed. Centurion Prime re-evaluates remaining Directives in light of the failure and generates a revised Mission segment. Routes back to `execute_directives`.

### 3.4 Conditional Routing Logic

The graph's conditional edges encode the decision logic that makes the system deterministic rather than relying on LLM judgment for routing:

|From Node         |To Node             |Condition                                                         |
|------------------|--------------------|------------------------------------------------------------------|
|`plan_mission`    |`await_approval`    |`interaction_mode == INTERACTIVE`                                 |
|`plan_mission`    |`execute_directives`|`interaction_mode in [SUPERVISED, AUTONOMOUS]`                    |
|`evaluate_seal`   |`dispatch_centurion`|`seal == DENIED AND on_failure == RETRY AND retries < max_retries`|
|`evaluate_seal`   |`replan`            |`seal == DENIED AND on_failure == REPLAN`                         |
|`evaluate_seal`   |`await_approval`    |`seal == DENIED AND on_failure == ESCALATE`                       |
|`evaluate_seal`   |`converge_results`  |`seal == DENIED AND on_failure == SKIP`                           |
|`evaluate_seal`   |`converge_results`  |`seal == GRANTED AND all_directives_resolved`                     |
|`evaluate_seal`   |*(wait)*            |`seal == GRANTED AND pending_directives_exist`                    |
|`converge_results`|`mission_complete`  |All quality gates passed                                          |
|`converge_results`|`replan`            |Integration failures detected                                     |

### 3.5 Persistence and Recovery

The Worldmind uses LangGraph4j's `PostgresSaver` to persist the Xandarian Archive after every graph node execution:

- **Crash recovery:** If the Worldmind server crashes or restarts during a Mission, the graph resumes from the last checkpointed state. Completed Directives are not re-executed. In-progress Starblasters are detected and either re-attached or re-provisioned.
- **Time-travel debugging:** Operators can inspect the Archive at any prior checkpoint, replaying the decision path that led to a failure. This is exposed via the Dispatch API as a mission timeline view.
- **Long-running mission support:** Complex Missions may run for hours. The checkpointing system ensures that progress is never lost, even across server deployments or scaling events.

-----

## 4. Worker Plane: Goose Centurions

Each Centurion is a Goose instance running in headless mode inside a containerized Starblaster. Goose was selected as the worker engine because of its native MCP integration, robust filesystem capabilities, and autonomous self-correction loops.

### 4.1 Why Goose

Framework comparison research identified Goose's strengths and limitations clearly. Its strengths align perfectly with the worker role:

- **MCP-native:** Goose is built as an MCP super-client. It connects natively to MCP servers for filesystem, git, shell, database, and search operations. Centurions inherit the full Nova Force capability set without any adapter code.
- **Developer-centric autonomy:** Goose behaves like a developer at a terminal. It reads files, writes code, runs builds, reads error output, and self-corrects. This internal reasoning loop is exactly what a Centurion needs to execute a Directive autonomously.
- **Self-correcting build loops:** When a command fails (e.g., a build error or test failure), Goose reads the error output, reasons about the fix, and retries. This happens inside the Goose instance's own context, before the result ever reaches the Worldmind's quality gate. This reduces the number of outer-loop retries significantly.
- **Sandboxed execution:** Running Goose inside a Docker container (Starblaster) provides natural isolation. Each Centurion operates on a mounted project volume with controlled network access and resource limits.

Its known limitations are mitigated by the hybrid architecture:

- **No native API** *(mitigated)*: Goose is CLI-first. The Starblaster Bridge wraps the Goose CLI process, translating LangGraph4j state into Goose instructions and capturing structured output.
- **No recursion** *(mitigated)*: Goose subagents cannot spawn their own subagents. This is irrelevant because all task decomposition happens in the LangGraph4j control plane. Goose instances are workers, not orchestrators.
- **Session-based state** *(mitigated)*: Goose does not persist state across sessions. Each Centurion deployment handles a single Directive. All persistent state lives in the Xandarian Archive, managed by LangGraph4j.

### 4.2 Centurion Roster

|Centurion             |Rank      |Goose Config                                  |Force Channels                  |Behavioral Profile                                                                                                                                            |
|----------------------|----------|----------------------------------------------|--------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------|
|**Centurion Prime**   |Nova Prime|Planner mode (runs in LangGraph4j, not Goose) |Terrain (read), Chronicle (read)|Request classification, context analysis, mission planning, execution management. Operates within the control plane, not in a Starblaster.                       |
|**Centurion Pulse**   |Millennian|Research mode                                 |Terrain, Chronicle, Signal      |Read-only. Scans project structure, reads source files, searches documentation. Produces structured context summaries. Never writes files.                    |
|**Centurion Forge**   |Centurion |Code-gen mode                                 |Terrain, Chronicle, Spark       |Read-write. Generates new code, creates files, modifies existing files. Runs build commands to validate. Uses self-correction loops on build failures.        |
|**Centurion Vigil**   |Centurion |Review mode                                   |Terrain, Chronicle, Signal      |Read-only. Analyzes code for quality, security, consistency, and correctness. Produces structured review feedback. Grants or denies the Seal of Approval.     |
|**Centurion Gauntlet**|Centurion |Test mode                                     |Terrain, Spark                  |Read-write (test files only). Generates tests following project conventions. Executes test suites. Reports pass/fail with coverage metrics.                   |
|**Centurion Prism**   |Centurion |Refactor mode                                 |Terrain, Chronicle, Spark       |Read-write. Restructures code without changing behavior. Runs existing tests before and after to verify behavioral equivalence.                               |

### 4.3 The Starblaster Bridge

The Starblaster Bridge is the critical integration layer between LangGraph4j and Goose. It translates between the two systems:

1. **Directive → Goose instruction:** The bridge takes a `Directive` record from the Xandarian Archive and constructs a Goose instruction file. This file includes the task description, relevant context (file excerpts, conventions, constraints), specific instructions, and success criteria. The instruction is written to a file inside the Starblaster volume.
1. **Goose launch:** The bridge executes `goose run --instructions directive.md` inside the Starblaster container. The Goose process runs headlessly, connecting to the pre-configured MCP servers (Force channels) available inside the container.
1. **Output capture:** The bridge monitors the Goose process via stdout (JSON-structured output mode). It captures: files created/modified, commands executed and their exit codes, errors encountered and self-corrections applied, and the final completion status.
1. **Result → Archive:** The bridge translates the captured output into a `DirectiveResult` record and writes it back to the Xandarian Archive. This includes file records (paths + content hashes), command logs, and a success/failure determination.

> **Bridge Design Principle**
>
> The Starblaster Bridge should be *thin*. It translates between LangGraph4j state and Goose instructions, and between Goose output and LangGraph4j state. It should not contain business logic, routing decisions, or quality evaluation. Those responsibilities belong to the control plane.

### 4.4 MCP Permission Matrix

Each Centurion type receives a tailored MCP server configuration inside its Starblaster. This enforces the principle of least privilege.

|Force Channel            |Pulse|Forge      |Vigil|Gauntlet              |Prism      |
|-------------------------|-----|-----------|-----|----------------------|-----------|
|**Terrain** (Filesystem) |Read |Read/Write |Read |Read/Write (test dirs)|Read/Write |
|**Chronicle** (Git)      |Read |Read/Commit|Read |—                     |Read/Commit|
|**Spark** (Shell)        |—    |Full       |—    |Test commands only    |Full       |
|**Signal** (Search)      |Full |—          |Full |—                     |—          |
|**Archive** (Database)   |Read |—          |—    |—                     |—          |
|**Lattice** (Lang Server)|—    |Diagnostics|Full |—                     |Diagnostics|

-----

## 5. Tool Layer: Nova Force (MCP Servers)

The Nova Force is the collection of MCP servers that provide capabilities to the Centurion agents. Each MCP server runs as an independent process (or container) and exposes tools via the Model Context Protocol. Goose connects to these natively as an MCP client. The Worldmind itself can also leverage Spring AI's MCP client support for control-plane operations.

### 5.1 Force Channel Specifications

|Channel             |Implementation        |Key Tools Exposed                                                           |Deployment Model                                                                                                            |
|--------------------|----------------------|----------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------|
|**Force::Terrain**  |MCP Filesystem Server |`read_file`, `write_file`, `list_directory`, `search_files`, `file_exists`  |Sidecar container per Starblaster, mounted to the project volume. Path restrictions enforced per Centurion type.               |
|**Force::Chronicle**|MCP Git Server        |`git_status`, `git_diff`, `git_log`, `git_blame`, `git_commit`, `git_branch`|Shared service. Single instance per project. Write operations gated by Centurion type.                                      |
|**Force::Spark**    |MCP Shell Server      |`run_command`, `run_script`, `get_env`, `list_processes`                    |Sidecar container per Starblaster. Command allowlisting enforced per Centurion type.                                           |
|**Force::Archive**  |gp-mcp-server (custom)|`query`, `describe_table`, `list_schemas`, `explain_plan`                   |Shared service. Connection pooling, query validation, and parameterized queries per the gp-mcp-server security architecture.|
|**Force::Signal**   |MCP RAG/Search Server |`search_codebase`, `search_docs`, `semantic_search`, `get_definition`       |Shared service. Indexes the project codebase and documentation. Updated incrementally as Centurions modify files.           |
|**Force::Lattice**  |MCP Language Server   |`get_diagnostics`, `get_completions`, `find_references`, `get_symbols`      |Per-language sidecar. Python LSP for Python projects, TypeScript LSP for TS projects.                                       |

### 5.2 Security Model

The Nova Force security model follows the principle of least privilege at multiple levels:

- **Centurion-level restrictions:** Each Centurion type has a defined set of Force channels and permission levels (read-only, read-write, restricted-write). These are enforced in the Goose MCP configuration injected by the Starblaster Bridge.
- **Starblaster-level isolation:** Each Starblaster container has its own network namespace, filesystem mount, and resource limits. Centurions cannot access other Centurions' Starblasters or the host system.
- **Force channel authentication:** MCP servers require authentication tokens issued by the Worldmind. Tokens are scoped to the specific Mission and Directive, with expiration times matching the expected Directive duration.
- **Command allowlisting:** Force::Spark (shell execution) uses an allowlist model. Only approved commands (build tools, test runners, linters) are permitted. The allowlist is configurable per project and per Centurion type.
- **Write path restrictions:** Force::Terrain (filesystem) enforces path-based write restrictions. Centurion Gauntlet can only write to test directories. Centurion Vigil cannot write at all. These restrictions are enforced at the MCP server level, not just in the Goose configuration.

-----

## 6. Infrastructure Layer: Starblasters

Starblasters are the containerized execution environments in which Centurions operate. The Starblaster layer manages the full container lifecycle: provisioning, configuration, monitoring, and cleanup.

### 6.1 Starblaster Lifecycle

1. **Provision:** When the Worldmind dispatches a Directive, the Starblaster manager (a Spring-managed `@Service`) selects the appropriate container image (based on Centurion type and project language), provisions it with the project volume mount, injects the MCP server configuration, and starts the container via the Docker Java client.
1. **Configure:** The Starblaster Bridge writes the Goose instruction file and MCP configuration to the container volume. Environment variables are set for the Goose process: model selection, token limits, and connection strings for the Force channel MCP servers.
1. **Execute:** The Goose process runs inside the container. The Bridge monitors it via stdout and process health checks. Resource usage (CPU, memory, disk) is tracked and reported to the Xandarian Archive.
1. **Capture:** On Goose process completion, the Bridge captures the final state: files modified on the volume, exit code, structured output logs, and any error information.
1. **Teardown:** After result capture, the Starblaster is torn down. The container is stopped and removed. Volume data (file modifications) has already been committed to the shared project volume. Temporary artifacts are cleaned up.

### 6.2 Container Images

Starblaster container images are purpose-built for each Centurion type and project language combination:

- **Base image:** A Goose-ready base with the Goose CLI, MCP client libraries, and common development tools pre-installed.
- **Language layers:** Stacked on top of the base — Python (with pip, poetry, pytest), Node.js (with npm, jest), Java (with Maven/Gradle, JUnit), Go (with go test), etc. Multiple language layers can be combined for polyglot projects.
- **Centurion profile:** A lightweight configuration layer that sets the Goose system prompt, MCP server permissions, and behavioral constraints for the specific Centurion type.

### 6.3 Kubernetes Deployment (Production)

In production, Starblasters run as Kubernetes Jobs:

- **Job per Directive:** Each Directive dispatches a K8s Job with the appropriate container image, resource requests/limits, and volume claims.
- **Project volumes:** The project codebase is mounted via a PersistentVolumeClaim (PVC) shared across all Starblasters in a Mission. This ensures all Centurions see each other's file modifications. Concurrent write conflicts are managed by the Worldmind's dependency ordering (parallel Directives operate on non-overlapping files).
- **Resource scaling:** Complex Missions with many parallel Directives can scale horizontally across K8s nodes. The Starblaster manager respects resource quotas and can queue Directives when capacity is constrained.
- **Observability:** Container logs are forwarded to the Worldmind's event stream. K8s metrics (CPU, memory, disk I/O) are collected per Starblaster and surfaced in the Dispatch mission dashboard.

### 6.4 Development Mode (Docker)

For local development, Starblasters run as Docker containers:

```bash
# Starblaster spin-up (simplified)
docker run -d \
  --name starblaster-forge-001 \
  -v /path/to/project:/workspace \
  -v /tmp/directive-001.md:/instructions/directive.md \
  -e GOOSE_MODEL=claude-sonnet-4-5-20250929 \
  -e MCP_TERRAIN_URL=http://host.docker.internal:3001 \
  -e MCP_CHRONICLE_URL=http://host.docker.internal:3002 \
  -e MCP_SPARK_URL=http://host.docker.internal:3003 \
  --memory=4g --cpus=2 \
  worldmind/centurion-forge:latest \
  goose run --instructions /instructions/directive.md
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
|`worldmind status <mission-id>`                |Show current Mission status with Directive progress.      |
|`worldmind status --watch <mission-id>`        |Stream real-time Mission events to the terminal.          |
|`worldmind log <mission-id>`                   |Show the complete Mission execution log.                  |
|`worldmind timeline <mission-id>`              |Show the checkpointed state timeline (time-travel view).  |
|`worldmind cancel <mission-id>`                |Cancel a running Mission. Tears down active Starblasters.    |
|`worldmind history`                            |List recent Missions with status and summary.             |
|`worldmind inspect <mission-id> <directive-id>`|Show detailed output from a specific Centurion deployment.|
|`worldmind force-channels`                     |List available Nova Force channels and their status.      |
|`worldmind centurions`                         |Show the Centurion roster with availability.              |

#### Example Session

```
$ worldmind mission "Add a REST endpoint for user profiles with validation"

✶ WORLDMIND v1.0
──────────────────────────────────
Uploading project context... 47 files indexed.

MISSION WMND-2026-0042
Objective: Add REST endpoint for user profiles with input validation
Strategy: Iterative (max 3 cycles)

DIRECTIVES:
  1. [PULSE]    Reconnaissance — scan project patterns
  2. [FORGE]    Implement UserProfileController
  3. [FORGE]    Implement UserProfileService
  4. [GAUNTLET] Write unit + integration tests
  5. [VIGIL]    Final code review

Proceed? [Y/n/edit] y

Deploying centurions...

  ✓ Directive 1  [PULSE]     Reconnaissance complete        4.2s
  ✓ Directive 2  [FORGE]     UserProfileController created   8.7s
  ✓ Directive 3  [FORGE]     UserProfileService created      6.3s
  ○ Directive 4  [GAUNTLET]  Running tests...
  ─ Directive 5  [VIGIL]     Awaiting dependencies

  ✓ Directive 4  [GAUNTLET]  12 tests passing, 94% coverage  5.1s
  ✓ Directive 5  [VIGIL]     Seal of Approval granted        7.8s

Mission WMND-2026-0042 complete.
5 files created, 2 modified, 12 tests passing.
Changes committed to branch: worldmind/WMND-2026-0042
```

### 7.2 Dispatch REST API

|Method|Endpoint                                |Description                                                      |
|------|----------------------------------------|-----------------------------------------------------------------|
|`POST`|`/api/v1/missions`                      |Submit a new Mission. Body: `{ request, mode, project_path }`.   |
|`GET` |`/api/v1/missions/{id}`                 |Get Mission status including all Directive statuses.             |
|`GET` |`/api/v1/missions/{id}/events`          |SSE stream of real-time Mission events.                          |
|`POST`|`/api/v1/missions/{id}/approve`         |Approve a Mission plan (Interactive mode).                       |
|`POST`|`/api/v1/missions/{id}/edit`            |Submit plan modifications (Interactive mode).                    |
|`POST`|`/api/v1/missions/{id}/cancel`          |Cancel a running Mission.                                        |
|`GET` |`/api/v1/missions/{id}/timeline`        |Get checkpointed state history.                                  |
|`GET` |`/api/v1/missions/{id}/directives/{did}`|Get detailed Directive result.                                   |
|`GET` |`/api/v1/starblasters`                     |List active Starblasters with resource usage.                       |
|`GET` |`/api/v1/health`                        |System health: Worldmind Core, Force channels, Starblaster capacity.|

### 7.3 API Response Format

```json
{
  "mission_id": "WMND-2026-0042",
  "status": "executing",
  "objective": "Add REST endpoint for user profiles",
  "strategy": "iterative",
  "directives": [
    {
      "id": "directive_001",
      "centurion": "pulse",
      "description": "Reconnaissance",
      "status": "fulfilled",
      "seal": "approved",
      "elapsed_ms": 4200
    },
    {
      "id": "directive_002",
      "centurion": "forge",
      "description": "Implement UserProfileController",
      "status": "running",
      "seal": null,
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

The Worldmind supports three execution patterns, selected by Centurion Prime based on the request classification and Mission structure.

### 8.1 Linear Execution

Directives execute sequentially, each waiting for the prior to complete. Used for simple, dependency-heavy tasks where each step depends on the output of the previous one.

```
[Pulse] ──▶ [Forge] ──▶ [Gauntlet] ──▶ [Vigil] ──▶ Complete
```

**Example:** A bug fix where the fix depends on research, the test depends on the fix, and the review depends on both.

### 8.2 Parallel Fan-Out

Multiple Directives execute concurrently when they share no dependencies. This is the primary pattern for feature implementation where multiple files can be generated independently. Fan-out uses LangGraph4j's parallel branch execution with `CompletableFuture` and Java 21 virtual threads.

```
                  ┌──▶ [Forge: Controller] ──┐
                  │                           │
[Pulse] ──▶ Fan-Out──▶ [Forge: Service]    ──┼──▶ [Gauntlet] ──▶ [Vigil]
                  │                           │
                  └──▶ [Forge: Repository] ──┘
```

The fan-out mechanism enables true dynamic spawning: if Centurion Prime plans 3 parallel Directives, the graph fans out to 3 concurrent Goose Starblasters. If it plans 8, it fans to 8. The topology is determined at runtime by the planner's output, not by the graph definition.

### 8.3 Iterative Cycles (Build-Test-Fix Loop)

The most critical pattern for code quality. After code generation, the output flows to review or testing. If the quality gate fails, the graph cycles back to the originating Centurion with accumulated feedback.

```
         ┌──────────────────────────────┐
         │                              │
         ▼                              │
[Forge] ──▶ [Gauntlet] ──▶ Seal? ──NO──┘
                              │
                             YES
                              │
                              ▼
                          [Vigil] ──▶ Complete
```

Key controls for iterative cycles:

- **`max_iterations`:** Default 3. Configurable per Directive. Prevents infinite loops.
- **Cumulative feedback:** Each iteration includes all prior feedback, preventing the Centurion from repeating previously identified mistakes.
- **Oscillation detection:** The Worldmind monitors for alternating patterns (Centurion alternates between two approaches) and escalates to the operator early rather than exhausting the iteration budget.
- **Progressive escalation:** First failure → retry with feedback. Second failure → retry with stronger constraints. Third failure → escalate to operator with full context.

-----

## 9. Observability and Metrics

### 9.1 Structured Event Stream

The Worldmind emits structured events at every significant state transition. These events power the Dispatch CLI progress display, the API's SSE stream, and operational monitoring.

|Event                |Payload                                                  |Consumer                            |
|---------------------|---------------------------------------------------------|------------------------------------|
|`mission.created`    |Full Mission plan, operator request, classification      |Dispatch (display plan), audit log  |
|`mission.approved`   |Mission ID, approval source (operator/auto)              |Dispatch, audit log                 |
|`starblaster.opened`    |Starblaster ID, Centurion type, Directive ID, container info|Dispatch (progress), resource monitor|
|`directive.started`  |Directive ID, Centurion name, input summary              |Dispatch (progress updates)         |
|`directive.progress` |Intermediate output: files created, commands run         |Dispatch (live feed), debugging     |
|`directive.fulfilled`|Result summary, files modified, quality gate outcome     |Dispatch, metrics                   |
|`seal.denied`        |Directive ID, failure details, `on_failure` action       |Dispatch (alert), audit log         |
|`mission.replanned`  |Revised Directive list, reason for replan                |Dispatch, audit log                 |
|`mission.completed`  |Final summary, all results, total metrics                |Dispatch, metrics, audit log        |
|`mission.escalated`  |Reason, context, operator options                        |Dispatch (notification)             |

### 9.2 Log Format

All system logs use the Worldmind vocabulary consistently:

```
[WORLDMIND]        Initializing Xandarian Archive...
[WORLDMIND]        Nova Force channels online: Terrain, Chronicle, Spark
[WORLDMIND]        Centurion roster loaded: Prime, Forge, Vigil, Gauntlet, Prism, Pulse
[DISPATCH]         Mission request received: "Add user profile endpoint"
[WORLDMIND]        Uploading project context... 47 files indexed.
[CENTURION PRIME]  Mission planned: 5 directives, 3 centurions assigned.
[STARBLASTER]         Opening for Centurion Forge...
[CENTURION FORGE]  Directive 002: Implement UserProfileController.
[FORCE::TERRAIN]   3 files created.
[CENTURION FORGE]  Directive 002 complete. 3 files, 187 lines.
[CENTURION VIGIL]  Seal of Approval granted.
[WORLDMIND]        Mission WMND-2026-0042 complete. All directives fulfilled.
```

### 9.3 Metrics

The following metrics are collected per Mission and aggregated for system-wide monitoring, exposed via Spring Boot Actuator and Micrometer:

- **Planning latency:** Time from request intake to Mission plan approval.
- **Execution time:** Total wall-clock time and per-Directive breakdown.
- **Token consumption:** LLM tokens used, broken down by Centurion Prime (planning), Centurion workers (execution), and quality gate evaluation.
- **Seal of Approval rate:** Percentage of Directives that pass their quality gate on first attempt.
- **Iteration depth:** Average and max number of build-test-fix cycles per Mission.
- **Starblaster utilization:** Container count, resource usage, queue depth, average Starblaster lifetime.
- **Escalation rate:** Percentage of Missions requiring operator intervention, categorized by reason.

-----

## 10. Performance Requirements

|Metric                                   |Target  |Notes                                                                          |
|-----------------------------------------|--------|-------------------------------------------------------------------------------|
|Time to first plan (complexity 1–2)      |< 10s   |Includes classification and minimal context upload.                            |
|Time to first plan (complexity 3–5)      |< 30s   |Includes full context upload and multi-step planning.                          |
|Starblaster spin-up time                    |< 5s    |From `dispatch_centurion` to Goose process running. Requires pre-pulled images.|
|Directive execution (simple file gen)    |< 30s   |Single file generation with validation.                                        |
|Directive execution (complex multi-file) |< 3 min |Multiple files with build verification.                                        |
|Full Mission (complexity 3, 5 Directives)|< 10 min|End-to-end including planning, execution, and review cycles.                   |
|Checkpoint write latency                 |< 100ms |Postgres write per super-step. Must not bottleneck graph execution.            |
|Event stream latency                     |< 500ms |From state transition to Dispatch UI update.                                   |
|Concurrent Missions per instance         |5 (v1)  |Scaling horizontally via K8s replicas for higher throughput.                   |
|Max parallel Starblasters per Mission       |10 (v1) |Configurable. Limited by K8s resource quotas.                                  |

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
|**Container Runtime**|Docker (dev) / Kubernetes (prod)            |Standard container orchestration. K8s Jobs map perfectly to the Starblaster lifecycle.                                               |
|**Docker Client**    |Docker Java (com.github.docker-java)        |Mature Java client for Docker Engine API. Starblaster lifecycle management.                                                          |
|**CLI Interface**    |picocli                                     |Modern Java CLI framework. ANSI color support, subcommands, tab completion. Fast startup with GraalVM native image.               |
|**Build Tool**       |Maven                                       |Standard Java build tool. BOM support for Spring AI and LangGraph4j dependency management.                                        |
|**MCP Servers**      |Per-channel (see Force specs)               |Standard MCP protocol. Mix of community servers and custom implementations.                                                       |
|**LLM Provider**     |Anthropic Claude (via Spring AI)            |Centurion Prime uses Claude Sonnet 4.5 for planning. Workers use configurable models via Goose.                                   |
|**Observability**    |Micrometer + Spring Boot Actuator           |Events emitted as structured JSON. Prometheus-compatible metrics via Actuator endpoints.                                          |

-----

## 12. Implementation Roadmap

### Phase 1: Foundation (Weeks 1–4)

- [ ] Spring Boot project with LangGraph4j StateGraph: `classify_request`, `plan_mission`, `execute_directives` nodes
- [ ] Xandarian Archive schema (Java records for Mission, Directive, state fields; AgentState for graph)
- [ ] PostgresSaver integration for state persistence
- [ ] Single Centurion type: Centurion Forge (code generator) running Goose in Docker
- [ ] Starblaster Bridge: Directive-to-Goose-instruction translation, output capture
- [ ] Force::Terrain (Filesystem MCP) server configured for Goose
- [ ] Basic Dispatch CLI: `worldmind mission`, `worldmind status`

### Phase 2: The Loop (Weeks 5–8)

- [ ] Add Centurion Vigil (reviewer) and Centurion Gauntlet (tester)
- [ ] Implement the iterative build-test-fix cycle with conditional routing
- [ ] Quality gate evaluation (`evaluate_seal` node)
- [ ] Force::Spark (Shell MCP) for build and test execution
- [ ] Force::Chronicle (Git MCP) for branch management and commits
- [ ] Retry, replan, and escalate failure strategies

### Phase 3: Scale (Weeks 9–12)

- [ ] Parallel Directive execution via fan-out with virtual threads
- [ ] Add Centurion Pulse (research) and Centurion Prism (refactorer)
- [ ] Kubernetes deployment with K8s Jobs for Starblasters
- [ ] Full Dispatch CLI with `--watch` mode, `timeline`, and `inspect` commands
- [ ] Dispatch REST API with SSE event streaming
- [ ] Observability: structured events, Micrometer metrics

### Phase 4: Polish (Weeks 13–16)

- [ ] Force::Signal (RAG/Search MCP) for codebase-aware planning
- [ ] Force::Lattice (Language Server MCP) for static analysis augmentation
- [ ] Oscillation detection and progressive escalation in iterative cycles
- [ ] Multi-language Starblaster images (Python, Node, Java, Go)
- [ ] Security hardening: token-scoped MCP auth, command allowlisting, path restrictions
- [ ] Performance optimization: image pre-pulling, warm Starblaster pools, context budget tuning

-----

## 13. Future Considerations

- **Dynamic Centurion registration:** Allow new Centurion types to be registered at runtime via a capability manifest. Centurion Prime would discover available Centurions and incorporate them into planning without code changes.
- **Multi-project Missions:** Support Missions that span multiple repositories or services, with cross-project dependency tracking and coordinated commits.
- **Learning from outcomes:** Track which planning strategies succeed for which request types. Use this history to improve Centurion Prime's planning over time.
- **Web UI:** A browser-based Dispatch dashboard for Mission visualization, real-time Centurion monitoring, and interactive plan editing. Built as a separate frontend consuming the Dispatch REST API.
- **CI/CD integration:** Trigger Missions from CI/CD pipelines (e.g., "fix this failing test" triggered by a GitHub Actions failure). Centurions commit fixes directly to branches for review.
- **Worker engine alternatives:** The Starblaster Bridge is designed to be worker-agnostic. Future Centurion implementations could use Claude Code, Aider, or custom LLM agents instead of Goose, selected per Centurion type based on task requirements.

-----

## 14. Nomenclature Reference

|Technical Concept   |Worldmind Name       |Description                                                      |
|--------------------|---------------------|-----------------------------------------------------------------|
|Orchestration Server|**Worldmind**        |The LangGraph4j control plane that plans and manages execution.  |
|CLI / API Interface |**Dispatch**         |The operator's interface. Dispatches missions to the Worldmind.  |
|Specialist Agents   |**Centurions**       |Goose worker instances dispatched to execute Directives.         |
|Planner Agent       |**Centurion Prime**  |The lead Centurion responsible for mission planning.             |
|Agent Containers    |**Starblasters**        |Docker/K8s environments in which Centurions operate.             |
|Execution Plans     |**Missions**         |Structured plans with ordered Directives.                        |
|Plan Steps          |**Directives**       |Individual tasks assigned to specific Centurions.                |
|MCP Servers / Tools |**Nova Force**       |The MCP tool layer. Each server is a Force channel.              |
|Shared State        |**Xandarian Archive**|The LangGraph4j state object (Java records + Postgres).          |
|Context Gathering   |**Upload**           |Scanning the codebase to build the context window.               |
|Quality Gates       |**Seal of Approval** |Criteria for Directive success. Granted or denied.               |
|User                |**Operator**         |The human using the system.                                      |

-----

> **Architectural Invariant**
>
> The separation between the LangGraph4j control plane and the Goose worker plane is the system's most important architectural decision. The control plane must never execute code directly. The worker plane must never make routing decisions. Violating this boundary collapses the hybrid model into a monolith and loses the advantages of both frameworks.

-----

*Worldmind — Tell it what to build. It plans the mission, deploys the centurions, and delivers the code.*
