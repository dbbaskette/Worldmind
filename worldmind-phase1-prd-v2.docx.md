  
**WORLDMIND**

Phase 1: Buildpack Migration \+ Agentic Loop

Product Requirements Document

Replacing Docker-based Agent containers with

Cloud Foundry buildpack-staged droplets, CF Tasks,

and a closed-loop build/test/correct cycle

| Document | Product Requirements Document (PRD) |
| :---- | :---- |
| **Project** | Worldmind Phase 1: Buildpack Migration \+ Agentic Loop |
| **Author** | Dan Baskette, Technical Marketing, Broadcom |
| **Status** | DRAFT |
| **Version** | 2.0 |
| **Date** | February 2026 |
| **Repository** | github.com/dbbaskette/Worldmind |

**1\. Executive Summary**

Worldmind is an agentic code assistant that accepts natural language development requests and autonomously plans, implements, tests, and reviews code. Its worker agents (Agents) currently run as Docker containers managed by a DockerSandboxProvider. On Cloud Foundry, the system uses CF Tasks with Docker images pushed to a container registry.

This PRD defines Phase 1, which has two objectives:

**Objective 1 — Buildpack Migration:** Replace Docker-based Agent packaging with Cloud Foundry buildpack-staged droplets using the goose-buildpack. The existing CF Task dispatch model, git-based workspace coordination, and LangGraph4j orchestration remain unchanged.

**Objective 2 — Agentic Build/Test Loop:** Enable Agents to compile and test generated code within their task containers, feeding build failures back to the orchestrator for automated correction. This closes the agentic loop: generate → build → test → correct → re-test, producing verified code rather than untested artifacts.

**The core insight:** Agent containers carry no persistent state. All state is externalized to PostgreSQL (checkpoints) and Git (code artifacts). The Docker image is just packaging for a Goose binary plus environment constraints. A Cloud Foundry supply buildpack provides the same capability without Dockerfiles, image builds, or registry management. The apt buildpack adds build toolchains (Maven, JDK, Python, Node) so that Agents can compile and test the code they generate.

**2\. Problem Statement**

**2.1 Current Architecture**

Today, each Agent type (Coder, Tester, Reviewer, Researcher, Refactorer) has a dedicated Dockerfile in docker/agent-{type}/. These images share a common base (agent-base) that installs the Goose CLI binary, then add type-specific configurations. The images must be built, tagged, and pushed to a container registry (ghcr.io/dbbaskette) before deployment.

**2.2 Pain Points**

Docker image maintenance: Five Dockerfiles plus a base image that must be kept in sync. Every Goose version bump requires rebuilding and pushing all six images.

Registry dependency: The CF deployment requires access to an external container registry (GHCR), adding a network dependency and credential management overhead.

Inconsistent deployment model: The Worldmind core application deploys via buildpacks (java\_buildpack), but its worker agents deploy via Docker images. This creates two separate build and deployment pipelines for a single system.

GenAI tile integration gap: Docker-based Agents require manual configuration of LLM endpoints and API keys via environment variables. The GenAI tile’s VCAP\_SERVICES auto-discovery is not leveraged for worker agents.

No build verification: Agents generate code but cannot verify it compiles or passes tests within the same task execution. Build verification requires a separate external step, breaking the agentic feedback loop.

One-shot generation: Without a closed-loop correction cycle, code quality depends entirely on first-pass generation accuracy. There is no mechanism for a Agent to learn from compilation errors or test failures within a single mission.

**2.3 Opportunity**

The goose-buildpack (github.com/cpage-pivotal/goose-buildpack) is a Cloud Foundry supply buildpack that installs the Goose CLI binary into any CF application. It supports GenAI tile auto-discovery, MCP server configuration via service bindings, and Goose skill files for agent personality customization. Combined with the apt buildpack for build toolchain installation and CF’s instances: 0 pattern for task-only apps, it provides everything Agents need — including the ability to compile and test generated code — without Docker.

**3\. Users and Personas**

Worldmind serves multiple user types, each interacting with the system at a different level of abstraction. Understanding these personas is essential for scoping Phase 1 correctly — the buildpack migration and agentic loop are invisible to end users but directly affect operators and developers extending the system.

**3.1 Mission Requester (End User)**

