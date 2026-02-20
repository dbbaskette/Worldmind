# Component Rename Plan

This document proposes renaming Worldmind's internal components from abstract/fantasy names to descriptive, self-documenting names that clearly indicate their purpose.

## Rationale

The current naming scheme uses evocative but opaque names (Agent, Sandbox, Coder, Reviewer, etc.) that:
- Require memorization to understand
- Don't communicate function to new users
- Make documentation harder to write and read
- Create a steeper learning curve

The proposed names prioritize **clarity over cleverness**.

---

## Rename Table

### Worker Types (Agents → Agents)

| Current Name | Proposed Name | Role | Permissions |
|--------------|---------------|------|-------------|
| **Agent** (umbrella) | **Agent** | Generic term for worker | — |
| **Coder** | **Coder** | Code generation and implementation | Read/write `src/`, `lib/`, `app/` |
| **Tester** | **Tester** | Test writing and execution | Read/write `test/`, `tests/`, `spec/` |
| **Reviewer** | **Reviewer** | Code review and quality assessment | Read-only |
| **Researcher** | **Researcher** | Research and context gathering | Read-only |
| **Refactorer** | **Refactorer** | Refactoring with behavioral equivalence | Read/write `src/`, `lib/`, `app/` |

### Infrastructure Components

| Current Name | Proposed Name | Purpose |
|--------------|---------------|---------|
| **Sandbox** | **Sandbox** | Isolated container environment for agent execution |
| **AgentDispatcher** | **AgentDispatcher** | Dispatches agents to sandboxes |
| **SandboxProvider** | **SandboxProvider** | Abstract interface for sandbox backends |
| **DockerSandboxProvider** | **DockerSandboxProvider** | Docker-based sandbox implementation |
| **CloudFoundrySandboxProvider** | **CloudFoundrySandboxProvider** | CF Task-based sandbox implementation |
| **SandboxProperties** | **SandboxProperties** | Configuration for sandbox behavior |
| **AgentRequest** | **AgentRequest** | Request payload for agent execution |
| **SandboxInfo** | **SandboxInfo** | Metadata about a sandbox execution |

### Orchestration Components

| Current Name | Proposed Name | Purpose |
|--------------|---------------|---------|
| **Task** | **Task** | A single unit of work for an agent |
| **QualityGate** | **QualityGate** | Approval decision based on tests + review |
| **QualityGateDecision** | **QualityDecision** | Result of quality gate evaluation |
| **QualityGateEvaluationService** | **QualityGateService** | Evaluates whether code passes quality checks |
| **Mission** | **Mission** | (keep) — top-level user request |
| **Wave** | **Batch** | Group of tasks executed in parallel |

### MCP Tools (Optional — these are external)

| Current Name | Proposed Name | Purpose |
|--------------|---------------|---------|
| **Terrain** | **FileSystem** | File read/write operations |
| **Chronicle** | **Git** | Git operations |
| **Spark** | **Shell** | Shell command execution |
| **Signal** | **Search** | Code search and indexing |

---

## Package Structure Changes

```
Current                                    Proposed
───────────────────────────────────────────────────────────────────
com.worldmind.sandbox/                 com.worldmind.sandbox/
├── AgentDispatcher.java                 ├── AgentDispatcher.java
├── SandboxManager.java                ├── SandboxManager.java
├── SandboxProperties.java             ├── SandboxProperties.java
├── AgentRequest.java                ├── AgentRequest.java
├── DockerSandboxProvider.java         ├── DockerSandboxProvider.java
├── InstructionBuilder.java                ├── InstructionBuilder.java (unchanged)
└── cf/                                    └── cf/
    ├── CloudFoundrySandboxProvider    ├── CloudFoundrySandboxProvider
    └── CloudFoundryProperties             └── CloudFoundryProperties (unchanged)

com.worldmind.core.quality_gate/                   com.worldmind.core.quality/
├── QualityGateEvaluationService.java             ├── QualityGateService.java
```

