# Deploying Worldmind on Cloud Foundry

## Component Overview

| Component | Type | Runtime | Model | Database | Description |
|-----------|------|---------|:-----:|:--------:|-------------|
| **worldmind** | CF app (Java buildpack) | Long-running process, 1 instance | Yes | Yes | Spring Boot orchestrator — classifies requests, plans missions, dispatches centurions, evaluates results |
| **centurion-forge** | CF app (Docker image) | 0 instances; runs as CF tasks | Yes | No | Code generation — Goose agent writes implementation code |
| **centurion-gauntlet** | CF app (Docker image) | 0 instances; runs as CF tasks | Yes | No | Test execution — Goose agent writes and runs tests |
| **centurion-vigil** | CF app (Docker image) | 0 instances; runs as CF tasks | Yes | No | Code review — Goose agent reviews code for quality |
| **centurion-pulse** | CF app (Docker image) | 0 instances; runs as CF tasks | Yes | No | Research — Goose agent performs read-only analysis |
| **centurion-prism** | CF app (Docker image) | 0 instances; runs as CF tasks | Yes | No | Refactoring — Goose agent restructures existing code |
| **worldmind-postgres** | CF service | Managed by marketplace tile | -- | -- | PostgreSQL for checkpoint state and mission persistence |
| **worldmind-model** | CF service | Managed by marketplace tile | -- | -- | OpenAI-compatible LLM endpoint, bound to orchestrator + all centurions |

**How centurions run:** The orchestrator calls `cf run-task centurion-forge` (or gauntlet/vigil/pulse/prism) to launch a short-lived CF task on the Docker-based app. The task clones the mission git branch, runs Goose with the directive, commits results, and exits. The app itself has 0 long-running instances — it only exists as a task host.

**How the model is consumed:**
- **Orchestrator** — Spring AI `ChatClient` for classify, plan, and seal evaluation nodes. Credentials are auto-mapped from the `worldmind-model` binding via `CfModelServiceProcessor` (java-cfenv).
- **Centurions** — Goose CLI reads `VCAP_SERVICES` at container startup (`entrypoint.sh`) and configures itself with the `worldmind-model` binding credentials (API URL + key).

## Prerequisites

- **CF CLI v8+** installed and authenticated (`cf login`)
- **Java 21** and **Maven** installed locally (for building the orchestrator JAR)
- **Docker images** for centurions pushed to a registry accessible by the CF foundation (configured via `CENTURION_IMAGE_REGISTRY` in `.env`):
  - `{registry}/centurion-forge:latest`
  - `{registry}/centurion-gauntlet:latest`
  - `{registry}/centurion-vigil:latest`
  - `{registry}/centurion-pulse:latest`
  - `{registry}/centurion-prism:latest`
- **GitHub PAT** with `read:packages` scope for pulling images from GHCR. Set `CF_DOCKER_PASSWORD` and `DOCKER_USERNAME` in your `.env` file.
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
3. Provide the remote URL per-mission via the `git_remote_url` field in the REST API or the "Git Remote URL" input in the React dashboard

## 3. Configure Variables

All configuration lives in `.env`. Copy the example and fill in your values:

```bash
cp .env.example .env
```

The CF-specific variables:

```bash
# --- Centurion Images ---
CENTURION_IMAGE_REGISTRY=ghcr.io/dbbaskette
DOCKER_USERNAME=dbbaskette
# CF CLI requires this exact variable name for Docker registry auth
CF_DOCKER_PASSWORD=ghp_yourGitHubPAThere

# --- Cloud Foundry ---
CF_API_URL=https://api.sys.example.com
CF_ORG=my-org
CF_SPACE=my-space
CENTURION_FORGE_APP=centurion-forge
CENTURION_GAUNTLET_APP=centurion-gauntlet
CENTURION_VIGIL_APP=centurion-vigil
```

When you run `./run.sh --cf`, it reads these from `.env`, generates a temporary vars file, and passes it to `cf push --vars-file`. No separate config file to manage.

## 4. Deploy

Build and push all applications:

```bash
./run.sh --cf
```

This builds the JAR, generates a vars file from `.env`, and runs `cf push`, deploying all components listed in the Component Overview above.

**Note:** On CF, the app auto-detects the Cloud Foundry environment (`VCAP_APPLICATION`) and starts in serve mode (HTTP server) without needing the `serve` subcommand.

## 5. How Services Are Bound

The `manifest.yml` binds services as follows:

| App | `worldmind-postgres` | `worldmind-model` |
|-----|:--------------------:|:-----------------:|
| worldmind (orchestrator) | Yes | Yes |
| centurion-forge | -- | Yes |
| centurion-gauntlet | -- | Yes |
| centurion-vigil | -- | Yes |
| centurion-pulse | -- | Yes |
| centurion-prism | -- | Yes |

### Orchestrator service binding (java-cfenv)

The orchestrator includes `java-cfenv-boot` which automatically parses `VCAP_SERVICES` at startup:

- **`worldmind-postgres`**: java-cfenv sets `spring.datasource.url`, `spring.datasource.username`, and `spring.datasource.password` from the PostgreSQL binding credentials. No manual JDBC configuration needed.

