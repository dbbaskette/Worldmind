#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Load .env if present
if [ -f .env ]; then
  set -a
  source .env
  set +a
fi

JAR="target/worldmind-0.1.0-SNAPSHOT.jar"
PROFILE="${WORLDMIND_PROFILE:-local}"

usage() {
  echo "Usage: ./run.sh [flags] [command] [options]"
  echo ""
  echo "Flags:"
  echo "  --cf                Build and push to Cloud Foundry (reads vars from .env)"
  echo "  --docker            Start everything in Docker (Postgres + Worldmind)"
  echo "  --down              Stop all Docker services"
  echo ""
  echo "Commands:"
  echo "  mission <request>   Submit a mission request"
  echo "  serve               Start the HTTP server + React dashboard"
  echo "  status              Show current mission status"
  echo "  health              Check system health"
  echo "  history             List past missions"
  echo "  inspect <id>        View detailed mission state"
  echo "  log                 View mission execution logs"
  echo "  timeline <id>       Show checkpoint history"
  echo ""
  echo "Environment:"
  echo "  WORLDMIND_PROFILE   Spring profile (default: local)"
  echo "  ANTHROPIC_API_KEY   Required for Anthropic profile"
  exit 1
}

# ---- CF push ----
cf_push() {
  missing=""
  for var in CF_DOCKER_PASSWORD CENTURION_IMAGE_REGISTRY DOCKER_USERNAME \
             CF_API_URL CF_ORG CF_SPACE CF_USERNAME CF_PASSWORD; do
    if [ -z "${!var:-}" ]; then missing="$missing $var"; fi
  done
  if [ -n "$missing" ]; then
    echo "ERROR: Missing required variables:$missing"
    echo "Set them in .env (see .env.example for reference)."
    exit 1
  fi

  VARS_FILE=$(mktemp /tmp/cf-vars-XXXXXX)
  trap "rm -f $VARS_FILE" EXIT
  cat > "$VARS_FILE" <<EOVARS
cf-api-url: ${CF_API_URL}
cf-org: ${CF_ORG}
cf-space: ${CF_SPACE}
centurion-image-registry: ${CENTURION_IMAGE_REGISTRY}
docker-username: ${DOCKER_USERNAME}
centurion-forge-app: ${CENTURION_FORGE_APP:-centurion-forge}
centurion-gauntlet-app: ${CENTURION_GAUNTLET_APP:-centurion-gauntlet}
centurion-vigil-app: ${CENTURION_VIGIL_APP:-centurion-vigil}
centurion-pulse-app: ${CENTURION_PULSE_APP:-centurion-pulse}
centurion-prism-app: ${CENTURION_PRISM_APP:-centurion-prism}
cf-username: ${CF_USERNAME}
cf-password: ${CF_PASSWORD}
git-token: ${GIT_TOKEN:-${CF_DOCKER_PASSWORD:-}}
mcp-enabled: ${MCP_ENABLED:-false}
nexus-mcp-url: ${NEXUS_MCP_URL:-}
nexus-mcp-token: ${NEXUS_MCP_TOKEN:-}
nexus-mcp-token-classify: ${NEXUS_MCP_TOKEN_CLASSIFY:-}
nexus-mcp-token-upload: ${NEXUS_MCP_TOKEN_UPLOAD:-}
nexus-mcp-token-plan: ${NEXUS_MCP_TOKEN_PLAN:-}
nexus-mcp-token-seal: ${NEXUS_MCP_TOKEN_SEAL:-}
nexus-mcp-token-forge: ${NEXUS_MCP_TOKEN_FORGE:-}
nexus-mcp-token-gauntlet: ${NEXUS_MCP_TOKEN_GAUNTLET:-}
nexus-mcp-token-vigil: ${NEXUS_MCP_TOKEN_VIGIL:-}
nexus-mcp-token-pulse: ${NEXUS_MCP_TOKEN_PULSE:-}
nexus-mcp-token-prism: ${NEXUS_MCP_TOKEN_PRISM:-}
EOVARS

  echo "Building JAR..."
  rm -rf src/main/resources/static/assets src/main/resources/static/index.html
  ./mvnw -q clean package -DskipTests

  # Remove stale dotted env vars from previous manifests (CF keeps old env vars across pushes)
  echo "Cleaning stale env vars..."
  for old_var in worldmind.cf.centurion-apps.forge worldmind.cf.centurion-apps.gauntlet \
                 worldmind.cf.centurion-apps.vigil worldmind.cf.centurion-apps.pulse \
                 worldmind.cf.centurion-apps.prism worldmind.cf.api-url worldmind.cf.org \
                 worldmind.cf.space worldmind.starblaster.provider \
                 worldmind.starblaster.image-registry; do
    cf unset-env worldmind "$old_var" 2>/dev/null || true
  done

  echo "Pushing to Cloud Foundry..."
  CF_DOCKER_PASSWORD="$CF_DOCKER_PASSWORD" cf push --vars-file "$VARS_FILE"
}

if [ $# -eq 0 ]; then
  usage
fi

case "$1" in
  --docker)
    echo "Starting all services in Docker..."
    docker compose --profile full up -d --build
    echo ""
    echo "  Dashboard:  http://localhost:${WORLDMIND_PORT:-8080}"
    echo "  API:        http://localhost:${WORLDMIND_PORT:-8080}/api/v1"
    echo "  Postgres:   localhost:5432"
    echo ""
    echo "  Logs:       docker compose --profile full logs -f"
    echo "  Stop:       ./run.sh --down"
    exit 0
    ;;
  --down)
    echo "Stopping all services..."
    docker compose --profile full down
    exit 0
    ;;
  --cf)
    cf_push
    exit 0
    ;;
esac

# Ensure Postgres is running
if ! docker compose ps postgres --status running -q 2>/dev/null | grep -q .; then
  echo "Starting PostgreSQL..."
  docker compose up -d postgres
  echo "Waiting for PostgreSQL to be ready..."
  until docker compose exec -T postgres pg_isready -U worldmind -q 2>/dev/null; do
    sleep 1
  done
  echo "PostgreSQL ready."
fi

# Rebuild if jar is missing or source is newer
if [ ! -f "$JAR" ] || \
   [ -n "$(find src worldmind-ui/src -newer "$JAR" -print -quit 2>/dev/null)" ]; then
  echo "Building Worldmind..."
  rm -rf src/main/resources/static/assets src/main/resources/static/index.html
  mvn -q package -DskipTests
fi

# Show post-launch info for serve mode
if [ "$1" = "serve" ]; then
  PORT="${SERVER_PORT:-8080}"
  echo ""
  echo "  Dashboard:  http://localhost:${PORT}"
  echo "  API:        http://localhost:${PORT}/api/v1"
  echo "  Postgres:   localhost:5432"
  echo ""
fi

exec java -Dspring.profiles.active="$PROFILE" -jar "$JAR" "$@"
