# ✶ WORLDMIND

### Agentic Code Assistant — System Architecture Specification

**The Hybrid Architecture: LangGraph Control Plane + Goose Worker Nodes**

Version 1.0 · February 2026 · Draft

*Inspired by the Xandarian Worldmind — the sentient supercomputer of the Nova Corps*

-----

## Table of Contents

1. [Executive Summary](#1-executive-summary)
1. [Architectural Overview](#2-architectural-overview)
1. [Control Plane: Worldmind Core (LangGraph)](#3-control-plane-worldmind-core-langgraph)
1. [Worker Plane: Goose Centurions](#4-worker-plane-goose-centurions)
1. [Tool Layer: Nova Force (MCP Servers)](#5-tool-layer-nova-force-mcp-servers)
1. [Infrastructure Layer: Stargates](#6-infrastructure-layer-stargates)
1. [Interface Layer: Helm](#7-interface-layer-helm)
1. [Execution Patterns](#8-execution-patterns)
1. [Observability and Metrics](#9-observability-and-metrics)
1. [Performance Requirements](#10-performance-requirements)
1. [Technology Stack](#11-technology-stack)
1. [Implementation Roadmap](#12-implementation-roadmap)
1. [Future Considerations](#13-future-considerations)
1. [Nomenclature Reference](#14-nomenclature-reference)

-----

## 1. Executive Summary

Worldmind is a server-based agentic code assistant that accepts natural language development requests and autonomously plans, implements, tests, and reviews code. It employs a hybrid architecture that combines the deterministic orchestration capabilities of **LangGraph** with the autonomous coding power of **Goose** worker agents.

The system is designed around a clear separation of concerns: the **Worldmind control plane** (built on LangGraph) manages state, planning, routing, persistence, and the software development lifecycle, while **Goose instances** (deployed as Centurion workers inside containerized Stargates) perform the actual code generation, file manipulation, and command execution through their native MCP integration.

This architecture resolves the central tension identified in framework comparison research: LangGraph provides the engineering rigor required for production-grade orchestration (cyclic graphs, per-step checkpointing, the Send API for dynamic fan-out) while Goose provides the developer-centric autonomy required for effective code generation (native filesystem interaction, self-correcting build loops, MCP-native tool access).

> **The Hybrid Principle**
> 
> Worldmind is the nervous system. Goose is the hands. LangGraph decides *what* to do and in *what order*. Goose *does it*. Neither is sufficient alone: LangGraph without Goose lacks developer autonomy; Goose without LangGraph lacks state persistence, deterministic routing, and scalable orchestration.

-----

## 2. Architectural Overview

The system is organized into five layers, each with distinct responsibilities and technology choices. Communication between layers follows defined protocols, with MCP serving as the universal tool interface.

### 2.1 Layer Architecture

|Layer             |Worldmind Name|Technology           |Responsibility                                                                                                                                                                                                                          |
|------------------|--------------|---------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
|**Interface**     |Helm          |FastAPI + CLI (Typer)|Accepts operator requests via REST API or CLI. Streams mission progress events via SSE/WebSocket. Provides mission management (submit, status, cancel, history).                                                                        |
|**Orchestration** |Worldmind Core|LangGraph StateGraph |The control plane. Houses Centurion Prime (planner), the mission state machine, conditional routing logic, the Send API fan-out for parallel directives, and the Postgres-backed checkpointer for state persistence.                    |
|**Agent**         |Centurions    |Goose (headless mode)|The worker layer. Each Centurion is a Goose instance running in a containerized Stargate, connected to the Worldmind via a thin bridge that translates LangGraph state into Goose instructions and Goose output back into state updates.|
|**Tool**          |Nova Force    |MCP Servers          |Provides capabilities to Centurions: filesystem access, git operations, shell execution, database queries, code search. Each MCP server is a Force channel. Goose connects to these natively as an MCP client.                          |
|**Infrastructure**|Stargates     |Docker / Kubernetes  |Container lifecycle management. Spins up isolated environments for each Centurion deployment. Manages resource limits, volume mounts, network isolation, and cleanup.                                                                   |

### 2.2 System Flow Overview

The end-to-end flow for a typical mission proceeds through these stages:

1. **Request Intake:** The operator submits a natural language request via the Helm CLI or API. Example: *“Add a REST endpoint for user profiles with validation and tests.”*
1. **Upload (Context Gathering):** Worldmind Core invokes Centurion Pulse (research agent) to scan the project via Force::Terrain and Force::Chronicle MCP servers. Project structure, conventions, dependencies, and relevant source files are assembled into the Xandarian Archive (shared state).
1. **Mission Planning:** Centurion Prime analyzes the request against the gathered context and produces a Mission: an ordered set of Directives with agent assignments, dependencies, quality gates, and failure strategies.
1. **Approval:** In Interactive mode, the Mission is presented to the operator via Helm for review and approval. In Supervised mode, it auto-approves and proceeds. The operator can edit, reorder, or reject directives.
1. **Centurion Deployment:** For each eligible Directive, Worldmind opens a Stargate (spins up a Docker container), provisions it with the project volume and appropriate Force channels, and deploys a Goose instance configured for the specific task.
1. **Execution:** The Goose Centurion executes its Directive autonomously within the Stargate, using MCP tools to read files, write code, run commands, and self-correct. Its output is captured and written back to the Xandarian Archive.
1. **Quality Gate (Seal of Approval):** After each Directive completes, the Worldmind evaluates the quality gate. For code generation, this typically means routing to Centurion Vigil (reviewer) or Centurion Gauntlet (tester). If the Seal is denied, the Worldmind routes feedback back to the originating Centurion for revision.
1. **Convergence:** When all Directives are fulfilled and all Seals granted, Worldmind marks the Mission complete, commits the changes via Force::Chronicle, and reports results to the operator via Helm.

### 2.3 Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────┐
│                         HELM (Interface Layer)                      │
│                    CLI (Typer) + REST API (FastAPI)                  │
│                         SSE Event Stream                            │
└──────────────────────────────┬──────────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────────┐
│                   WORLDMIND CORE (Control Plane)                    │
│                       LangGraph StateGraph                          │
│                                                                     │
│  ┌──────────┐   ┌──────────┐   ┌───────────────────┐              │
│  │ Classify  │──▶│ Upload   │──▶│ Centurion Prime   │              │
│  │ Request   │   │ Context  │   │ (Plan Mission)    │              │
│  └──────────┘   └──────────┘   └─────────┬─────────┘              │
│                                           │                         │
│                                    ┌──────▼──────┐                  │
│                                    │  Execute     │                  │
│                                    │  Directives  │  ◀── Send API   │
│                                    │  (Fan-Out)   │      Fan-Out    │
│                                    └──┬───┬───┬──┘                  │
│                                       │   │   │                     │
│  ┌─────────────┐  ┌─────────────┐  ┌─┴───┴───┴──────────────┐     │
│  │  Evaluate   │◀─│  Dispatch   │◀─│  Parallel Branches      │     │
│  │  Seal       │  │  Centurion  │  │  (1 per Directive)      │     │
│  └──────┬──────┘  └─────────────┘  └────────────────────────┘     │
│         │                                                           │
│    ┌────┴────┐                                                      │
│    │Converge │──▶ Mission Complete / Failed                         │
│    │Results  │                                                      │
│    └─────────┘                                                      │
│                                                                     │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │            Xandarian Archive (Shared State)                  │   │
│  │         PostgreSQL-backed LangGraph Checkpointer             │   │
│  └─────────────────────────────────────────────────────────────┘   │
└──────────────────────────────┬──────────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────────┐
│                     STARGATES (Infrastructure)                      │
│                   Docker (dev) / Kubernetes (prod)                   │
│                                                                     │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐                │
│  │  Stargate A  │  │  Stargate B  │  │  Stargate C  │               │
│  │ ┌─────────┐ │  │ ┌─────────┐ │  │ ┌─────────┐ │               │
│  │ │  Goose  │ │  │ │  Goose  │ │  │ │  Goose  │ │               │
│  │ │ (Forge) │ │  │ │ (Vigil) │ │  │ │(Gauntlet)│ │               │
│  │ └────┬────┘ │  │ └────┬────┘ │  │ └────┬────┘ │               │
│  │      │      │  │      │      │  │      │      │               │
│  │  MCP Client │  │  MCP Client │  │  MCP Client │               │
│  └──────┼──────┘  └──────┼──────┘  └──────┼──────┘               │
└─────────┼────────────────┼────────────────┼────────────────────────┘
          │                │                │
          ▼                ▼                ▼
┌─────────────────────────────────────────────────────────────────────┐
│                    NOVA FORCE (MCP Tool Layer)                      │
│                                                                     │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐               │
│  │Force::Terrain│ │Force::Chronc.│ │ Force::Spark │               │
│  │ (Filesystem) │ │    (Git)     │ │   (Shell)    │               │
│  └──────────────┘ └──────────────┘ └──────────────┘               │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐               │
│  │Force::Archive│ │Force::Signal │ │Force::Lattice│               │
│  │  (Database)  │ │ (RAG/Search) │ │ (Lang Server)│               │
│  └──────────────┘ └──────────────┘ └──────────────┘               │
└─────────────────────────────────────────────────────────────────────┘
```

-----

## 3. Control Plane: Worldmind Core (LangGraph)

The control plane is the LangGraph StateGraph that defines the Worldmind’s behavior. It is the single source of truth for mission state and the deterministic backbone that ensures reliable execution.

### 3.1 Why LangGraph

Framework comparison research identified several properties that make LangGraph the right choice for the control plane:

- **Cyclic graph architecture:** Unlike DAG-based workflow engines, LangGraph supports cycles natively. This is essential for the build-test-fix loop where a Coding Node passes to a Testing Node which, on failure, routes back to the Coding Node. This self-correcting cycle is the core pattern of autonomous software engineering.
- **Send API for dynamic fan-out:** When Centurion Prime plans a Mission with multiple parallel Directives, the Send API allows the graph to dynamically spawn N worker branches at runtime, where N is determined by the LLM’s planning output. Each branch carries its own localized state while sharing the global Xandarian Archive.
- **Per-step checkpointing:** LangGraph persists state after every super-step via its Checkpointer (Postgres-backed). If Worldmind crashes or redeploys during a long-running build, it resumes exactly where it left off. No other framework provides this granularity without custom engineering.
- **Native server architecture:** LangGraph Server provides a standardized REST API for creating threads and runs, native async task queues for long-running jobs, and structured event streaming. This eliminates the need to build a custom FastAPI wrapper around the orchestration logic.
- **Time-travel debugging:** The checkpointing system enables replay of any execution from any prior state, which is invaluable for debugging failed Missions and understanding why a Centurion produced incorrect output.

### 3.2 State Schema: The Xandarian Archive

The Xandarian Archive is the typed Pydantic model that serves as LangGraph’s shared state. Every node in the graph reads from and writes to this schema.

|Field               |Type                     |Description                                                                                                               |
|--------------------|-------------------------|--------------------------------------------------------------------------------------------------------------------------|
|`mission_id`        |`str`                    |Unique identifier. Format: `WMND-YYYY-NNNN`.                                                                              |
|`request`           |`str`                    |Original operator request, verbatim.                                                                                      |
|`classification`    |`Classification`         |Request category, complexity (1–5), affected components, planning strategy.                                               |
|`context`           |`ProjectContext`         |Gathered project metadata: file tree, conventions, dependencies, relevant source excerpts, git state.                     |
|`mission`           |`Mission`                |The execution plan: ordered Directives with agent assignments, I/O specs, dependencies, quality gates, failure strategies.|
|`directives`        |`list[Directive]`        |Flattened list of all Directives with their current status, results, and iteration counts.                                |
|`active_stargates`  |`dict[str, StargateInfo]`|Registry of currently running containers: Stargate ID, Centurion type, Directive ID, resource usage.                      |
|`files_created`     |`list[FileRecord]`       |All files created or modified during the Mission, with paths, content hashes, and originating Directive.                  |
|`test_results`      |`list[TestResult]`       |Aggregated test execution results across all Directives.                                                                  |
|`review_feedback`   |`list[ReviewFeedback]`   |Accumulated review feedback from Centurion Vigil, keyed by Directive and iteration.                                       |
|`execution_strategy`|`enum`                   |`linear` | `parallel` | `iterative`. Determines how the LangGraph routes Directives.                                      |
|`interaction_mode`  |`enum`                   |`interactive` | `supervised` | `autonomous`. Controls operator involvement.                                               |
|`status`            |`enum`                   |`uploading` | `planning` | `awaiting_approval` | `executing` | `replanning` | `completed` | `failed` | `cancelled`.       |
|`metrics`           |`MissionMetrics`         |Token usage, elapsed time per Directive, retry counts, quality gate pass rates.                                           |
|`created_at`        |`datetime`               |Mission creation timestamp (ISO 8601).                                                                                    |
|`updated_at`        |`datetime`               |Last state modification timestamp.                                                                                        |

### 3.3 Graph Topology

The LangGraph StateGraph defines the following node topology. Each node is a Python function that receives the Xandarian Archive, performs work, and returns a state update.

1. **`classify_request`** — Receives the raw request. Uses an LLM call to produce a Classification object (category, complexity, affected components). Routes to `upload_context`.
1. **`upload_context`** — Invokes Centurion Pulse via a Goose Stargate to scan the project. Pulse connects to Force::Terrain and Force::Chronicle to gather structure, conventions, and relevant source files. Writes ProjectContext to the Archive. Routes to `plan_mission`.
1. **`plan_mission`** — Centurion Prime node. Uses the Classification and ProjectContext to generate a Mission with ordered Directives. This is the most complex LLM call in the system. Routes to `await_approval` (Interactive) or `execute_directives` (Supervised/Autonomous).
1. **`await_approval`** — Pauses execution and emits the Mission to the Helm interface. Waits for operator input (approve, edit, reject). On approval, routes to `execute_directives`. On edit, routes back to `plan_mission` with modifications. On reject, routes to `mission_cancelled`.
1. **`execute_directives`** — The fan-out node. Uses the LangGraph Send API to dynamically spawn one execution branch per eligible Directive (respecting dependency ordering). Each branch targets the `dispatch_centurion` node with the Directive’s localized state.
1. **`dispatch_centurion`** — Opens a Stargate for the Directive’s assigned Centurion type. Translates the Directive’s input spec into a Goose instruction set. Launches the Goose instance and waits for completion. Captures output and writes results to the Archive.
1. **`evaluate_seal`** — Quality gate evaluation. Checks the Directive’s completion criteria (tests pass, lint clean, review approved). If the Seal is granted, marks the Directive as fulfilled. If denied, applies the Directive’s `on_failure` strategy (retry, replan, escalate, skip).
1. **`converge_results`** — Aggregation node. Runs after all Directive branches complete. Merges file changes, test results, and review feedback. Performs final validation. Routes to `mission_complete` or `mission_failed`.
1. **`replan`** — Triggered when `evaluate_seal` determines a replan is needed. Centurion Prime re-evaluates remaining Directives in light of the failure and generates a revised Mission segment. Routes back to `execute_directives`.

### 3.4 Conditional Routing Logic

The graph’s conditional edges encode the decision logic that makes the system deterministic rather than relying on LLM judgment for routing:

|From Node         |To Node             |Condition                                                         |
|------------------|--------------------|------------------------------------------------------------------|
|`plan_mission`    |`await_approval`    |`interaction_mode == interactive`                                 |
|`plan_mission`    |`execute_directives`|`interaction_mode in [supervised, autonomous]`                    |
|`evaluate_seal`   |`dispatch_centurion`|`seal == denied AND on_failure == retry AND retries < max_retries`|
|`evaluate_seal`   |`replan`            |`seal == denied AND on_failure == replan`                         |
|`evaluate_seal`   |`await_approval`    |`seal == denied AND on_failure == escalate`                       |
|`evaluate_seal`   |`converge_results`  |`seal == denied AND on_failure == skip`                           |
|`evaluate_seal`   |`converge_results`  |`seal == granted AND all_directives_resolved`                     |
|`evaluate_seal`   |*(wait)*            |`seal == granted AND pending_directives_exist`                    |
|`converge_results`|`mission_complete`  |All quality gates passed                                          |
|`converge_results`|`replan`            |Integration failures detected                                     |

### 3.5 Persistence and Recovery

The Worldmind uses LangGraph’s Postgres-backed Checkpointer to persist the Xandarian Archive after every graph node execution:

- **Crash recovery:** If the Worldmind server crashes or restarts during a Mission, the graph resumes from the last checkpointed state. Completed Directives are not re-executed. In-progress Stargates are detected and either re-attached or re-provisioned.
- **Time-travel debugging:** Operators can inspect the Archive at any prior checkpoint, replaying the decision path that led to a failure. This is exposed via the Helm API as a mission timeline view.
- **Long-running mission support:** Complex Missions may run for hours. The checkpointing system ensures that progress is never lost, even across server deployments or scaling events.

-----

## 4. Worker Plane: Goose Centurions

Each Centurion is a Goose instance running in headless mode inside a containerized Stargate. Goose was selected as the worker engine because of its native MCP integration, robust filesystem capabilities, and autonomous self-correction loops.

### 4.1 Why Goose

Framework comparison research identified Goose’s strengths and limitations clearly. Its strengths align perfectly with the worker role:

- **MCP-native:** Goose is built as an MCP super-client. It connects natively to MCP servers for filesystem, git, shell, database, and search operations. Centurions inherit the full Nova Force capability set without any adapter code.
- **Developer-centric autonomy:** Unlike tool-calling agents that invoke Python functions, Goose behaves like a developer at a terminal. It reads files, writes code, runs builds, reads error output, and self-corrects. This internal reasoning loop is exactly what a Centurion needs to execute a Directive autonomously.
- **Self-correcting build loops:** When a command fails (e.g., a build error or test failure), Goose reads the error output, reasons about the fix, and retries. This happens inside the Goose instance’s own context, before the result ever reaches the Worldmind’s quality gate. This reduces the number of outer-loop retries significantly.
- **Sandboxed execution:** Running Goose inside a Docker container (Stargate) provides natural isolation. Each Centurion operates on a mounted project volume with controlled network access and resource limits.

Its known limitations are mitigated by the hybrid architecture:

- **No native API** *(mitigated)*: Goose is CLI-first. The Stargate Bridge wraps the Goose CLI process, translating LangGraph state into Goose instructions and capturing structured output.
- **No recursion** *(mitigated)*: Goose subagents cannot spawn their own subagents. This is irrelevant because all task decomposition happens in the LangGraph control plane. Goose instances are workers, not orchestrators.
- **Session-based state** *(mitigated)*: Goose does not persist state across sessions. Each Centurion deployment handles a single Directive. All persistent state lives in the Xandarian Archive, managed by LangGraph.

### 4.2 Centurion Roster

|Centurion             |Rank      |Goose Config                               |Force Channels                  |Behavioral Profile                                                                                                                                       |
|----------------------|----------|-------------------------------------------|--------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------|
|**Centurion Prime**   |Nova Prime|Planner mode (runs in LangGraph, not Goose)|Terrain (read), Chronicle (read)|Request classification, context analysis, mission planning, execution management. Operates within the control plane, not in a Stargate.                  |
|**Centurion Pulse**   |Millennian|Research mode                              |Terrain, Chronicle, Signal      |Read-only. Scans project structure, reads source files, searches documentation. Produces structured context summaries. Never writes files.               |
|**Centurion Forge**   |Centurion |Code-gen mode                              |Terrain, Chronicle, Spark       |Read-write. Generates new code, creates files, modifies existing files. Runs build commands to validate. Uses self-correction loops on build failures.   |
|**Centurion Vigil**   |Centurion |Review mode                                |Terrain, Chronicle, Signal      |Read-only. Analyzes code for quality, security, consistency, and correctness. Produces structured review feedback. Grants or denies the Seal of Approval.|
|**Centurion Gauntlet**|Centurion |Test mode                                  |Terrain, Spark                  |Read-write (test files only). Generates tests following project conventions. Executes test suites. Reports pass/fail with coverage metrics.              |
|**Centurion Prism**   |Centurion |Refactor mode                              |Terrain, Chronicle, Spark       |Read-write. Restructures code without changing behavior. Runs existing tests before and after to verify behavioral equivalence.                          |

### 4.3 The Stargate Bridge

The Stargate Bridge is the critical integration layer between LangGraph and Goose. It translates between the two systems:

1. **Directive → Goose instruction:** The bridge takes a Directive object from the Xandarian Archive and constructs a Goose instruction file. This file includes the task description, relevant context (file excerpts, conventions, constraints), specific instructions (“use pytest, not unittest”), and success criteria. The instruction is written to a file inside the Stargate volume.
1. **Goose launch:** The bridge executes `goose run --instructions directive.md` inside the Stargate container. The Goose process runs headlessly, connecting to the pre-configured MCP servers (Force channels) available inside the container.
1. **Output capture:** The bridge monitors the Goose process via stdout (JSON-structured output mode). It captures: files created/modified, commands executed and their exit codes, errors encountered and self-corrections applied, and the final completion status.
1. **Result → Archive:** The bridge translates the captured output into a `DirectiveResult` object and writes it back to the Xandarian Archive. This includes file records (paths + content hashes), command logs, and a success/failure determination.

> **Bridge Design Principle**
> 
> The Stargate Bridge should be *thin*. It translates between LangGraph state and Goose instructions, and between Goose output and LangGraph state. It should not contain business logic, routing decisions, or quality evaluation. Those responsibilities belong to the control plane.

### 4.4 MCP Permission Matrix

Each Centurion type receives a tailored MCP server configuration inside its Stargate. This enforces the principle of least privilege.

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

The Nova Force is the collection of MCP servers that provide capabilities to the Centurion agents. Each MCP server runs as an independent process (or container) and exposes tools via the Model Context Protocol. Goose connects to these natively as an MCP client.

### 5.1 Force Channel Specifications

|Channel             |Implementation        |Key Tools Exposed                                                           |Deployment Model                                                                                                            |
|--------------------|----------------------|----------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------|
|**Force::Terrain**  |MCP Filesystem Server |`read_file`, `write_file`, `list_directory`, `search_files`, `file_exists`  |Sidecar container per Stargate, mounted to the project volume. Path restrictions enforced per Centurion type.               |
|**Force::Chronicle**|MCP Git Server        |`git_status`, `git_diff`, `git_log`, `git_blame`, `git_commit`, `git_branch`|Shared service. Single instance per project. Write operations gated by Centurion type.                                      |
|**Force::Spark**    |MCP Shell Server      |`run_command`, `run_script`, `get_env`, `list_processes`                    |Sidecar container per Stargate. Command allowlisting enforced per Centurion type.                                           |
|**Force::Archive**  |gp-mcp-server (custom)|`query`, `describe_table`, `list_schemas`, `explain_plan`                   |Shared service. Connection pooling, query validation, and parameterized queries per the gp-mcp-server security architecture.|
|**Force::Signal**   |MCP RAG/Search Server |`search_codebase`, `search_docs`, `semantic_search`, `get_definition`       |Shared service. Indexes the project codebase and documentation. Updated incrementally as Centurions modify files.           |
|**Force::Lattice**  |MCP Language Server   |`get_diagnostics`, `get_completions`, `find_references`, `get_symbols`      |Per-language sidecar. Python LSP for Python projects, TypeScript LSP for TS projects.                                       |

### 5.2 Security Model

The Nova Force security model follows the principle of least privilege at multiple levels:

- **Centurion-level restrictions:** Each Centurion type has a defined set of Force channels and permission levels (read-only, read-write, restricted-write). These are enforced in the Goose MCP configuration injected by the Stargate Bridge.
- **Stargate-level isolation:** Each Stargate container has its own network namespace, filesystem mount, and resource limits. Centurions cannot access other Centurions’ Stargates or the host system.
- **Force channel authentication:** MCP servers require authentication tokens issued by the Worldmind. Tokens are scoped to the specific Mission and Directive, with expiration times matching the expected Directive duration.
- **Command allowlisting:** Force::Spark (shell execution) uses an allowlist model. Only approved commands (build tools, test runners, linters) are permitted. The allowlist is configurable per project and per Centurion type.
- **Write path restrictions:** Force::Terrain (filesystem) enforces path-based write restrictions. Centurion Gauntlet can only write to test directories. Centurion Vigil cannot write at all. These restrictions are enforced at the MCP server level, not just in the Goose configuration.

-----

## 6. Infrastructure Layer: Stargates

Stargates are the containerized execution environments in which Centurions operate. The Stargate layer manages the full container lifecycle: provisioning, configuration, monitoring, and cleanup.

### 6.1 Stargate Lifecycle

1. **Provision:** When the Worldmind dispatches a Directive, the Stargate manager selects the appropriate container image (based on Centurion type and project language), provisions it with the project volume mount, injects the MCP server configuration, and starts the container.
1. **Configure:** The Stargate Bridge writes the Goose instruction file and MCP configuration to the container volume. Environment variables are set for the Goose process: model selection, token limits, and connection strings for the Force channel MCP servers.
1. **Execute:** The Goose process runs inside the container. The Bridge monitors it via stdout and process health checks. Resource usage (CPU, memory, disk) is tracked and reported to the Xandarian Archive.
1. **Capture:** On Goose process completion, the Bridge captures the final state: files modified on the volume, exit code, structured output logs, and any error information.
1. **Teardown:** After result capture, the Stargate is torn down. The container is stopped and removed. Volume data (file modifications) has already been committed to the shared project volume. Temporary artifacts are cleaned up.

### 6.2 Container Images

Stargate container images are purpose-built for each Centurion type and project language combination:

- **Base image:** A Goose-ready base with the Goose CLI, MCP client libraries, and common development tools pre-installed.
- **Language layers:** Stacked on top of the base — Python (with pip, poetry, pytest), Node.js (with npm, jest), Java (with Maven/Gradle, JUnit), Go (with go test), etc. Multiple language layers can be combined for polyglot projects.
- **Centurion profile:** A lightweight configuration layer that sets the Goose system prompt, MCP server permissions, and behavioral constraints for the specific Centurion type.

### 6.3 Kubernetes Deployment (Production)

In production, Stargates run as Kubernetes Jobs:

- **Job per Directive:** Each Directive dispatches a K8s Job with the appropriate container image, resource requests/limits, and volume claims.
- **Project volumes:** The project codebase is mounted via a PersistentVolumeClaim (PVC) shared across all Stargates in a Mission. This ensures all Centurions see each other’s file modifications. Concurrent write conflicts are managed by the Worldmind’s dependency ordering (parallel Directives operate on non-overlapping files).
- **Resource scaling:** Complex Missions with many parallel Directives can scale horizontally across K8s nodes. The Stargate manager respects resource quotas and can queue Directives when capacity is constrained.
- **Observability:** Container logs are forwarded to the Worldmind’s event stream. K8s metrics (CPU, memory, disk I/O) are collected per Stargate and surfaced in the Helm mission dashboard.

### 6.4 Development Mode (Docker)

For local development, Stargates run as Docker containers:

```bash
# Stargate spin-up (simplified)
docker run -d \
  --name stargate-forge-001 \
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

## 7. Interface Layer: Helm

The Helm is the operator’s interface to the Worldmind. It provides both a CLI for interactive use and a REST API for programmatic integration.

### 7.1 Helm CLI

The CLI is the primary interface for v1. Built with Typer (Python), it communicates with the Worldmind Core via the LangGraph Server API.

#### Key Commands

|Command                                        |Description                                               |
|-----------------------------------------------|----------------------------------------------------------|
|`worldmind mission "<request>"`                |Submit a new Mission. Enters interactive planning flow.   |
|`worldmind mission --auto "<request>"`         |Submit a Mission in Supervised mode (auto-approve).       |
|`worldmind status <mission-id>`                |Show current Mission status with Directive progress.      |
|`worldmind status --watch <mission-id>`        |Stream real-time Mission events to the terminal.          |
|`worldmind log <mission-id>`                   |Show the complete Mission execution log.                  |
|`worldmind timeline <mission-id>`              |Show the checkpointed state timeline (time-travel view).  |
|`worldmind cancel <mission-id>`                |Cancel a running Mission. Tears down active Stargates.    |
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

### 7.2 Helm REST API

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
|`GET` |`/api/v1/stargates`                     |List active Stargates with resource usage.                       |
|`GET` |`/api/v1/health`                        |System health: Worldmind Core, Force channels, Stargate capacity.|

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

### 8.2 Parallel Fan-Out (Send API)

Multiple Directives execute concurrently when they share no dependencies. This is the primary pattern for feature implementation where multiple files can be generated independently.

```
                  ┌──▶ [Forge: Controller] ──┐
                  │                           │
[Pulse] ──▶ Fan-Out──▶ [Forge: Service]    ──┼──▶ [Gauntlet] ──▶ [Vigil]
                  │                           │
                  └──▶ [Forge: Repository] ──┘
```

The Send API is the mechanism that enables true dynamic spawning: if Centurion Prime plans 3 parallel Directives, the graph fans out to 3 concurrent Goose Stargates. If it plans 8, it fans to 8. The topology is determined at runtime by the planner’s output, not by the graph definition.

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

The Worldmind emits structured events at every significant state transition. These events power the Helm CLI progress display, the API’s SSE stream, and operational monitoring.

|Event                |Payload                                                  |Consumer                         |
|---------------------|---------------------------------------------------------|---------------------------------|
|`mission.created`    |Full Mission plan, operator request, classification      |Helm (display plan), audit log   |
|`mission.approved`   |Mission ID, approval source (operator/auto)              |Helm, audit log                  |
|`stargate.opened`    |Stargate ID, Centurion type, Directive ID, container info|Helm (progress), resource monitor|
|`directive.started`  |Directive ID, Centurion name, input summary              |Helm (progress updates)          |
|`directive.progress` |Intermediate output: files created, commands run         |Helm (live feed), debugging      |
|`directive.fulfilled`|Result summary, files modified, quality gate outcome     |Helm, metrics                    |
|`seal.denied`        |Directive ID, failure details, `on_failure` action       |Helm (alert), audit log          |
|`mission.replanned`  |Revised Directive list, reason for replan                |Helm, audit log                  |
|`mission.completed`  |Final summary, all results, total metrics                |Helm, metrics, audit log         |
|`mission.escalated`  |Reason, context, operator options                        |Helm (notification)              |

### 9.2 Log Format

All system logs use the Worldmind vocabulary consistently:

```
[WORLDMIND]        Initializing Xandarian Archive...
[WORLDMIND]        Nova Force channels online: Terrain, Chronicle, Spark
[WORLDMIND]        Centurion roster loaded: Prime, Forge, Vigil, Gauntlet, Prism, Pulse
[HELM]             Mission request received: "Add user profile endpoint"
[WORLDMIND]        Uploading project context... 47 files indexed.
[CENTURION PRIME]  Mission planned: 5 directives, 3 centurions assigned.
[STARGATE]         Opening for Centurion Forge...
[CENTURION FORGE]  Directive 002: Implement UserProfileController.
[FORCE::TERRAIN]   3 files created.
[CENTURION FORGE]  Directive 002 complete. 3 files, 187 lines.
[CENTURION VIGIL]  Seal of Approval granted.
[WORLDMIND]        Mission WMND-2026-0042 complete. All directives fulfilled.
```

### 9.3 Metrics

The following metrics are collected per Mission and aggregated for system-wide monitoring:

- **Planning latency:** Time from request intake to Mission plan approval.
- **Execution time:** Total wall-clock time and per-Directive breakdown.
- **Token consumption:** LLM tokens used, broken down by Centurion Prime (planning), Centurion workers (execution), and quality gate evaluation.
- **Seal of Approval rate:** Percentage of Directives that pass their quality gate on first attempt.
- **Iteration depth:** Average and max number of build-test-fix cycles per Mission.
- **Stargate utilization:** Container count, resource usage, queue depth, average Stargate lifetime.
- **Escalation rate:** Percentage of Missions requiring operator intervention, categorized by reason.

-----

## 10. Performance Requirements

|Metric                                   |Target  |Notes                                                                          |
|-----------------------------------------|--------|-------------------------------------------------------------------------------|
|Time to first plan (complexity 1–2)      |< 10s   |Includes classification and minimal context upload.                            |
|Time to first plan (complexity 3–5)      |< 30s   |Includes full context upload and multi-step planning.                          |
|Stargate spin-up time                    |< 5s    |From `dispatch_centurion` to Goose process running. Requires pre-pulled images.|
|Directive execution (simple file gen)    |< 30s   |Single file generation with validation.                                        |
|Directive execution (complex multi-file) |< 3 min |Multiple files with build verification.                                        |
|Full Mission (complexity 3, 5 Directives)|< 10 min|End-to-end including planning, execution, and review cycles.                   |
|Checkpoint write latency                 |< 100ms |Postgres write per super-step. Must not bottleneck graph execution.            |
|Event stream latency                     |< 500ms |From state transition to Helm UI update.                                       |
|Concurrent Missions per instance         |5 (v1)  |Scaling horizontally via K8s replicas for higher throughput.                   |
|Max parallel Stargates per Mission       |10 (v1) |Configurable. Limited by K8s resource quotas.                                  |

-----

## 11. Technology Stack

|Component            |Technology                             |Rationale                                                                                                                         |
|---------------------|---------------------------------------|----------------------------------------------------------------------------------------------------------------------------------|
|**Control Plane**    |LangGraph (Python)                     |Cyclic graphs, Send API, per-step checkpointing, native server. No other framework matches this combination.                      |
|**Worker Engine**    |Goose (headless CLI)                   |MCP-native, developer-centric autonomy, self-correcting build loops. Strongest filesystem/code interaction of any agent framework.|
|**State Persistence**|PostgreSQL (via LangGraph Checkpointer)|Battle-tested, supports the checkpoint schema natively, enables time-travel queries.                                              |
|**Container Runtime**|Docker (dev) / Kubernetes (prod)       |Standard container orchestration. K8s Jobs map perfectly to the Stargate lifecycle.                                               |
|**CLI Interface**    |Typer (Python)                         |Modern Python CLI framework. Rich terminal output for Mission progress display.                                                   |
|**API Framework**    |FastAPI                                |Wraps LangGraph Server API with Worldmind-specific endpoints. Native async, SSE support.                                          |
|**MCP Servers**      |Per-channel (see Force specs)          |Standard MCP protocol. Mix of community servers and custom implementations (gp-mcp-server).                                       |
|**LLM Provider**     |Anthropic Claude (configurable)        |Centurion Prime uses Claude Sonnet 4.5 for planning. Workers use configurable models.                                             |
|**Observability**    |Structured logging + Prometheus        |Events emitted as structured JSON. Prometheus metrics for dashboarding.                                                           |

-----

## 12. Implementation Roadmap

### Phase 1: Foundation (Weeks 1–4)

- [ ] LangGraph StateGraph with `classify_request`, `plan_mission`, and `execute_directives` nodes
- [ ] Xandarian Archive schema (Pydantic models for Mission, Directive, state fields)
- [ ] Postgres Checkpointer integration for state persistence
- [ ] Single Centurion type: Centurion Forge (code generator) running Goose in Docker
- [ ] Stargate Bridge: Directive-to-Goose-instruction translation, output capture
- [ ] Force::Terrain (Filesystem MCP) server configured for Goose
- [ ] Basic Helm CLI: `worldmind mission`, `worldmind status`

### Phase 2: The Loop (Weeks 5–8)

- [ ] Add Centurion Vigil (reviewer) and Centurion Gauntlet (tester)
- [ ] Implement the iterative build-test-fix cycle with conditional routing
- [ ] Quality gate evaluation (`evaluate_seal` node)
- [ ] Force::Spark (Shell MCP) for build and test execution
- [ ] Force::Chronicle (Git MCP) for branch management and commits
- [ ] Retry, replan, and escalate failure strategies

### Phase 3: Scale (Weeks 9–12)

- [ ] Parallel Directive execution via Send API fan-out
- [ ] Add Centurion Pulse (research) and Centurion Prism (refactorer)
- [ ] Kubernetes deployment with K8s Jobs for Stargates
- [ ] Full Helm CLI with `--watch` mode, `timeline`, and `inspect` commands
- [ ] Helm REST API with SSE event streaming
- [ ] Observability: structured events, Prometheus metrics

### Phase 4: Polish (Weeks 13–16)

- [ ] Force::Signal (RAG/Search MCP) for codebase-aware planning
- [ ] Force::Lattice (Language Server MCP) for static analysis augmentation
- [ ] Oscillation detection and progressive escalation in iterative cycles
- [ ] Multi-language Stargate images (Python, Node, Java, Go)
- [ ] Security hardening: token-scoped MCP auth, command allowlisting, path restrictions
- [ ] Performance optimization: image pre-pulling, warm Stargate pools, context budget tuning

-----

## 13. Future Considerations

- **Dynamic Centurion registration:** Allow new Centurion types to be registered at runtime via a capability manifest. Centurion Prime would discover available Centurions and incorporate them into planning without code changes.
- **Multi-project Missions:** Support Missions that span multiple repositories or services, with cross-project dependency tracking and coordinated commits.
- **Learning from outcomes:** Track which planning strategies succeed for which request types. Use this history to improve Centurion Prime’s planning over time.
- **Web UI:** A browser-based Helm dashboard for Mission visualization, real-time Centurion monitoring, and interactive plan editing. Built as a separate frontend consuming the Helm REST API.
- **CI/CD integration:** Trigger Missions from CI/CD pipelines (e.g., “fix this failing test” triggered by a GitHub Actions failure). Centurions commit fixes directly to branches for review.
- **Worker engine alternatives:** The Stargate Bridge is designed to be worker-agnostic. Future Centurion implementations could use Claude Code, Aider, or custom LLM agents instead of Goose, selected per Centurion type based on task requirements.

-----

## 14. Nomenclature Reference

|Technical Concept   |Worldmind Name       |Description                                                  |
|--------------------|---------------------|-------------------------------------------------------------|
|Orchestration Server|**Worldmind**        |The LangGraph control plane that plans and manages execution.|
|CLI / API Interface |**Helm**             |The operator’s interface. Named for the Nova Helm.           |
|Specialist Agents   |**Centurions**       |Goose worker instances dispatched to execute Directives.     |
|Planner Agent       |**Centurion Prime**  |The lead Centurion responsible for mission planning.         |
|Agent Containers    |**Stargates**        |Docker/K8s environments in which Centurions operate.         |
|Execution Plans     |**Missions**         |Structured plans with ordered Directives.                    |
|Plan Steps          |**Directives**       |Individual tasks assigned to specific Centurions.            |
|MCP Servers / Tools |**Nova Force**       |The MCP tool layer. Each server is a Force channel.          |
|Shared State        |**Xandarian Archive**|The LangGraph state object (Pydantic model + Postgres).      |
|Context Gathering   |**Upload**           |Scanning the codebase to build the context window.           |
|Quality Gates       |**Seal of Approval** |Criteria for Directive success. Granted or denied.           |
|User                |**Operator**         |The human using the system.                                  |

-----

> **Architectural Invariant**
> 
> The separation between the LangGraph control plane and the Goose worker plane is the system’s most important architectural decision. The control plane must never execute code directly. The worker plane must never make routing decisions. Violating this boundary collapses the hybrid model into a monolith and loses the advantages of both frameworks.

-----

*Worldmind — Tell it what to build. It plans the mission, deploys the centurions, and delivers the code.*
