# Nova Force MCP Integration — Product Requirements Document

## Worldmind Implementation Changes

**Version:** 1.0.0  
**Date:** February 11, 2026  
**Status:** Ready for Implementation  
**Author:** Dan Baskette

---

## 1. Summary

Integrate Worldmind with Nexus (MCP Gateway) to give Core graph nodes and Agent agents access to four MCP servers: Archive (knowledge graph memory), Xandar (GitHub), Brave Search, and Datacore (PostgreSQL). Nexus manages all server lifecycle and access control externally. Worldmind's responsibility is limited to: connecting to Nexus with the right credentials per consumer, and injecting MCP tools into the right execution contexts.

---

## 2. Goals

- Connect 12 Worldmind consumers (7 Core graph nodes + 5 Agent types) to Nexus via Streamable HTTP
- Make the Nexus gateway fully configurable via `.env` — no hardcoded URLs or credentials
- Push Nexus config to the modules that need it: Core nodes get tools via Spring AI MCP Client, Agents get credentials passed into Sandbox containers
- Zero MCP server management in Worldmind — Nexus owns everything upstream

## 3. Non-Goals

- Worldmind does not manage MCP server lifecycle, health, or registration
- Worldmind does not store MCP server metadata in its database
- No UI changes in this phase — Nova Force dashboard is a future iteration
- No changes to the LangGraph4j graph structure or node execution order

---

## 4. MCP Server Roster (Configured in Nexus)

| Codename | Server | Function |
|----------|--------|----------|
| **Archive** | Knowledge graph memory | Persistent memory across missions — patterns, lessons, decisions |
| **Xandar** | GitHub API | PR creation, branch management, issue tracking |
| **Brave Search** | Web search | Research, tech discovery, documentation lookup |
| **Datacore** | PostgreSQL | Schema inspection, query execution, state checks |

---

## 5. Nexus User → Server Access Matrix

| # | Nexus User | Layer | Archive | Xandar | Brave Search | Datacore |
|---|-----------|-------|---------|--------|-------------|----------|
| 1 | `worldmind-classify` | Core | 👁️ | — | 👁️ | — |
| 2 | `worldmind-upload` | Core | — | — | — | — |
| 3 | `worldmind-plan` | Core | 👁️ | — | 👁️ | — |
| 4 | `worldmind-schedule` | Core | — | — | — | — |
| 5 | `worldmind-converge` | Core | — | — | — | 👁️ |
| 6 | `worldmind-quality_gate` | Core | ✏️ | — | — | — |
| 7 | `worldmind-postmission` | Core | ✏️ | ✏️ | — | — |
| 8 | `worldmind-coder` | Agent | — | — | — | 👁️ |
| 9 | `worldmind-tester` | Agent | — | — | — | 👁️ |
| 10 | `worldmind-refactorer` | Agent | — | — | — | — |
| 11 | `worldmind-reviewer` | Agent | — | — | — | — |
| 12 | `worldmind-researcher` | Agent | — | — | 👁️ | 👁️ |

👁️ = read-only tools exposed · ✏️ = read/write tools exposed · — = no access

**Note:** `worldmind-upload`, `worldmind-schedule`, `worldmind-refactorer`, and `worldmind-reviewer` have zero MCP server access. Upload and Schedule are pure orchestration. Refactorer and Reviewer rely entirely on Goose's native file and git capabilities.

---

## 6. Environment Configuration

All Nexus configuration lives in `.env`. Worldmind reads these at startup and distributes them to the modules that need them.

### 6.1 `.env` Additions

