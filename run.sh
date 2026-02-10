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
  echo "  cf-push             Build and push to Cloud Foundry"
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
    echo "Building and pushing to Cloud Foundry..."
    ./mvnw -q clean package -DskipTests
    cf push
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