The primary user of Worldmind. This person describes what they want built in natural language: “Build me a Spring Boot REST API that manages inventory with CRUD operations and connects to PostgreSQL.” They do not know or care about Agents, buildpacks, or CF Tasks. They interact through the Worldmind UI or API, approve the generated plan, and receive a link to the completed code (Phase 1\) or deployed application (Phase 2).

**Typical profiles:** Product managers, business analysts, junior developers, technical marketers building demos, sales engineers preparing proof-of-concept environments.

**Phase 1 impact:** None. The requester’s experience is unchanged. They benefit indirectly from the agentic loop because code quality improves — they receive code that compiles and passes tests rather than untested first-pass output.

**3.2 Platform Operator**

The person responsible for deploying and maintaining the Worldmind system on Cloud Foundry. They run the deployment scripts, manage service bindings, monitor CF Task execution, and troubleshoot failures. This is the persona most directly affected by the buildpack migration.

**Typical profiles:** Platform engineers, DevOps engineers, SREs, technical marketing engineers running demo environments.

**Phase 1 impact:** Significant. The operator’s workflow changes from building and pushing Docker images to running cf push with manifests. They manage the apt.yml package list, tune memory and disk quotas for build tools, configure the GenAI tile and Nexus service bindings, and monitor the agentic retry loops for runaway tasks.

**3.3 System Developer**

A developer extending Worldmind itself — adding new Agent types, modifying the LangGraph4j orchestration, or integrating new MCP servers. They work directly with the codebase and need to understand the buildpack chain, skill file format, and build result contract.

**Typical profiles:** Backend engineers, AI/ML engineers, contributors to the Worldmind open source project.

**Phase 1 impact:** Moderate. They must understand the new manifest structure, buildpack chain, and agentic loop contract (build-result.json) when adding capabilities. The shift from Dockerfiles to .goose-config.yml changes how new Agent types are created.

**3.4 Agent (Agent Persona)**

Not a human user, but a critical persona in the system. Each Agent type has a distinct personality, skill set, and permission boundary defined by its Goose skill file. Understanding Agents as personas helps clarify why the system is structured the way it is — each one is a specialist with a specific mandate.

| Agent | Role | Persona Description |
| :---- | :---- | :---- |
| **Coder** | Code generator | The builder. Writes production code from tasks. Compiles its own output to verify correctness. Takes feedback from Tester test failures and corrects. |
| **Tester** | Test engineer | The challenger. Writes and executes test suites. Its job is to find what’s broken. Reports structured test results that drive the correction loop. |
| **Reviewer** | Code reviewer | The auditor. Reviews code for quality, security, and adherence to standards. Read-only access. Provides qualitative feedback that informs the QualityGate’s approval decision. |
| **Researcher** | Researcher | The scout. Investigates APIs, libraries, and patterns needed for the mission. Read-only access. Produces research briefs that inform Coder’s implementation choices. |
| **Refactorer** | Refactorer | The optimizer. Restructures existing code for clarity, performance, or maintainability. Can compile and test to verify refactoring doesn’t break anything. |

**4\. System Components**

Worldmind is composed of several distinct components that work together to accept a natural language request and produce verified, tested code. This section describes each component, its responsibility, and how it interacts with the rest of the system.

**10.1 Worldmind Core**

**Runtime:** Spring Boot application deployed via java\_buildpack on Cloud Foundry.

The central orchestrator. Worldmind Core hosts the LangGraph4j state machine (WorldmindGraph), the REST API for mission submission, and the task dispatch layer. It receives natural language requests from users, runs them through the classification and planning stages, dispatches Agent tasks via CF Tasks, monitors their completion, and manages the agentic retry loop. Core is the only long-running process in the system — everything else is ephemeral.

**Key sub-components:** WorldmindGraph (LangGraph4j state machine), MissionEngine (mission lifecycle management), CloudFoundryTaskProvider (CF Task dispatch), InstructionBuilder (task generation), QualityGate (quality gate evaluation).

**10.2 WorldmindGraph (LangGraph4j Orchestrator)**

**Runtime:** Embedded within Worldmind Core.

The stateful directed graph that defines the mission workflow. Each node in the graph represents a stage: classify, plan, dispatch, converge, evaluate, quality_gate. Edges define transitions, including the conditional edges that implement the agentic retry loop — a failed build result triggers a transition back to the dispatch node with error context appended to the task. State is checkpointed to PostgreSQL, making the workflow resumable after failures.

**10.3 Agents (Worker Agents)**

**Runtime:** CF Tasks run against buildpack-staged apps (instances: 0). Ephemeral.

