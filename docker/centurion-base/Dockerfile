FROM python:3.12-slim

# Install Node.js 20 and system dependencies
RUN apt-get update && apt-get install -y curl git && \
    curl -fsSL https://deb.nodesource.com/setup_20.x | bash - && \
    apt-get install -y nodejs && \
    apt-get clean && rm -rf /var/lib/apt/lists/*

# Install langfuse v2 first (v3 removed langfuse.decorators which goose-ai needs)
# Then install goose-ai — pip will see langfuse is already satisfied at v2
RUN pip install --no-cache-dir "langfuse>=2.0.0,<3.0.0" && \
    pip install --no-cache-dir goose-ai

# Verify goose CLI is available and langfuse.decorators is importable
RUN goose --version && python -c "import langfuse.decorators; print('langfuse OK')"

# Install MCP servers (retry + longer timeout for cross-platform QEMU builds)
RUN npm config set fetch-retries 5 && \
    npm config set fetch-retry-mintimeout 60000 && \
    npm config set fetch-retry-maxtimeout 300000 && \
    npm install -g @modelcontextprotocol/server-filesystem

# Copy entrypoint script (generates profiles.yaml from env vars, then runs goose)
COPY entrypoint.sh /usr/local/bin/entrypoint.sh

# Create non-root user (CF Diego rejects root)
RUN useradd -m -s /bin/bash centurion && \
    mkdir -p /workspace && chown centurion:centurion /workspace
USER centurion

# Working directory
WORKDIR /workspace

ENTRYPOINT ["entrypoint.sh"]
