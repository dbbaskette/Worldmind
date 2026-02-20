#!/bin/bash
# Builds all agent images locally, with optional push and smoke test.
# Usage: ./scripts/build-agents.sh [--push] [--smoke-test]
set -euo pipefail

REGISTRY=${AGENT_IMAGE_REGISTRY:-ghcr.io/dbbaskette}
PUSH=false
SMOKE_TEST=false

for arg in "$@"; do
  case "$arg" in
    --push) PUSH=true ;;
    --smoke-test) SMOKE_TEST=true ;;
    *) echo "Unknown option: $arg"; exit 1 ;;
  esac
done

AGENTS=(agent-base agent-coder agent-refactorer agent-researcher agent-reviewer agent-tester agent-deployer)

for agent in "${AGENTS[@]}"; do
  echo "Building $agent..."
  docker build -t "$REGISTRY/$agent:latest" "docker/$agent/"
done

echo "All agent images built."

if [ "$SMOKE_TEST" = true ]; then
  echo ""
  echo "Running smoke tests..."
  echo "  agent-deployer: cf version"
  docker run --rm "$REGISTRY/agent-deployer:latest" cf version
  echo "  agent-deployer: mvn --version"
  docker run --rm "$REGISTRY/agent-deployer:latest" mvn --version
  echo "Smoke tests passed."
fi

if [ "$PUSH" = true ]; then
  echo ""
  for agent in "${AGENTS[@]}"; do
    echo "Pushing $agent..."
    docker push "$REGISTRY/$agent:latest"
  done
  echo "All agent images pushed."
fi
