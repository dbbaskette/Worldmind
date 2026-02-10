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
  echo "Usage: ./run.sh <command> [options]"
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
  echo "  up                  Start everything in Docker (Postgres + Worldmind)"
  echo "  down                Stop all Docker services"
  echo "  cf-push             Build and push to Cloud Foundry (reads CF vars from .env)"
  echo ""
  echo "Environment:"
  echo "  WORLDMIND_PROFILE   Spring profile (default: local)"
  echo "  ANTHROPIC_API_KEY   Required for Anthropic profile"
  exit 1
}

if [ $# -eq 0 ]; then
  usage
fi

# Docker-only commands
case "$1" in
  up)
    echo "Starting all services in Docker..."
    docker compose --profile full up -d --build
    echo ""
    echo "  Dashboard:  http://localhost:${WORLDMIND_PORT:-8080}"
    echo "  API:        http://localhost:${WORLDMIND_PORT:-8080}/api/v1"
    echo "  Postgres:   localhost:5432"
    echo ""
    echo "  Logs:       docker compose --profile full logs -f"
    echo "  Stop:       ./run.sh down"
    exit 0
    ;;
  down)
    echo "Stopping all services..."
    docker compose --profile full down
    exit 0
    ;;
  cf-push)
    # Validate required CF variables
    missing=""
    for var in CF_DOCKER_PASSWORD CENTURION_IMAGE_REGISTRY DOCKER_USERNAME \
               CF_API_URL CF_ORG CF_SPACE GIT_REMOTE_URL; do
      if [ -z "${!var:-}" ]; then missing="$missing $var"; fi
    done
    if [ -n "$missing" ]; then
      echo "ERROR: Missing required variables:$missing"
      echo "Set them in .env (see .env.example for reference)."
      exit 1
    fi

    # Generate vars YAML from .env for cf push --vars-file
    VARS_FILE=$(mktemp /tmp/cf-vars-XXXXXX.yml)
    trap "rm -f $VARS_FILE" EXIT
    cat > "$VARS_FILE" <<EOVARS
cf-api-url: ${CF_API_URL}
cf-org: ${CF_ORG}
cf-space: ${CF_SPACE}
git-remote-url: ${GIT_REMOTE_URL}
centurion-image-registry: ${CENTURION_IMAGE_REGISTRY}
docker-username: ${DOCKER_USERNAME}
centurion-forge-app: ${CENTURION_FORGE_APP:-centurion-forge}
centurion-gauntlet-app: ${CENTURION_GAUNTLET_APP:-centurion-gauntlet}
centurion-vigil-app: ${CENTURION_VIGIL_APP:-centurion-vigil}
EOVARS

    echo "Building and pushing to Cloud Foundry..."
    ./mvnw -q clean package -DskipTests
    CF_DOCKER_PASSWORD="$CF_DOCKER_PASSWORD" cf push --vars-file "$VARS_FILE"
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
