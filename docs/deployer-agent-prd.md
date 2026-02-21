# Worldmind Deployer Agent PRD

## Overview

Add automated Cloud Foundry deployment testing as the final phase of a Worldmind mission. When enabled, a new **DEPLOYER** agent will build and push the completed application to Tanzu Application Service (TAS), verify it starts successfully, and report deployment status.

This enables end-to-end validation: code generation → testing → review → **live deployment verification**.

## Goals

1. **Automated Deployment Testing** - Push completed applications to CF and verify they start
2. **Service Binding Awareness** - Detect required services from PRD/code and prompt user to pre-create them
3. **Manifest Generation** - Auto-generate `manifest.yml` if not created by a task, avoiding conflicts
4. **Predictable Routing** - Deploy with mission-based routes for easy identification
5. **Minimal User Intervention** - Leverage existing CF credentials; user only pre-creates services

## Non-Goals (Phase 1)

- Automated service instance creation (user pre-creates)
- Multi-space/multi-org deployments
- Blue-green or rolling deployments
- Automatic cleanup/deletion of test apps
- Support for Docker image deployments (JAR-based only initially)

---

## User Experience

### Mission Submission Form

**Change**: The existing `createCfDeployment` checkbox (currently labeled in code as "Create CF Deployment") should be relabeled to **"CF Deploy"** in the UI (`MissionForm.tsx`).

Currently, when checked, this appends a final CODER task that creates `manifest.yml` and `Staticfile`. This PRD replaces that behavior with a DEPLOYER agent that both creates the manifest (if needed) AND pushes to CF.

When checked:
- Mission will include a final DEPLOYER task after all code tasks complete
- Clarifying questions phase will ask about service bindings (already partially implemented in `GenerateClarifyingQuestionsNode`)

### Clarifying Questions (Service Detection)

During the clarifying questions phase, Worldmind will:

1. **Auto-detect services** from PRD content:
   - Database mentions (PostgreSQL, MySQL, MongoDB) → prompt for database service instance
   - Cache mentions (Redis, Memcached) → prompt for cache service instance
   - Message queue mentions (RabbitMQ, Kafka) → prompt for message queue service instance
   - Other detectable patterns (S3, Vault, etc.)

2. **Present to user**:
   ```
   Service Bindings Detected:
   
   Based on your PRD, this application appears to need:
   
   ☑ PostgreSQL Database
     Service instance name: [____________] (must be pre-created)
   
   ☐ Redis Cache  
     Service instance name: [____________] (must be pre-created)
   
   Please ensure these service instances exist in the target space 
   before the mission completes. The deployer will bind to them.
   
   [Add Service] [Remove incorrect detection]
   ```

3. **User can**:
   - Confirm detected services and provide instance names
   - Remove incorrectly detected services
   - Add services that weren't auto-detected
   - Skip service bindings entirely

### Mission Execution

When CF Deploy is enabled:

1. **Planning Phase**: Planner checks if any task creates `manifest.yml`
   - If YES: DEPLOYER will use the task-created manifest
   - If NO: DEPLOYER task includes manifest generation in its instructions

2. **Execution Phase**: All CODER/TESTER/REVIEWER tasks run as normal

3. **Deployment Phase** (new, after all tasks complete):
   - DEPLOYER agent activates
   - Builds the application (`mvn package` for Spring Boot)
   - Generates manifest if needed (with service bindings from clarifying answers)
   - Pushes to CF with predictable route
   - Waits for app to start and pass health check
   - Reports success/failure

### Deployment Route Convention

Apps are deployed with predictable routes based on mission ID:

```
{mission-id}.apps.{cf-domain}
```

Example: `wmnd-2026-0001.apps.tas-tdc.kuhn-labs.com`

This allows:
- Easy identification of test deployments
- No route conflicts between missions
- Simple manual cleanup (search for `wmnd-*` apps)

---

## Technical Design

### New Agent: DEPLOYER

#### Container Image: `agent-deployer`

Based on the existing agent base image (`docker/agent-base/`) with additions. The existing agent images are built from a shared Dockerfile that includes Goose, Python 3, and the SSE proxy. The deployer extends this:

```dockerfile
FROM agent-base:latest

# Install CF CLI v8 (Tanzu CLI)
# The cf binary can also be placed in the Worldmind project dir (gitignored)
# and copied into the image at build time for air-gapped environments.
COPY cf /usr/local/bin/cf
RUN chmod +x /usr/local/bin/cf

# Install Maven for building Java apps
RUN apt-get update && apt-get install -y maven && rm -rf /var/lib/apt/lists/*
```

The deployer reuses the same `entrypoint.sh` as other agents (Goose + developer extension). The CF CLI commands are executed by Goose via the developer extension's shell access, same as how agents run git commands today.

#### Agent Communication

The deployer uses the same communication pattern as all other agents:
1. Orchestrator stores instructions via `InstructionStore` 
2. CF task command curls `GET /api/internal/instructions/{key}` from orchestrator's public route
3. Agent (Goose) executes instructions
4. CF task command curls `PUT /api/internal/output/{key}` back to orchestrator

