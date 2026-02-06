# Deploying Worldmind on Cloud Foundry

## Prerequisites

- CF CLI v8+ installed and authenticated (`cf login`)
- Docker images for centurions pushed to a registry accessible by the CF foundation:
  - `worldmind/centurion-forge:latest`
  - `worldmind/centurion-gauntlet:latest`
  - `worldmind/centurion-vigil:latest`
- CF foundation must have Diego Docker support enabled (`cf feature-flag diego_docker`)
- PostgreSQL service tile available in the marketplace
- GenAI service tile available in the marketplace
- Java 21 and Maven installed locally (for building the orchestrator JAR)

## 1. Create Services

Create the PostgreSQL and GenAI service instances. Adjust plan names to match your marketplace:

```bash
cf create-service postgres on-demand-postgres-db worldmind-db
cf create-service genai standard worldmind-genai
```

Verify both services reach a `create succeeded` status:

```bash
cf services
```

## 2. Push Centurion Apps

Centurions run as Docker-based task hosts. They are deployed with zero instances and no routes -- the orchestrator launches work on them via `cf run-task`.

```bash
cf push centurion-forge --docker-image worldmind/centurion-forge:latest \
  -m 2G -k 4G -i 0 --no-route --health-check-type process
cf bind-service centurion-forge worldmind-genai

cf push centurion-gauntlet --docker-image worldmind/centurion-gauntlet:latest \
  -m 2G -k 4G -i 0 --no-route --health-check-type process
cf bind-service centurion-gauntlet worldmind-genai

cf push centurion-vigil --docker-image worldmind/centurion-vigil:latest \
  -m 2G -k 4G -i 0 --no-route --health-check-type process
cf bind-service centurion-vigil worldmind-genai
```

Alternatively, push all apps at once using the manifest:

```bash
cf push
```

## 3. Build and Push the Orchestrator

```bash
mvn clean package -DskipTests
cf push worldmind
```

The manifest binds `worldmind-db` and `worldmind-genai` automatically. Spring Boot's cloud connectors will pick up the PostgreSQL datasource from `VCAP_SERVICES`.

## 4. Configuration

Set CF-specific properties so the orchestrator can locate the centurion apps and invoke tasks. These can be set as environment variables or in an `application-cf.yml` profile:

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

### Property Reference

| Property | Description |
|---|---|
| `worldmind.cf.api-url` | CF API endpoint for the foundation |
| `worldmind.cf.org` | CF org where centurion apps are deployed |
| `worldmind.cf.space` | CF space where centurion apps are deployed |
| `worldmind.cf.centurion-apps.*` | Mapping of centurion role to CF app name |
| `worldmind.cf.git-remote-url` | Git remote used for workspace coordination |

## 5. How It Works: Git-Based Workspace Pattern

On Cloud Foundry, centurions cannot share a local filesystem the way Docker volumes allow on a single host. Instead, Worldmind uses a git-based workspace pattern:

1. **Branch creation** -- The orchestrator creates a mission branch (e.g., `mission/abc123`) and pushes it to the configured git remote.

2. **Task execution** -- The orchestrator runs `cf run-task centurion-forge` with a command that clones the mission branch, executes the Goose directive, then commits and pushes results back to the same branch.

3. **Sequential visibility** -- When GAUNTLET or VIGIL centurions run after FORGE, they clone the same mission branch and see all prior changes. Each centurion commits its own results (test reports, review findings) back to the branch.

4. **Merge and cleanup** -- After the mission completes successfully (Seal of Approval passes), the orchestrator merges the mission branch into the target branch and deletes the mission branch.

This gives each centurion an isolated working copy while maintaining a shared, ordered view of changes through git history.

## 6. Troubleshooting

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

**GenAI service not bound** -- Ensure `worldmind-genai` is bound to both the orchestrator and the centurion that failed:

```bash
cf services
cf env centurion-forge
```

**Task timeout** -- CF tasks have a default timeout. For long-running missions, increase it:

```bash
cf run-task centurion-forge --command "..." --timeout 600
```
