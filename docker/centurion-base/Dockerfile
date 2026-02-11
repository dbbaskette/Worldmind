FROM python:3.12-slim

# System dependencies (git for workspace, curl for downloads, python3 for VCAP parsing)
RUN apt-get update && apt-get install -y curl git ca-certificates && \
    apt-get clean && rm -rf /var/lib/apt/lists/*

# Install Goose CLI v1.x (Rust binary) — replaces Python goose-ai
RUN curl -fsSL https://github.com/block/goose/releases/download/stable/download_cli.sh | CONFIGURE=false bash && \
    cp /root/.goose/bin/goose /usr/local/bin/goose && \
    rm -rf /root/.goose && \
    goose --version

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
