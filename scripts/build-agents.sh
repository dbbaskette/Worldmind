#!/bin/bash
# Builds all agent images locally.
# Usage: ./scripts/build-agents.sh
set -euo pipefail

REGISTRY=${AGENT_IMAGE_REGISTRY:-ghcr.io/dbbaskette}

echo "Building agent-base..."
docker build -t "$REGISTRY/agent-base:latest" docker/agent-base/

echo "Building agent-coder..."
docker build -t "$REGISTRY/agent-coder:latest" docker/agent-coder/

echo "Building agent-refactorer..."
docker build -t "$REGISTRY/agent-refactorer:latest" docker/agent-refactorer/

echo "Building agent-researcher..."
docker build -t "$REGISTRY/agent-researcher:latest" docker/agent-researcher/

echo "Building agent-reviewer..."
docker build -t "$REGISTRY/agent-reviewer:latest" docker/agent-reviewer/

echo "Building agent-tester..."
docker build -t "$REGISTRY/agent-tester:latest" docker/agent-tester/

echo "Building agent-deployer..."
docker build -t "$REGISTRY/agent-deployer:latest" docker/agent-deployer/

echo "All agent images built."