The workforce. Each Agent is a Goose CLI session running inside a CF Task container. It receives a task (what to do), a workspace (git branch to clone), and a skill file (who it is). It executes the task, optionally builds and tests the output, commits results to the branch, writes a build-result.json, and exits. Agents have no persistent state — they are stateless workers that read from and write to Git.

**4.4 Nexus (MCP Gateway)**

**Runtime:** Deployed as a CF app or external service. Registered as a CF user-provided service.

The tool hub. Nexus is a Model Context Protocol (MCP) gateway that aggregates multiple MCP servers behind a single endpoint. Agents connect to Nexus to access tools: Terrain (filesystem operations on the git workspace), Chronicle (git operations), and Spark (shell command execution). Nexus handles authentication, rate limiting, and tool routing. By registering Nexus as a CF user-provided service, all Agents discover it automatically via VCAP\_SERVICES.

**4.5 GenAI Tile**

**Runtime:** Tanzu Platform managed service.

The LLM provider. The GenAI tile is a Tanzu Platform service that provides access to large language models. It exposes a config\_url endpoint that describes available models and their capabilities (text generation, tool calling, embeddings). The goose-buildpack auto-discovers this service via VCAP\_SERVICES and configures Goose to use the first model with TOOLS capability. This eliminates the need to hardcode API keys or model endpoints in Agent configuration.

**4.6 Workspace Repository (Git)**

**Runtime:** External Git repository (GitHub, GitLab, or Gitea).

The coordination layer. Every mission gets a dedicated branch. Agents clone the branch, make changes, and push. The orchestrator reads results from the branch. This provides the hard isolation boundary between Agent tasks — a Agent can only affect its assigned branch, and the orchestrator controls merges. The workspace also serves as the audit trail: every code change, test result, and build artifact is committed with a descriptive message.

**4.7 PostgreSQL (State Store)**

**Runtime:** CF managed service (Tanzu Postgres or equivalent).

The persistence layer. PostgreSQL stores LangGraph4j checkpoints (workflow state), mission metadata (status, retry counts, timestamps), and the QualityGate’s evaluation history. This is what makes the orchestration resumable — if Worldmind Core restarts, it can resume in-flight missions from the last checkpoint.

**4.8 Component Interaction Summary**

Mission Requester  
       │  
       │  "Build me an inventory API"  
       ▼  
  Worldmind Core (Spring Boot / java\_buildpack)  
       │  
       ├── WorldmindGraph: classify → plan → dispatch  
       │  
       ├── CloudFoundryTaskProvider: cf run-task agent-coder  
       │         │  
       │         ▼  
       │    Agent Task (apt \+ goose \+ binary buildpacks)  
       │         │  
       │         ├── Goose CLI session  
       │         ├── Connects to Nexus MCP (via service binding)  
       │         ├── Uses GenAI tile LLM (via service binding)  
       │         ├── Clones workspace branch (Git)  
       │         ├── Generates code → builds → tests → self-corrects  
       │         ├── Commits build-result.json \+ code to branch  
       │         └── Task exits  
       │  
       ├── Core reads build-result.json from branch  
       ├── If fail: re-dispatch with error feedback (outer loop)  
       ├── If pass: converge → Reviewer review → QualityGate approval  
       │  
       └── PostgreSQL: checkpoint state at each transition

**5\. Goals and Non-Goals**

**5.1 Goals**

G1: Replace all five Agent Docker images with buildpack-staged CF apps configured via .goose-config.yml and skill files.

G2: Install build toolchains (JDK, Maven, Python, Node) via the apt buildpack so Agents can compile and test generated code within task containers.

G3: Implement a closed-loop agentic cycle in LangGraph4j: generate → build → test → feedback → correct → re-test, with a configurable maximum retry count.

G4: Maintain the existing CF Task dispatch model in CloudFoundryTaskProvider with zero changes to the orchestration layer’s dispatch interface.

G5: Leverage GenAI tile service bindings for automatic LLM credential discovery in Agent workers.

G6: Register Nexus (the MCP gateway) as a CF user-provided service that Agents auto-discover via VCAP\_SERVICES.

G7: Preserve all existing Agent permission constraints (filesystem paths, read/write vs. read-only, command allowlists).

G8: Eliminate the Docker image build and registry push steps from the deployment pipeline.

**5.2 Non-Goals (Phase 2\)**