```bash
# ================================================================
# Nexus MCP Gateway
# ================================================================

# Gateway URL — single endpoint for all MCP traffic
NEXUS_URL=http://nexus:8090/mcp

# --- Core Node Tokens ---
# Each graph node authenticates to Nexus as a dedicated user.
# Nodes with no MCP access (upload, schedule) still get tokens
# for future extensibility — Nexus simply returns zero tools.
NEXUS_CORE_CLASSIFY_TOKEN=
NEXUS_CORE_UPLOAD_TOKEN=
NEXUS_CORE_PLAN_TOKEN=
NEXUS_CORE_SCHEDULE_TOKEN=
NEXUS_CORE_CONVERGE_TOKEN=
NEXUS_CORE_QUALITY_GATE_TOKEN=
NEXUS_CORE_POSTMISSION_TOKEN=

# --- Agent Type Tokens ---
# Injected into Sandbox containers at dispatch time.
# Agents with no MCP access (refactorer, reviewer) still get tokens
# for future extensibility.
NEXUS_AGENT_CODER_TOKEN=
NEXUS_AGENT_TESTER_TOKEN=
NEXUS_AGENT_REFACTORER_TOKEN=
NEXUS_AGENT_REVIEWER_TOKEN=
NEXUS_AGENT_RESEARCHER_TOKEN=

# --- Feature Flag ---
# Master switch to enable/disable all MCP integration.
# When false, graph nodes run without tools, agents
# launch without Nexus connection.
NEXUS_ENABLED=true
```

### 6.2 `.env.example` Updates

Add all keys above to `.env.example` with empty values and comments explaining each section.

---

## 7. Implementation Requirements

### 7.1 New: Configuration Properties

**File:** `src/main/java/com/worldmind/core/novaforce/NexusProperties.java`

Spring Boot `@ConfigurationProperties` class that binds the `.env` values.

```
worldmind.nexus.enabled       → boolean (default: false)
worldmind.nexus.url            → string
worldmind.nexus.core.*         → map of node name → token
worldmind.nexus.agents.*   → map of agent type → token
```

**Acceptance criteria:**
- Application starts cleanly when `NEXUS_ENABLED=false` with no Nexus connection attempts
- Application fails fast at startup when `NEXUS_ENABLED=true` but `NEXUS_URL` is missing
- Missing individual tokens log a warning but don't prevent startup (allows incremental rollout)

### 7.2 New: NexusClientFactory

**File:** `src/main/java/com/worldmind/core/novaforce/NexusClientFactory.java`

Central factory that creates and caches `McpSyncClient` instances per consumer.

**Responsibilities:**
- Creates one `McpSyncClient` per Core graph node using Streamable HTTP transport
- Each client authenticates to Nexus using its node-specific token
- Clients are created lazily and cached (ConcurrentHashMap)
- Provides `getAgentToken(AgentType)` for Sandbox dispatch
- Graceful shutdown: closes all cached clients on `@PreDestroy`

**Acceptance criteria:**
- `getClient("plan")` returns a client authenticated as `worldmind-plan`
- `getClient("plan")` called twice returns the same cached instance
- `getClient("schedule")` returns a client that reports zero tools (by Nexus design)
- `getAgentToken(AgentType.CODER)` returns the coder token string
- All clients are closed on application shutdown
- When `NEXUS_ENABLED=false`, `getClient()` returns null (or throws a clear exception)

### 7.3 New: NovaForceToolProvider

**File:** `src/main/java/com/worldmind/core/novaforce/NovaForceToolProvider.java`

Translates MCP tool listings into Spring AI `ToolCallback[]` arrays.

**Responsibilities:**
- Takes a node name, gets the client from NexusClientFactory
- Calls `client.listTools()` to discover available tools
- Wraps each tool as a `McpToolCallback`
- Returns `ToolCallback[]` ready for use with `ChatOptionsBuilder.withTools()`

**Acceptance criteria:**
- `getToolsForNode("plan")` returns callbacks for Archive (read), Brave Search tools
- `getToolsForNode("schedule")` returns an empty array
- When `NEXUS_ENABLED=false`, returns an empty array for all nodes

### 7.4 Modify: Graph Node Integration

Each graph node that has MCP access injects `NovaForceToolProvider` and passes tools to the ChatModel.

**Files to modify:**

| Graph Node | File | Tools Expected | Changes |
|-----------|------|---------------|---------|
| Classify | `ClassifyNode.java` | Archive (read), Brave Search | Inject `NovaForceToolProvider`, add tools to prompt |
| Plan | `PlanNode.java` | Archive (read), Brave Search | Inject `NovaForceToolProvider`, add tools to prompt |
| Converge | `ConvergeNode.java` | Datacore (read) | Inject `NovaForceToolProvider`, add tools to prompt |
| QualityGate | `QualityGateNode.java` | Archive (read/write) | Inject `NovaForceToolProvider`, add tools to prompt |
| PostMission | `PostMissionNode.java` | Xandar (read/write), Archive (read/write) | Inject `NovaForceToolProvider`, add tools to prompt |

