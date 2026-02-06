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

# Rebuild if jar is missing or source is newer
if [ ! -f "$JAR" ] || [ -n "$(find src -newer "$JAR" -print -quit 2>/dev/null)" ]; then
  echo "Building Worldmind..."
  mvn -q package -DskipTests
fi

PROFILE="${1:-local}"
shift 2>/dev/null || true

case "$PROFILE" in
  local)
    echo "Worldmind [local model: ${LOCAL_LLM_URL:-http://localhost:1234}]"
    ;;
  anthropic)
    echo "Worldmind [Anthropic Claude]"
    ;;
  *)
    echo "Worldmind [profile: $PROFILE]"
    ;;
esac

exec java -Dspring.profiles.active="$PROFILE" -jar "$JAR" "$@"