Deploy Agent (Nova): A new agent type that performs cf push of generated applications to the platform. Deferred to Phase 2\.

Configuration UI: A user-facing interface for reviewing and overriding application properties before deployment. Deferred to Phase 2\.

Application deployment: Staging and deploying the generated application as a running CF app. Phase 1 verifies code compiles and tests pass; Phase 2 deploys it.

Docker provider removal: The DockerSandboxProvider remains functional for local development. Only the CF deployment path changes.

**6\. Architecture**

**10.1 Before and After**

| Aspect | Current (Docker) | Phase 1 (Buildpack) |
| :---- | :---- | :---- |
| **Packaging** | Dockerfile per Agent type | .goose-config.yml \+ skill file per type |
| **Binary delivery** | Baked into Docker image at build | Installed by goose-buildpack at staging |
| **Build tools** | Baked into Docker image | Installed by apt buildpack at staging |
| **Registry** | ghcr.io/dbbaskette | None — CF droplet store |
| **LLM credentials** | Env vars in manifest | Auto-discovered via GenAI binding |
| **MCP config** | Env vars / hardcoded | Service binding (cf cups) |
| **Build verification** | None (generate only) | mvn compile, pytest, npm test in-task |
| **Correction loop** | None (one-shot) | Retry with error feedback, max N attempts |
| **Dispatch** | cf run-task against Docker image | cf run-task against staged droplet |
| **Orchestration** | LangGraph4j \+ CloudFoundryTaskProvider | LangGraph4j \+ CloudFoundryTaskProvider (unchanged) |

**10.2 Component Mapping**

| Component | Change | Details |
| :---- | :---- | :---- |
| docker/agent-\* | Remove | Delete all Dockerfiles; replace with manifests/ |
| manifests/ (new) | Add | Per-Agent manifest.yml, .goose-config.yml, and apt.yml |
| CloudFoundryTaskProvider | Modify | Update task command to invoke Goose from buildpack path |
| InstructionBuilder | Modify | Map Agent types to skill names instead of Docker images |
| WorldmindGraph | Modify | Add correction loop edges: test failure → re-dispatch with feedback |
| MissionEngine | Modify | Support retry count tracking and build result parsing |
| run.sh \--cf | Modify | Replace docker build/push with cf push of Agent apps |

**10.3 Deployment Topology**

CF Apps:  
  worldmind-core        ─ Spring Boot app (java\_buildpack), instances: 1  
  agent-coder       ─ Goose worker (apt \+ goose \+ binary buildpacks), instances: 0  
  agent-tester    ─ Goose worker (apt \+ goose \+ binary buildpacks), instances: 0  
  agent-reviewer       ─ Goose worker (apt \+ goose \+ binary buildpacks), instances: 0  
  agent-researcher       ─ Goose worker (apt \+ goose \+ binary buildpacks), instances: 0  
  agent-refactorer       ─ Goose worker (apt \+ goose \+ binary buildpacks), instances: 0  
   
CF Services:  
  worldmind-postgres    ─ PostgreSQL (checkpointing)  
  worldmind-genai       ─ GenAI tile service instance  
  nexus-mcp            ─ User-provided service (MCP gateway)  
   
CF Tasks (dispatched by worldmind-core):  
  git clone → goose session → build/test → git push results → exit  
  On failure: core parses errors → re-dispatches with feedback → retry

**7\. Buildpack Migration Requirements**

**7.1 Agent App Configuration**

Each Agent type is deployed as a CF app with instances: 0 (task-only, no long-running process). Three supply buildpacks are chained: the apt buildpack installs build toolchains, the goose-buildpack installs the Goose CLI binary, and binary\_buildpack satisfies CF’s final buildpack requirement.

**7.1.1 Manifest Structure**

\# manifests/agent-coder/manifest.yml  
applications:  
\- name: agent-coder  
  no-route: true  
  health-check-type: process  
  instances: 0  
  memory: 1G  
  disk\_quota: 2G  
  buildpacks:  
    \- apt\_buildpack  
    \- goose-buildpack  
    \- binary\_buildpack  
  services:  
    \- worldmind-genai  
    \- nexus-mcp  
  env:  
    GOOSE\_ENABLED: true

**7.1.2 Build Toolchain via apt Buildpack**

Each Agent app includes an apt.yml that declares the build toolchains needed to compile and test generated code. The apt buildpack installs these packages into the droplet during staging, making them available to every CF Task run against the app.

