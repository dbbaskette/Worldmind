FROM python:3.12-slim

# Install Node.js 20
RUN apt-get update && apt-get install -y curl git && \
    curl -fsSL https://deb.nodesource.com/setup_20.x | bash - && \
    apt-get install -y nodejs && \
    apt-get clean && rm -rf /var/lib/apt/lists/*

# Install Goose CLI
RUN pip install --no-cache-dir goose-ai

# Install MCP servers
RUN npm install -g @modelcontextprotocol/server-filesystem

# Create non-root user
RUN useradd -m -s /bin/bash centurion

# Working directory
WORKDIR /workspace

USER centurion
