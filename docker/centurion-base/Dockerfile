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

# Install MCP servers
RUN npm install -g @modelcontextprotocol/server-filesystem

# Copy entrypoint script (generates profiles.yaml from env vars, then runs goose)
COPY entrypoint.sh /usr/local/bin/entrypoint.sh

# Working directory
WORKDIR /workspace

ENTRYPOINT ["entrypoint.sh"]