\# manifests/agent-coder/apt.yml  
packages:  
  \- openjdk-21-jdk-headless  
  \- maven  
  \- python3  
  \- python3-pip  
  \- python3-venv  
  \- nodejs  
  \- npm  
  \- git

These are build-time tools, not application runtimes. The Agent is not running a Java app — it is running Goose, which invokes mvn compile, pytest, or npm test as shell commands against the generated code. The JDK and Maven are CLI tools in the same way that git is a CLI tool.

**Memory and disk allocation:** Build tools increase the droplet size and task memory requirements. Memory is set to 1G (up from 512M) and disk\_quota to 2G to accommodate Maven dependency downloads and compilation. These values should be tuned during M1 validation.

**7.1.3 Goose Configuration per Agent**

\# manifests/agent-coder/.goose-config.yml  
goose:  
  enabled: true  
  version: "latest"  
  skills:  
    \- name: coder  
      description: "Code generation Agent"  
      content: |  
        You are Coder, a code generation Agent.  
        You write production-quality code based on tasks.  
        PERMISSIONS: Read/write src/, lib/, app/  
        RESTRICTIONS: Do not modify test files.  
        After writing code, run the appropriate build command  
        to verify compilation. If compilation fails, fix the  
        errors and retry until it compiles cleanly.  
        BUILD COMMANDS:  
          Java/Spring: mvn compile \-q  
          Python: python \-m py\_compile \<files\>  
          Node: npm install && npm run build  
        Always commit your changes with a descriptive message.

**7.1.4 Complete Agent Configuration Matrix**

| Agent | Role | Write Paths | Build Cmd | Test Cmd | MCP Tools |
| :---- | :---- | :---- | :---- | :---- | :---- |
| **Coder** | Code generation | src/, lib/, app/ | mvn compile | — | Terrain, Chronicle |
| **Tester** | Test write/run | test/, spec/ | mvn compile | mvn test | Terrain, Spark |
| **Reviewer** | Code review | None | — | — | Terrain |
| **Researcher** | Research | None | — | — | Terrain |
| **Refactorer** | Refactoring | src/, lib/, app/ | mvn compile | mvn test | Terrain, Chronicle |

**7.2 Service Bindings**

**7.2.1 GenAI Tile Binding**

All Agent apps bind to the same GenAI tile service instance (worldmind-genai). The goose-buildpack automatically discovers the binding at runtime by parsing VCAP\_SERVICES, calling the config\_url endpoint to discover available models, and selecting the first model with TOOLS capability. No API keys or endpoint URLs need to be specified in environment variables.

**7.2.2 Nexus MCP Gateway Binding**

The Nexus MCP gateway is registered as a CF user-provided service. Agent apps bind to this service, and the goose-buildpack auto-discovers it as an MCP server when the credentials contain a uri field and an X-API-KEY header.

\# One-time setup  
cf create-user-provided-service nexus-mcp \\  
  \-p '{  
    "uri": "https://nexus-gateway.apps.internal/mcp",  
    "headers": {  
      "X-API-KEY": "your-nexus-api-key"  
    }  
  }'

**7.3 Task Dispatch**

**7.3.1 CloudFoundryTaskProvider Changes**

The existing CloudFoundryTaskProvider dispatches work by calling cf run-task against a target app name. Today it targets Docker-image-based apps. After migration, it targets buildpack-staged apps. The task command changes from invoking the Docker entrypoint to invoking the Goose binary that the buildpack installed.

**7.3.2 Task Lifecycle**

1\. Worldmind core creates a mission branch in the workspace git repository.

2\. Core dispatches a CF Task against the appropriate Agent app (e.g., agent-coder).

3\. The task starts, Goose clones the mission branch, executes the task, runs build verification, and pushes results (including build output).

4\. The task exits. Core polls for task completion and collects results from the git branch.

5\. If build/test failed: core extracts error output, appends it to the task as feedback, and re-dispatches (up to max retries).

6\. If build/test passed: results converge, QualityGate evaluates quality, and the next wave dispatches.

**7.4 Deployment Script Changes**

\# Before (Docker)  
docker build \-t ghcr.io/dbbaskette/agent-base docker/agent-base/  
docker build \-t ghcr.io/dbbaskette/agent-coder docker/agent-coder/  
docker push ghcr.io/dbbaskette/agent-coder  
\# ... repeat for all 5 types  
   