**Nodes with NO changes:**
- Upload — no MCP access
- Schedule — no MCP access

**Pattern for each modified node:**

```java
@Component
public class PlanNode implements NodeAction<WorldmindState> {

    private final ChatModel chatModel;
    private final NovaForceToolProvider toolProvider;

    @Override
    public Map<String, Object> apply(WorldmindState state) {
        ToolCallback[] tools = toolProvider.getToolsForNode("plan");

        // Build prompt with system message describing available tools
        // Pass tools via ChatOptionsBuilder.withTools(tools)
        // Execute and return result
    }
}
```

**Acceptance criteria per node:**
- Node works identically when `NEXUS_ENABLED=false` (no tools, same logic)
- Node discovers and uses tools when `NEXUS_ENABLED=true`
- Node's system prompt describes what tools are available and when to use them
- Tool calls are logged with mission context (missionId, taskId, node name)

### 7.5 Modify: System Prompts

Each MCP-enabled node needs an updated system prompt that tells the LLM what tools it has and when to use them. These should be additive — the existing prompt logic stays, and tool guidance is appended when tools are present.

| Node | Tool Guidance to Add |
|------|---------------------|
| **Classify** | "You have access to mission history (Archive) and web search. Check Archive for similar past missions before classifying. If the mission references unfamiliar technology, search for it to understand the domain before routing." |
| **Plan** | "You have access to mission history (Archive) and web search. Before decomposing, check Archive for patterns from similar past missions. If the mission involves unfamiliar libraries or frameworks, search for them to understand constraints and best practices before generating tasks." |
| **Converge** | "You have access to the project database (Datacore). Use it to check mission checkpoint state and verify that agent outputs align with the task requirements." |
| **QualityGate** | "You have access to the mission Archive with write permission. After evaluating results: if APPROVED, record what patterns worked. If REJECTED, record what failed and why. The Worldmind learns from every mission." |
| **PostMission** | "You have access to GitHub (Xandar) and the mission Archive. Create a pull request with a clear title and description summarizing all changes. Record the mission outcome and PR link in the Archive." |

**Acceptance criteria:**
- Tool guidance is only present in the prompt when tools are actually available
- Prompts are externalized (not hardcoded in node classes) — either in `application.yml` or a dedicated prompt template file

### 7.6 Modify: InstructionBuilder

**File:** `InstructionBuilder.java` (or equivalent Goose config generator)

Currently generates Goose `profiles.json` with potentially multiple MCP server entries. Simplify to a single Nexus entry when MCP is enabled, or no MCP entry when disabled.

**Changes:**
- When `NEXUS_ENABLED=true`: Generate a single `nexus` MCP server entry with the agent-type-specific token
- When `NEXUS_ENABLED=false`: Generate profiles.json with no MCP servers (existing behavior)

**Generated config when enabled:**

```json
{
  "profile": "coder",
  "provider": "openai",
  "model": "qwen2.5-coder-32b",
  "mcpServers": {
    "nexus": {
      "url": "${NEXUS_URL}",
      "transport": "streamable-http",
      "headers": {
        "Authorization": "Bearer ${NEXUS_TOKEN}"
      }
    }
  }
}
```

**Acceptance criteria:**
- Coder/Tester containers connect to Nexus and see Datacore tools
- Researcher containers connect to Nexus and see Brave Search + Datacore tools
- Refactorer/Reviewer containers connect to Nexus and see zero tools (Nexus returns empty)
- When `NEXUS_ENABLED=false`, no Nexus entry in profiles.json

### 7.7 Modify: AgentDispatcher / DockerSandboxProvider

**File:** `DockerSandboxProvider.java` (or equivalent)

Pass Nexus environment variables into Sandbox containers at launch time.

**Changes:**
- Add `NEXUS_URL` and `NEXUS_TOKEN` to the container environment variables
- `NEXUS_TOKEN` is the agent-type-specific token from `NexusClientFactory.getAgentToken()`
- Only set these when `NEXUS_ENABLED=true`

