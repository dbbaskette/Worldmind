#!/bin/bash
# Builds all sandbox runtime images locally.
# Usage: ./build-all.sh
set -euo pipefail

REGISTRY=${SANDBOX_IMAGE_REGISTRY:-ghcr.io/dbbaskette}

echo "Building sandbox:base..."
docker build -t "$REGISTRY/sandbox:base" -f Dockerfile.base .

echo "Building sandbox:java..."
docker build -t "$REGISTRY/sandbox:java" -f Dockerfile.java .

echo "Building sandbox:python..."
docker build -t "$REGISTRY/sandbox:python" -f Dockerfile.python .

echo "All sandbox images built."