\# After (Buildpack)  
cf push \-f manifests/agent-coder/manifest.yml  
cf push \-f manifests/agent-tester/manifest.yml  
cf push \-f manifests/agent-reviewer/manifest.yml  
cf push \-f manifests/agent-researcher/manifest.yml  
cf push \-f manifests/agent-refactorer/manifest.yml

**8\. Agentic Build/Test Loop**

**10.1 Overview**

The agentic loop is the core value proposition of Phase 1 beyond packaging. Without it, Agents are code generators that produce unverified output. With it, Worldmind delivers code that compiles cleanly and passes its test suite. The loop operates at two levels: the inner loop (within a single Agent task) and the outer loop (across wave re-dispatches by the orchestrator).

**10.2 Inner Loop: Within a Agent Task**

When a Agent (Coder, Tester, or Refactorer) generates or modifies code, Goose’s skill instructions direct it to immediately run the appropriate build command. If the build fails, Goose reads the error output and attempts to fix the code. This happens entirely within a single CF Task execution — Goose is an agentic LLM session that can iterate on its own work.

Inner Loop (within Goose session):  
   
  1\. Goose generates/modifies code per task  
  2\. Goose runs: mvn compile \-q  (or pytest, npm test, etc.)  
  3\. If BUILD PASSES:  
       → git add, git commit, git push  
       → Write build-result.json: { status: "pass" }  
       → Task exits successfully  
  4\. If BUILD FAILS:  
       → Goose reads compiler/test error output  
       → Goose modifies code to fix errors  
       → Go to step 2 (up to 3 internal retries)  
  5\. If still failing after internal retries:  
       → git push current state  
       → Write build-result.json: { status: "fail", errors: "..." }  
       → Task exits with failure code

The inner loop retry count (default: 3\) is configured in the Goose skill file. This keeps the task execution bounded — a Agent will not spin indefinitely trying to fix a fundamental design error.

**10.3 Outer Loop: Orchestrator Re-Dispatch**

When the inner loop exhausts its retries and the task still fails, the orchestrator (LangGraph4j WorldmindGraph) receives the failure. It can then re-dispatch with additional context: the error output, suggestions from Reviewer’s code review, or revised tasks from the Planner.

Outer Loop (LangGraph4j orchestration):  
   
  Wave 1: Coder generates code, Tester writes tests  
     │  
     ├─ Coder inner loop: compile → fix → compile → PASS  
     └─ Tester inner loop: compile tests → PASS  
     │  
  Wave 2: Tester runs tests against Coder’s code  
     │  
     ├─ Tests pass → proceed to Wave 3 (Reviewer review)  
     └─ Tests fail → extract errors → re-dispatch Coder  
        with test failures as feedback  
     │  
  Correction Wave: Coder fixes code based on test feedback  
     │  
     ├─ Coder inner loop: fix → compile → PASS  
     └─ Re-run Tester → tests pass → proceed  
     │  
  Wave 3: Reviewer reviews, QualityGate approves  
   
  Max outer retries: 3 (configurable per mission)

**8.4 Build Result Contract**

Agents communicate build results to the orchestrator via a build-result.json file committed to the mission branch. This provides a structured, parseable contract that the orchestrator can read without scraping task logs.

// build-result.json (on success)  
{  
  "status": "pass",  
  "build\_command": "mvn compile \-q",  
  "test\_command": "mvn test",  
  "tests\_run": 12,  
  "tests\_passed": 12,  
  "tests\_failed": 0,  
  "duration\_seconds": 34  
}

// build-result.json (on failure)  
{  
  "status": "fail",  
  "build\_command": "mvn compile \-q",  
  "errors": \[  
    "src/main/java/com/example/Service.java:42: cannot find symbol",  
    "  symbol: variable customerRepository"  
  \],  
  "inner\_retries\_exhausted": 3,  
  "duration\_seconds": 87  
}

**8.5 Build Command Detection**

Agents detect the project type and select the appropriate build command based on the presence of build files in the workspace. This detection is encoded in the Goose skill instructions:

| Indicator File | Project Type | Build Command | Test Command |
| :---- | :---- | :---- | :---- |
| pom.xml | Java / Maven | mvn compile \-q | mvn test |
| build.gradle | Java / Gradle | gradle build \-x test | gradle test |
| requirements.txt | Python | python \-m py\_compile | pytest |
| setup.py / pyproject.toml | Python (packaged) | pip install \-e . | pytest |
| package.json | Node.js | npm install && npm run build | npm test |
| go.mod | Go | go build ./... | go test ./... |