- **`worldmind-model`**: A custom `CfModelServiceProcessor` reads the LLM service credentials and maps them to Spring AI OpenAI properties:
  - `spring.ai.openai.base-url` from the service URI
  - `spring.ai.openai.api-key` from the service API key
  - `spring.ai.openai.chat.options.model` from service metadata or `GOOSE_MODEL` env var

### Centurion service binding (entrypoint.sh)

Centurion Docker containers parse `VCAP_SERVICES` at startup via `entrypoint.sh`. Two credential formats are supported:

| Format | Fields | Provider |
|--------|--------|----------|
| GenAI tile | `model_provider`, `model_name`, `api_key` | Detected from `model_provider` |
| OpenAI-compatible | `uri`/`url`, `api_key`, `model` | Defaults to `openai` |

The entrypoint sets `OPENAI_HOST` + `OPENAI_API_KEY` (or `ANTHROPIC_API_KEY` / `GOOGLE_API_KEY` depending on provider) and writes a Goose `profiles.yaml` with the detected model configuration.

## 6. Architecture: Git-Based Workspace Pattern

On Cloud Foundry, centurions cannot share a local filesystem the way Docker volumes allow on a single host. Instead, Worldmind uses a git-based workspace pattern:

1. **Branch creation** -- The orchestrator creates a mission branch (e.g., `worldmind/WMND-2026-0001`) and pushes it to the configured git remote.

2. **Task execution** -- The orchestrator runs `cf run-task centurion-forge` with a command that clones the mission branch, executes the Goose directive, then commits and pushes results back to the same branch.

3. **Sequential visibility** -- When GAUNTLET or VIGIL centurions run after FORGE, they clone the same mission branch and see all prior changes. Each centurion commits its own results (test reports, review findings) back to the branch.

4. **Merge and cleanup** -- After the mission completes successfully (Seal of Approval passes), the orchestrator merges the mission branch into the target branch and deletes the mission branch.

This gives each centurion an isolated working copy while maintaining a shared, ordered view of changes through git history.

## 7. Configuration Reference

### .env variables for CF deployment

| Variable | Description | Required |
|----------|-------------|:--------:|
| `CF_API_URL` | CF API endpoint (e.g., `https://api.sys.example.com`) | Yes |
| `CF_ORG` | CF org name | Yes |
| `CF_SPACE` | CF space name | Yes |
| `CENTURION_IMAGE_REGISTRY` | Docker image registry prefix (e.g., `ghcr.io/dbbaskette`) | Yes |
| `DOCKER_USERNAME` | Registry username (GitHub username for GHCR) | Yes |
| `CF_DOCKER_PASSWORD` | GitHub PAT with `read:packages` scope (CF CLI convention) | Yes |
| `CENTURION_FORGE_APP` | CF app name for Forge centurion | No (default: `centurion-forge`) |
| `CENTURION_GAUNTLET_APP` | CF app name for Gauntlet centurion | No (default: `centurion-gauntlet`) |
| `CENTURION_VIGIL_APP` | CF app name for Vigil centurion | No (default: `centurion-vigil`) |
| `CENTURION_PULSE_APP` | CF app name for Pulse centurion | No (default: `centurion-pulse`) |
| `CENTURION_PRISM_APP` | CF app name for Prism centurion | No (default: `centurion-prism`) |

### Application properties

| Property                                  | Env Var Override       | Description                                 | Default          |
|-------------------------------------------|------------------------|---------------------------------------------|------------------|
| `worldmind.starblaster.provider`          | `STARBLASTER_PROVIDER` | Provider type                               | `docker`         |
| `worldmind.starblaster.image-registry`    | `CENTURION_IMAGE_REGISTRY` | Docker image registry for centurions    | `ghcr.io/dbbaskette` |
| `worldmind.starblaster.max-parallel`      | --                     | Max concurrent centurion tasks              | `3` (CF profile) |
| `worldmind.starblaster.wave-cooldown-seconds` | --                | Delay between waves                         | `30` (CF profile)|
| `worldmind.cf.api-url`                    | --                     | CF API endpoint                             | --               |
| `worldmind.cf.org`                        | --                     | CF org name                                 | --               |
| `worldmind.cf.space`                      | --                     | CF space name                               | --               |
| `worldmind.cf.centurion-apps.*`           | --                     | Centurion role to CF app name mapping       | --               |
| `worldmind.cf.task-timeout-seconds`       | --                     | CF task timeout                             | `600`            |
| `worldmind.cf.task-memory-mb`             | --                     | CF task memory limit                        | `2048`           |
| `worldmind.cf.task-disk-mb`              | --                     | CF task disk limit                          | `4096`           |
| `GOOSE_MODEL`                             | `GOOSE_MODEL`          | LLM model name (if not in service metadata) | --               |
| `SPRING_PROFILES_ACTIVE`                  | `SPRING_PROFILES_ACTIVE` | Must include `cf`                        | --               |
| `CF_DOCKER_PASSWORD`                      | `CF_DOCKER_PASSWORD`   | GitHub PAT with `read:packages` for GHCR (CF CLI convention) | --  |

## 8. API Usage with `git_remote_url`

On CF, each mission must specify `git_remote_url` — the project repository that centurions will clone, branch, and push to:

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

## 9. Troubleshooting

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