---

## Environment Variable Changes

| Current | Proposed | Notes |
|---------|----------|-------|
| `AGENT_CODER_APP` | `AGENT_CODER_APP` | CF app name |
| `AGENT_TESTER_APP` | `AGENT_TESTER_APP` | CF app name |
| `AGENT_REVIEWER_APP` | `AGENT_REVIEWER_APP` | CF app name |
| `AGENT_RESEARCHER_APP` | `AGENT_RESEARCHER_APP` | CF app name |
| `AGENT_REFACTORER_APP` | `AGENT_REFACTORER_APP` | CF app name |
| `AGENT_IMAGE_REGISTRY` | `AGENT_IMAGE_REGISTRY` | Docker registry |
| `SANDBOX_PROVIDER` | `SANDBOX_PROVIDER` | docker/cloudfoundry |
| `SANDBOX_IMAGE_PREFIX` | `SANDBOX_IMAGE_PREFIX` | Image name prefix |

---

## Docker Image Changes

| Current | Proposed |
|---------|----------|
| `agent-base` | `agent-base` |
| `agent-coder` | `agent-coder` |
| `agent-tester` | `agent-tester` |
| `agent-reviewer` | `agent-reviewer` |
| `agent-researcher` | `agent-researcher` |
| `agent-refactorer` | `agent-refactorer` |
| `sandbox:java` | `sandbox:java` |
| `sandbox:python` | `sandbox:python` |
| `sandbox:node` | `sandbox:node` |

---

## API Endpoint Changes

| Current | Proposed |
|---------|----------|
| `GET /api/v1/sandboxes` | `GET /api/v1/sandboxes` |
| Response: `agent: "CODER"` | Response: `agent: "CODER"` |
| Response: `task_id` | Response: `task_id` |

---

## UI Changes

| Location | Current | Proposed |
|----------|---------|----------|
| TaskCard.tsx | `task.agent` | `task.agent` |
| StatusBadge colors | `agent-coder`, `agent-reviewer`, etc. | `agent-coder`, `agent-reviewer`, etc. |
| Mission view | "Tasks" section | "Tasks" section |
| MCP status | "Agent tokens" | "Agent tokens" |

---

## Migration Plan

### Phase 1: Internal Aliases (Non-Breaking)
1. Add type aliases in Java: `typedef Task = Task`
2. Add `@JsonAlias` annotations for JSON compatibility
3. Update UI to accept both old and new field names
4. Add deprecation warnings to old env vars

### Phase 2: Documentation Update
1. Update README.md with new terminology
2. Update architecture docs
3. Add glossary mapping old → new names
4. Update inline code comments

### Phase 3: Code Rename (Breaking)
1. Rename Java packages and classes
2. Update all references in codebase
3. Rename Docker images
4. Update CI/CD workflows
5. Update manifest.yml and .env.example

### Phase 4: Deprecation Removal
1. Remove old JSON aliases after 2 releases
2. Remove old env var support
3. Remove old Docker images from registry

---

## Quick Reference Card

After rename, the system reads as:

```
User submits a Mission
  → Planner creates Tasks (formerly Tasks)
  → Tasks are assigned to Agents (formerly Agents):
      - Coder writes code (formerly Coder)
      - Tester runs tests (formerly Tester)  
      - Reviewer checks quality (formerly Reviewer)
      - Researcher gathers context (formerly Researcher)
      - Refactorer restructures code (formerly Refactorer)
  → Agents run in Sandboxes (formerly Sandboxes)
  → QualityGate approves or rejects (formerly QualityGate)
  → Mission completes
```

---

## Decision

- [ ] Approve rename plan
- [ ] Modify rename plan (specify changes)
- [ ] Reject rename plan (keep current names)

**Notes:**
- The "Worldmind" name is retained as the overall product name
- "Mission" is retained as it's already descriptive
- This is a significant refactoring effort; estimate 2-3 days of work