**8.6 Retry Configuration**

| Parameter | Default | Description |
| :---- | :---- | :---- |
| inner\_retry\_max | 3 | Max build/fix attempts within a single Goose session |
| outer\_retry\_max | 3 | Max wave re-dispatches by the orchestrator |
| task\_timeout\_seconds | 600 | Max duration for a single CF Task before forced termination |
| mission\_timeout\_seconds | 3600 | Max duration for an entire mission including all retries |

All retry parameters are configurable per mission via the Planner’s mission spec. The defaults are conservative — 3 inner retries × 3 outer retries \= a maximum of 9 build attempts before the mission is marked as failed and escalated for human review.

**9\. Permission Model**

In the Docker model, Agent permissions are enforced by the container’s filesystem mounts and command allowlists. In the buildpack model, permissions are enforced through Goose skill instructions and the command allowlist configuration in .goose-config.yml.

The enforcement mechanism shifts from infrastructure-level (container mounts) to application-level (Goose instruction adherence \+ allowlists). This is a trade-off: Docker provides hard filesystem boundaries, while skill-based instructions rely on the LLM following its instructions. However, the git-based workspace model inherently limits what a Agent can affect — it can only modify files in its cloned workspace branch. The orchestrator controls which changes are merged.

Build tool execution (mvn, pytest, npm) is explicitly added to the command allowlist for Coder, Tester, and Refactorer Agents. Reviewer and Researcher retain read-only access with no build tool permissions.

**10\. Buildpack Requirements**

**10.1 Buildpack Chain**

Each Agent app uses three buildpacks in the following order:

| Order | Buildpack | Type | Purpose |
| :---- | :---- | :---- | :---- |
| 1 | apt\_buildpack | Supply | Installs JDK, Maven, Python, Node, npm, git via apt |
| 2 | goose-buildpack | Supply | Installs Goose CLI, configures GenAI and MCP bindings |
| 3 | binary\_buildpack | Final | Satisfies CF staging requirement for a final buildpack |

**10.2 Capability Verification**

| Capability | Status | Notes |
| :---- | :---- | :---- |
| apt package installation | Available | Standard CF apt\_buildpack |
| Goose binary installation | Available | \~30MB native Rust binary from GitHub releases |
| GenAI auto-discovery | Available | Parses VCAP\_SERVICES for GenAI bindings |
| MCP via service binding | Available | Auto-configures from uri \+ X-API-KEY |
| Skill file support | Available | Inline, file-based, and git-based |
| instances: 0 compatibility | To verify | Must stage without requiring a running process |
| apt \+ goose chain compatibility | To verify | Both supply buildpacks must coexist in deps/ |
| Maven in apt packages | To verify | Verify apt maven package includes mvn CLI on PATH |

**10.3 Network Considerations**

The goose-buildpack downloads the Goose binary from GitHub releases during CF staging. The apt buildpack downloads packages from Ubuntu repositories. If the staging environment has restricted outbound network access, both the Goose binary and apt packages must be vendored or hosted on internal servers.

At task runtime, Maven will download dependencies from Maven Central (or a configured mirror). For air-gapped environments, a Nexus or Artifactory repository mirror should be configured via MAVEN\_OPTS or settings.xml injected through the manifest’s env block.

**11\. Migration Plan**

**11.1 Phase 1 Milestones**

| \# | Milestone | Deliverable | Dependencies |
| :---- | :---- | :---- | :---- |
| M1 | Buildpack chain validation | Single Agent (Coder) staged with apt \+ goose \+ binary buildpacks | Buildpack access |
| M2 | Build toolchain validation | Coder task runs mvn compile against generated code inside container | M1 complete |
| M3 | GenAI \+ MCP bindings verified | Coder uses LLM via GenAI binding, Nexus via service binding | GenAI tile, Nexus |
| M4 | Inner agentic loop working | Coder compiles, detects errors, self-corrects within single task | M2 \+ M3 complete |
| M5 | All five Agents migrated | manifests/ directory with all configs; docker/ deprecated | M4 complete |
| M6 | Outer loop in orchestrator | LangGraph4j re-dispatches on build failure with error feedback | M5 complete |
| M7 | End-to-end mission validated | Full cycle: plan → generate → build → test → correct → review → quality_gate | M6 complete |
| M8 | Deployment script updated | run.sh \--cf uses cf push; no docker build/push | M7 complete |