CF credentials are passed as environment variables to the CF task via `CloudFoundrySandboxProvider`, same as `GOOSE_PROVIDER`, `GOOSE_MODEL`, etc. The Goose agent uses shell commands to authenticate:
```bash
cf api "$CF_API_URL" --skip-ssl-validation
cf auth "$CF_USERNAME" "$CF_PASSWORD"
cf target -o "$CF_ORG" -s "$CF_SPACE"
```

#### Environment Variables (Inherited from Orchestrator)

| Variable | Description |
|----------|-------------|
| `CF_API_URL` | CF API endpoint |
| `CF_USERNAME` | CF authentication username |
| `CF_PASSWORD` | CF authentication password |
| `CF_ORG` | Target organization |
| `CF_SPACE` | Target space |

### Manifest Generation

When no task creates a manifest, the DEPLOYER generates one:

```yaml
applications:
- name: {mission-id}
  memory: 1G
  instances: 1
  path: target/{artifact}.jar
  buildpacks:
  - java_buildpack_offline
  routes:
  - route: {mission-id}.apps.{cf-domain}
  env:
    JBP_CONFIG_OPEN_JDK_JRE: '{ jre: { version: 21.+ } }'
  services:
  - {service-instance-1}
  - {service-instance-2}
```

**Defaults**:
- Memory: Based on app type (1G for simple, 2G for complex)
- Instances: 1 (testing only)
- Health check: HTTP to `/actuator/health` (Spring Boot) or port-based
- Buildpack: `java_buildpack_offline` (not `java_buildpack`)
- Route: `{mission-id}.apps.{cf-apps-domain}`

### Task Flow

Current flow (for reference):
- `classify_request` → `upload_context` → `clarify_requirements` → `generate_spec` → `plan_mission` → `await_approval` → `schedule_wave` → `parallel_dispatch` → `evaluate_wave` → (loop or converge)

With DEPLOYER, the change is in `PlanMissionNode.appendCfDeploymentTask()`. Currently this appends a CODER task that creates `manifest.yml`. The new behavior replaces this with a DEPLOYER task:

```
Mission Start
     │
     ▼
┌─────────────────┐
│ Clarifying Qs   │◄── Includes service binding questions
└────────┬────────┘    when CF Deploy enabled (already wired)
         │
         ▼
┌─────────────────┐
│ Plan Mission    │◄── Checks if manifest.yml task exists
└────────┬────────┘    Appends DEPLOYER task (not CODER)
         │
         ▼
┌─────────────────┐
│ Execute Tasks   │    CODER → TESTER → REVIEWER cycles
│ (wave loop)     │    Per-wave merge into main after each wave
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ DEPLOYER Wave   │    Scheduled as final wave (depends on all CODERs)
│                 │
│ 1. git clone    │    Fresh clone from main (all code merged)
│ 2. mvn package  │    Build the application
│ 3. gen manifest │    If no task created one
│ 4. cf push      │    Deploy to target space
│ 5. health check │    Verify app starts
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Converge Results│
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Mission Complete│
└─────────────────┘
```

### DEPLOYER Task Instructions

The orchestrator generates DEPLOYER instructions based on:

1. **App type** (detected from PRD/code):
   - Spring Boot JAR
   - (Future: Node.js, Python, static sites)

2. **Manifest availability**:
   - Use existing if a task created one
   - Generate if not

3. **Service bindings** (from clarifying answers):
   - List of service instance names to bind

Example instruction:

```markdown
# Deployment Task

Deploy the completed application to Cloud Foundry.

## Application Details
- Type: Spring Boot
- Artifact: target/todo-app-0.0.1-SNAPSHOT.jar
- Route: wmnd-2026-0001.apps.tas-tdc.kuhn-labs.com

## Service Bindings
- PostgreSQL: `todo-db` (user confirmed)

## Steps

1. Pull latest code from main branch
2. Build the application:
   ```bash
   ./mvnw clean package -DskipTests
   ```
3. Generate manifest.yml (no task created one):
   ```yaml
   applications:
   - name: wmnd-2026-0001
     memory: 1G
     instances: 1
     path: target/todo-app-0.0.1-SNAPSHOT.jar
     buildpacks:
     - java_buildpack_offline
     routes:
     - route: wmnd-2026-0001.apps.tas-tdc.kuhn-labs.com
     services:
     - todo-db
   ```
4. Deploy: `cf push -f manifest.yml`
5. Wait for app to start (health check must pass)
6. Report deployment URL and status

## Success Criteria
- App starts without crashing
- Health check passes within 5 minutes
- No staging errors
```

### Orchestrator Changes

#### Orchestrator Changes

**Service binding detection** is already partially implemented:
- `GenerateClarifyingQuestionsNode` (line 115) already injects a CF service binding question when `createCfDeployment=true`
- `PlanMissionNode.extractServiceNames()` already parses `cf_service_bindings` from clarifying answers
- Enhancement: improve auto-detection from PRD keywords (see Appendix) and present them as pre-filled suggestions

