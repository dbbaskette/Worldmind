FROM python:3.12-slim

# System dependencies (git for workspace, curl for downloads, python3 for VCAP parsing)
RUN apt-get update && apt-get install -y curl git ca-certificates bzip2 && \
    apt-get clean && rm -rf /var/lib/apt/lists/*

# Install Goose CLI v1.x (Rust binary) — download directly from GitHub releases
RUN ARCH=$(uname -m) && \
    curl -fsSL "https://github.com/block/goose/releases/download/stable/goose-${ARCH}-unknown-linux-gnu.tar.bz2" \
      | tar xj -C /usr/local/bin && \
    chmod +x /usr/local/bin/goose && \
    goose --version

# Copy SSE normalizing proxy (fixes Tanzu GenAI streaming for Goose)
COPY sse-proxy.py /usr/local/bin/sse-proxy.py

# Copy entrypoint script (generates config.yaml from env vars, then runs goose)
COPY entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh

# Create non-root user (CF Diego rejects root)
RUN useradd -m -s /bin/bash centurion && \
    mkdir -p /workspace && chown centurion:centurion /workspace
USER centurion

# Working directory
WORKDIR /workspace

ENTRYPOINT ["entrypoint.sh"]