**11.2 Rollback Strategy**

The Docker-based deployment remains functional throughout Phase 1\. The DockerSandboxProvider is not modified. If the buildpack migration encounters blocking issues, the CF deployment reverts to Docker images by switching the SANDBOX\_PROVIDER configuration back to docker. The manifests/ directory and docker/ directory coexist until Phase 1 is validated and the Docker path is formally deprecated.

**12\. Risks and Mitigations**

| ID | Risk | Severity | Mitigation |
| :---- | :---- | :---- | :---- |
| R1 | Binary download blocked during staging | High | Vendor Goose binary into buildpack; host apt mirror internally |
| R2 | apt \+ goose buildpack chain incompatibility | High | Validate in M1; fallback to single custom buildpack with both |
| R3 | Maven dependency download at task runtime | Medium | Configure Maven mirror via settings.xml; pre-cache common deps in droplet |
| R4 | Droplet size exceeds quota with build tools | Medium | Use headless JDK; increase disk\_quota; trim unnecessary apt packages |
| R5 | Inner loop infinite retry on unfixable error | Medium | Hard cap at inner\_retry\_max (3); task\_timeout\_seconds (600) as backstop |
| R6 | Permission enforcement weaker than Docker | Medium | Git branch model provides hard boundary; skill \+ allowlists provide soft |
| R7 | GenAI auto-discovery selects wrong model | Low | Override with GOOSE\_MODEL env var if auto-selection is incorrect |
| R8 | Build errors too cryptic for LLM correction | Medium | Skill instructions include guidance on parsing common error patterns; outer loop provides human escalation path |

**13\. Success Criteria**

Phase 1 is complete when all of the following are true:

SC1: All five Agent types (Coder, Tester, Reviewer, Researcher, Refactorer) are deployed as buildpack-staged CF apps with instances: 0, using the apt \+ goose \+ binary buildpack chain.

SC2: Build toolchains (JDK, Maven, Python, Node) are available inside Agent task containers and can compile generated code.

SC3: The inner agentic loop works: a Agent generates code, runs the build, detects compilation errors, self-corrects, and produces a clean build within a single task execution.

SC4: The outer agentic loop works: when a Agent task fails after exhausting inner retries, the orchestrator re-dispatches with error feedback, and the correction wave produces passing code.

SC5: A build-result.json contract is committed to the mission branch by each build-capable Agent, and the orchestrator correctly parses it to determine pass/fail.

SC6: LLM credentials are sourced exclusively from the GenAI tile service binding — no API keys in manifests or environment variables.

SC7: Nexus MCP gateway is accessed via CF service binding — no hardcoded MCP endpoints.

SC8: A full mission lifecycle (classify → plan → generate → build → test → correct → review → quality_gate) completes successfully, producing code that compiles and passes its test suite.

SC9: The run.sh \--cf script deploys the full system without any docker build or docker push commands.

SC10: Wave-based parallel dispatch works correctly — multiple Agent tasks run concurrently, each with independent build/test execution.

**14\. Future Phases (Out of Scope)**

**Phase 2: Deploy**

A new Nova Agent type that pulls verified code from the mission branch, detects the appropriate buildpack, and performs cf push to deploy the application to the platform. Includes a configuration UI for reviewing and overriding application properties (service bindings, environment variables, scaling parameters) before deployment. Nova consumes the build-result.json produced in Phase 1 to confirm the code is deployment-ready.

**Phase 3: Full Platform Integration**

Service catalog integration for automatic service provisioning (databases, message queues, caches). Multi-foundation deployment support. Canary deployment strategies with automated rollback. Integration with Tanzu Application Service metrics and logging for deployed applications.

**15\. References**

Worldmind repository: github.com/dbbaskette/Worldmind

goose-buildpack repository: github.com/cpage-pivotal/goose-buildpack

Goose AI agent: github.com/block/goose

CF apt buildpack: github.com/cloudfoundry/apt-buildpack

Cloud Foundry Tasks documentation: docs.cloudfoundry.org/devguide/using-tasks.html

Cloud Foundry supply buildpacks: docs.cloudfoundry.org/buildpacks/understand-buildpacks.html

Tanzu GenAI tile documentation: docs.vmware.com/en/VMware-Tanzu-Platform

LangGraph4j: github.com/bsorrentino/langgraph4j