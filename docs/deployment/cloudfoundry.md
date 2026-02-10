# Deploying Worldmind on Cloud Foundry

## Prerequisites

- **CF CLI v8+** installed and authenticated (`cf login`)
- **Java 21** and **Maven** installed locally (for building the orchestrator JAR)
- **Docker images** for centurions pushed to a registry accessible by the CF foundation:
  - `worldmind/centurion-forge:latest`
  - `worldmind/centurion-gauntlet:latest`
  - `worldmind/centurion-vigil:latest`
- CF foundation must have **Diego Docker support enabled**:
  ```bash
  cf feature-flag diego_docker
  ```
- PostgreSQL service tile available in the marketplace
- GenAI / LLM service tile available in the marketplace (OpenAI-compatible)

## 1. Create Services

Two services are required:

| Service Instance     | Purpose                                           |
|----------------------|---------------------------------------------------|
| `worldmind-postgres` | PostgreSQL database for checkpoints and state      |
| `worldmind-model`    | OpenAI-compatible LLM (bound to orchestrator + centurions) |

Create them (adjust plan names to match your marketplace):

```bash
cf create-service postgres on-demand-postgres-db worldmind-postgres
cf create-service genai standard worldmind-model
```

Verify both services reach `create succeeded`:

```bash
cf services
```

## 2. Git Remote Setup

On Cloud Foundry, centurions share work through git branches rather than shared volumes. You need a git remote accessible by both the orchestrator and centurion containers.

1. Create or identify a git repository for workspace coordination
2. Add a deploy key with read/write access
3. Provide the remote URL via either:
   - Config property: `worldmind.cf.git-remote-url`
   - Per-mission API parameter: `git_remote_url`

## 3. Deploy

Build and push all applications:

```bash
./run.sh cf-push
```

This runs `./mvnw clean package -DskipTests` then `cf push`, deploying:
- **worldmind** -- the Spring Boot orchestrator (1 instance)
- **centurion-forge** -- code generation (0 instances, task host)
- **centurion-gauntlet** -- testing (0 instances, task host)
- **centurion-vigil** -- code review (0 instances, task host)

The `manifest.yml` binds `worldmind-postgres` and `worldmind-model` to the orchestrator, and `worldmind-model` to each centurion.

## 4. How java-cfenv Auto-Binds Services

The orchestrator includes `java-cfenv-boot` which automatically parses `VCAP_SERVICES` at startup:

- **`worldmind-postgres`**: java-cfenv sets `spring.datasource.url`, `spring.datasource.username`, and `spring.datasource.password` from the PostgreSQL binding credentials. No manual JDBC configuration needed.

- **`worldmind-model`**: A custom `CfModelServiceProcessor` reads the LLM service credentials and maps them to Spring AI OpenAI properties:
  - `spring.ai.openai.base-url` from the service URI
  - `spring.ai.openai.api-key` from the service API key
  - `spring.ai.openai.chat.options.model` from service metadata or `GOOSE_MODEL` env var

## 5. Architecture: Git-Based Workspace Pattern

On Cloud Foundry, centurions cannot share a local filesystem the way Docker volumes allow on a single host. Instead, Worldmind uses a git-based workspace pattern:

1. **Branch creation** -- The orchestrator creates a mission branch (e.g., `worldmind/WMND-2026-0001`) and pushes it to the configured git remote.

2. **Task execution** -- The orchestrator runs `cf run-task centurion-forge` with a command that clones the mission branch, executes the Goose directive, then commits and pushes results back to the same branch.

3. **Sequential visibility** -- When GAUNTLET or VIGIL centurions run after FORGE, they clone the same mission branch and see all prior changes. Each centurion commits its own results (test reports, review findings) back to the branch.

4. **Merge and cleanup** -- After the mission completes successfully (Seal of Approval passes), the orchestrator merges the mission branch into the target branch and deletes the mission branch.

This gives each centurion an isolated working copy while maintaining a shared, ordered view of changes through git history.

## 6. Configuration

Set CF-specific properties so the orchestrator can locate the centurion apps and invoke tasks:

```bash
cf set-env worldmind worldmind.cf.api-url https://api.sys.example.com
cf set-env worldmind worldmind.cf.org my-org
cf set-env worldmind worldmind.cf.space my-space
cf set-env worldmind worldmind.cf.centurion-apps.forge centurion-forge
cf set-env worldmind worldmind.cf.centurion-apps.gauntlet centurion-gauntlet
cf set-env worldmind worldmind.cf.centurion-apps.vigil centurion-vigil
cf set-env worldmind worldmind.cf.git-remote-url https://github.com/your-org/your-repo.git
```

Restage after setting environment variables:

```bash
cf restage worldmind
```

### Environment Variable Reference

| Property                                  | Env Var Override       | Description                                 | Default          |
|-------------------------------------------|------------------------|---------------------------------------------|------------------|
| `worldmind.starblaster.provider`          | `STARBLASTER_PROVIDER` | Provider type                               | `docker`         |
| `worldmind.starblaster.max-parallel`      | --                     | Max concurrent centurion tasks              | `3` (CF profile) |
| `worldmind.starblaster.wave-cooldown-seconds` | --                | Delay between waves                         | `30` (CF profile)|
| `worldmind.cf.api-url`                    | --                     | CF API endpoint                             | --               |
| `worldmind.cf.org`                        | --                     | CF org name                                 | --               |
| `worldmind.cf.space`                      | --                     | CF space name                               | --               |
| `worldmind.cf.centurion-apps.*`           | --                     | Centurion role to CF app name mapping       | --               |
| `worldmind.cf.git-remote-url`             | --                     | Git remote for workspace sharing            | --               |
| `worldmind.cf.task-timeout-seconds`       | --                     | CF task timeout                             | `600`            |
| `worldmind.cf.task-memory-mb`             | --                     | CF task memory limit                        | `2048`           |
| `worldmind.cf.task-disk-mb`              | --                     | CF task disk limit                          | `4096`           |
| `GOOSE_MODEL`                             | `GOOSE_MODEL`          | LLM model name (if not in service metadata) | --               |
| `SPRING_PROFILES_ACTIVE`                  | `SPRING_PROFILES_ACTIVE` | Must include `cf`                        | --               |

## 7. API Usage with `git_remote_url`

The mission API accepts an optional `git_remote_url` parameter that overrides the configured `worldmind.cf.git-remote-url`:

```bash
curl -X POST https://worldmind.apps.example.com/api/v1/missions \
  -H 'Content-Type: application/json' \
  -d '{
    "request": "Add input validation to the user registration form",
    "mode": "APPROVE_PLAN",
    "project_path": "/workspace",
    "git_remote_url": "https://github.com/your-org/your-repo.git"
  }'
```

The React dashboard also includes a "Git Remote URL" field in the mission submission form.

## 8. Troubleshooting

### Check task status

```bash
cf tasks centurion-forge
cf tasks centurion-gauntlet
cf tasks centurion-vigil
```

### View task logs

```bash
cf logs centurion-forge --recent
cf logs worldmind --recent
```

### Common issues

**Task fails immediately** -- Check that the Docker image is accessible and that Diego Docker is enabled:

```bash
cf feature-flag diego_docker
```

**Database connection errors** -- Verify the service binding exists and that the `cf` Spring profile is active:

```bash
cf env worldmind
```

**LLM service not bound** -- Ensure `worldmind-model` is bound to both the orchestrator and the centurion that failed:

```bash
cf services
cf env centurion-forge
```

**Task timeout** -- CF tasks have a default timeout. For long-running missions, increase it:

```bash
cf run-task centurion-forge --command "..." --timeout 600
```

**java-cfenv not detecting services** -- Verify `VCAP_SERVICES` is populated:

```bash
cf env worldmind | grep VCAP_SERVICES
```

Ensure the `cf` Spring profile is active (`SPRING_PROFILES_ACTIVE=cf`).

**Spring AI not connecting to LLM** -- Check that `CfModelServiceProcessor` is resolving credentials:

```bash
cf logs worldmind --recent | grep -i "openai\|model\|cfenv"
```