```java
if (nexusProperties.isEnabled()) {
    env.add("NEXUS_URL=" + nexusProperties.getUrl());
    env.add("NEXUS_TOKEN=" + nexusClientFactory.getAgentToken(type));
}
```

**Acceptance criteria:**
- Container environment includes `NEXUS_URL` and `NEXUS_TOKEN` when enabled
- Container environment does not include Nexus variables when disabled
- Token is specific to the agent type being launched

### 7.8 Modify: Agent Entrypoint

**File:** `entrypoint.sh` (in Sandbox Docker image)

Update the entrypoint to conditionally include the Nexus MCP server in Goose config.

```bash
# If NEXUS_URL is set, configure Goose to connect to Nexus
if [ -n "$NEXUS_URL" ]; then
  MCP_CONFIG='"mcpServers": {
    "nexus": {
      "url": "'$NEXUS_URL'",
      "transport": "streamable-http",
      "headers": {
        "Authorization": "Bearer '$NEXUS_TOKEN'"
      }
    }
  }'
else
  MCP_CONFIG='"mcpServers": {}'
fi
```

**Acceptance criteria:**
- Goose connects to Nexus when `NEXUS_URL` is present
- Goose runs without MCP when `NEXUS_URL` is absent
- No npm packages installed in the container image

---

## 8. Maven Dependencies

Add to `pom.xml`:

```xml
<!-- Spring AI MCP Client — Streamable HTTP transport -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-client</artifactId>
</dependency>
```

Verify compatibility with existing Spring AI version in the project. The MCP client starter pulls in the Streamable HTTP transport automatically.

---

## 9. Application Configuration

Add to `application.yml`:

```yaml
spring:
  ai:
    mcp:
      client:
        enabled: ${NEXUS_ENABLED:false}
        type: SYNC
        toolcallback:
          enabled: false  # We manage tool injection per-node, not globally

worldmind:
  nexus:
    enabled: ${NEXUS_ENABLED:false}
    url: ${NEXUS_URL:}
    core:
      classify:
        token: ${NEXUS_CORE_CLASSIFY_TOKEN:}
      upload:
        token: ${NEXUS_CORE_UPLOAD_TOKEN:}
      plan:
        token: ${NEXUS_CORE_PLAN_TOKEN:}
      schedule:
        token: ${NEXUS_CORE_SCHEDULE_TOKEN:}
      converge:
        token: ${NEXUS_CORE_CONVERGE_TOKEN:}
      quality_gate:
        token: ${NEXUS_CORE_QUALITY_GATE_TOKEN:}
      postmission:
        token: ${NEXUS_CORE_POSTMISSION_TOKEN:}
    agents:
      coder:
        token: ${NEXUS_AGENT_CODER_TOKEN:}
      tester:
        token: ${NEXUS_AGENT_TESTER_TOKEN:}
      refactorer:
        token: ${NEXUS_AGENT_REFACTORER_TOKEN:}
      reviewer:
        token: ${NEXUS_AGENT_REVIEWER_TOKEN:}
      researcher:
        token: ${NEXUS_AGENT_RESEARCHER_TOKEN:}
```

---

## 10. Logging & Observability

### 10.1 Structured Logging

All MCP tool calls through Nexus are logged with MDC context:

```
missionId, taskId, nexusUser, tool, duration_ms, status
```

### 10.2 Health Check

Add a `NexusHealthIndicator` to Spring Boot Actuator:

```
GET /actuator/health → includes nexus.status, nexus.url, nexus.toolCount
```

Only active when `NEXUS_ENABLED=true`. Reports `UP` when Nexus responds to a tool listing request, `DOWN` otherwise.

### 10.3 Metrics

Prometheus counters via existing `WorldmindMetrics`:

```
worldmind_nexus_requests_total{user, status}
worldmind_nexus_request_duration_seconds{user, tool}
worldmind_nexus_errors_total{user, error_type}
```

---

## 11. Docker Compose Updates

Add Nexus as a service dependency:

```yaml
services:
  nexus:
    image: ${NEXUS_IMAGE}
    ports:
      - "8090:8090"
    volumes:
      - nexus-data:/data
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8090/health"]
      interval: 10s
      timeout: 5s
      retries: 3

  worldmind:
    depends_on:
      nexus:
        condition: service_healthy
    environment:
      - NEXUS_ENABLED=${NEXUS_ENABLED:-false}
      - NEXUS_URL=${NEXUS_URL:-http://nexus:8090/mcp}
      # ... all token env vars ...
```

Nexus is optional — when `NEXUS_ENABLED=false`, Worldmind starts without it and the `depends_on` can be removed for local dev.

---

## 12. Testing Requirements

### 12.1 Unit Tests

| Test | Validates |
|------|----------|
| `NexusClientFactoryTest` | Client creation, caching, token resolution, shutdown |
| `NovaForceToolProviderTest` | Tool discovery, empty array when disabled, callback wrapping |
| `NexusPropertiesTest` | Config binding, validation, defaults |

### 12.2 Integration Tests

| Test | Validates |
|------|----------|
| `NexusConnectionTest` | Spring Boot starts, connects to Nexus, lists tools per user |
| `GraphNodeToolInjectionTest` | Each modified node receives correct tools and executes with them |
| `SandboxNexusTest` | Container launches with Nexus env vars, Goose connects |
| `DisabledModeTest` | Everything works when `NEXUS_ENABLED=false` — no Nexus dependency |

### 12.3 End-to-End Test

Run a full mission with Nexus enabled. Verify:
- Classify checks Archive and searches for unfamiliar tech
- Plan uses Archive patterns and search results to generate better tasks
- Coder connects to Nexus and uses Datacore to check schemas
- QualityGate writes lessons learned to Archive
- PostMission creates a GitHub PR via Xandar and records it in Archive
- All tool calls appear in logs with correct MDC context

---

## 13. File Inventory

### New Files

| File | Purpose |
|------|---------|
| `src/.../novaforce/NexusProperties.java` | Configuration properties binding |
| `src/.../novaforce/NexusClientFactory.java` | MCP client creation and caching |
| `src/.../novaforce/NovaForceToolProvider.java` | Tool discovery and callback wrapping |
| `src/.../novaforce/NexusHealthIndicator.java` | Actuator health check |

### Modified Files

| File | Change |
|------|--------|
| `pom.xml` | Add `spring-ai-starter-mcp-client` dependency |
| `application.yml` | Add `worldmind.nexus.*` configuration block |
| `.env.example` | Add all Nexus environment variables |
| `ClassifyNode.java` | Inject tools, update system prompt |
| `PlanNode.java` | Inject tools, update system prompt |
| `ConvergeNode.java` | Inject tools, update system prompt |
| `QualityGateNode.java` | Inject tools, update system prompt |
| `PostMissionNode.java` | Inject tools, update system prompt |
| `InstructionBuilder.java` | Simplify to single Nexus MCP entry |
| `DockerSandboxProvider.java` | Pass Nexus env vars to containers |
| `entrypoint.sh` | Conditional Nexus config in Goose profiles |
| `docker-compose.yml` | Add Nexus service, update Worldmind env |

---

## 14. Rollout Plan

| Phase | Scope | Estimate |
|-------|-------|----------|
| **1. Config & Plumbing** | NexusProperties, NexusClientFactory, NovaForceToolProvider, .env, application.yml, pom.xml | 2 days |
| **2. Core Node Wiring** | Modify 5 graph nodes, update system prompts, add health check | 2-3 days |
| **3. Agent Wiring** | InstructionBuilder, AgentDispatcher, entrypoint.sh | 1-2 days |
| **4. Docker Compose** | Nexus service, dependency wiring, .env.example | 0.5 day |
| **5. Testing** | Unit, integration, E2E mission | 2-3 days |

**Total: 8-11 days**

Phases 2 and 3 can run in parallel after Phase 1 completes.

---

## 15. Future Iterations (Out of Scope)

- Nova Force UI dashboard (server registry, user matrix, audit log)
- Additional MCP servers (Probe, Tactician, Chronicle)
- Per-mission token scoping
- Nexus health/audit surfaced in Worldmind dashboard
- Dynamic Nexus user creation for specialized missions
