FROM ghcr.io/dbbaskette/agent-base:latest

# Deployer-specific labels
LABEL com.worldmind.agent="deployer"
LABEL com.worldmind.description="CF deployment agent with Maven and CF CLI"

# Switch to root for package installation
USER root

# Install CF CLI v8 binary (must be placed in docker/agent-deployer/cf before building).
# The binary is gitignored to support air-gapped environments.
# Verify integrity before building: sha256sum docker/agent-deployer/cf
# Known-good v8 checksum should be recorded in docker/agent-deployer/cf.sha256.
COPY cf /usr/local/bin/cf
RUN chmod +x /usr/local/bin/cf && \
    cf version

# Install Maven for building Java apps
RUN apt-get update && apt-get install -y maven && rm -rf /var/lib/apt/lists/*

# Switch back to non-root user
USER agent
