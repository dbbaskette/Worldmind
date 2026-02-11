#!/bin/bash
# Builds all starblaster runtime images locally.
# Usage: ./build-all.sh
set -euo pipefail

REGISTRY=${STARBLASTER_IMAGE_REGISTRY:-ghcr.io/dbbaskette}

echo "Building starblaster:base..."
docker build -t "$REGISTRY/starblaster:base" -f Dockerfile.base .

echo "Building starblaster:java..."
docker build -t "$REGISTRY/starblaster:java" -f Dockerfile.java .

echo "Building starblaster:python..."
docker build -t "$REGISTRY/starblaster:python" -f Dockerfile.python .

echo "All starblaster images built."