**WorldmindState additions**:
```java
// Whether manifest.yml is created by a planner task (set during planning)
Map.entry("manifestCreatedByTask", Channels.base(() -> false)),
```

Service bindings continue to flow through the existing `clarifyingAnswers` JSON field (key: `cf_service_bindings`).

**PlanMissionNode changes** — replace `appendCfDeploymentTask()`:

1. Check if any planned task targets `manifest.yml`:
   ```java
   boolean manifestTaskExists = tasks.stream()
       .flatMap(t -> t.targetFiles().stream())
       .anyMatch(f -> f.endsWith("manifest.yml"));
   ```

2. Create a DEPLOYER task (instead of current CODER task) that:
   - Depends on ALL CODER/REFACTORER tasks
   - Includes build + deploy + health check instructions
   - Includes manifest generation instructions only if `!manifestTaskExists`
   - Includes service binding info from `clarifyingAnswers`

**Agent app registration** — add to `manifest.yml`, `run.sh`, `SandboxManager`, and `CloudFoundrySandboxProvider`:
- New app: `agent-deployer` 
- New env var mapping for CF credentials: `CF_API_URL`, `CF_USERNAME`, `CF_PASSWORD`, `CF_ORG`, `CF_SPACE`
- New property: `agent-deployer-app` in vars

### Error Handling

| Error | Behavior |
|-------|----------|
| Build fails (`mvn package`) | Task FAILED, retry up to max iterations |
| Staging fails | Task FAILED, include staging logs in output |
| App crashes on start | Task FAILED, include crash logs |
| Health check timeout | Task FAILED, app may need more memory/time |
| Service binding fails | Task FAILED, prompt user to verify service exists |

**Mission Status**: If DEPLOYER fails, the overall mission is marked **FAILED** (deployment is considered part of mission success when CF Deploy is enabled).

---

## Implementation Phases

### Phase 1: Core Deployer (This PRD)

- [ ] Create `agent-deployer` Docker image with CF CLI
- [ ] Add CF Deploy checkbox to mission form (rename from Deployment Artifacts)
- [ ] Add service detection to clarifying questions
- [ ] Add manifest detection to planning phase
- [ ] Implement DEPLOYER task scheduling after converge
- [ ] Generate deployment instructions with manifest template
- [ ] Add health check verification
- [ ] Update manifest.yml for deployer app in orchestrator

### Phase 2: Enhanced Detection (Future)

- [ ] Better service detection from code analysis (not just PRD)
- [ ] Memory/resource estimation based on app complexity
- [ ] Support for application.yml/properties parsing

### Phase 3: Additional App Types (Future)

- [ ] Node.js applications
- [ ] Python applications
- [ ] Static site deployments
- [ ] Docker image deployments

### Phase 4: Advanced Features (Future)

- [ ] Automated service instance creation
- [ ] Blue-green deployments
- [ ] Auto-cleanup with TTL
- [ ] Multi-space deployments

---

## Configuration

### New Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `CF_APPS_DOMAIN` | (from CF API) | Domain for app routes |
| `DEPLOYER_HEALTH_TIMEOUT` | `300` | Seconds to wait for health check |
| `DEPLOYER_DEFAULT_MEMORY` | `1G` | Default app memory |

### Manifest Template Defaults

```yaml
# Stored in orchestrator, used when generating manifests
deployer:
  defaults:
    memory: 1G
    instances: 1
    healthCheckType: http
    healthCheckPath: /actuator/health
    buildpack: java_buildpack_offline
    javaVersion: 21
```

---

## Security Considerations

1. **CF Credentials**: Deployer uses same credentials as orchestrator (already secured via VCAP_SERVICES or env vars)

2. **Service Instance Names**: User-provided; deployer only binds, never creates

3. **Route Isolation**: Mission-based routes prevent conflicts; easily identifiable for cleanup

4. **No Sensitive Data in Manifests**: Service credentials come from CF service bindings, not manifest

---

## Success Metrics

1. **Deployment Success Rate**: % of CF Deploy missions that successfully deploy
2. **Time to Deploy**: Duration from converge completion to healthy app
3. **Service Detection Accuracy**: % of correctly auto-detected services

---

## Open Questions

1. Should we support a "deploy only" mode for re-deploying after manual fixes?
2. Should deployed apps have a standard naming prefix beyond mission ID?
3. Should we capture and display app logs in the UI during deployment?

---

## Appendix: Service Detection Patterns

| Service Type | PRD Keywords |
|--------------|--------------|
| PostgreSQL | `postgresql`, `postgres`, `psql`, `relational database` |
| MySQL | `mysql`, `mariadb` |
| MongoDB | `mongodb`, `mongo`, `document database` |
| Redis | `redis`, `cache`, `session store` |
| RabbitMQ | `rabbitmq`, `message queue`, `amqp` |
| S3/Blob | `s3`, `blob storage`, `object storage`, `file storage` |
