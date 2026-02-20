FROM ghcr.io/dbbaskette/agent-base:latest

# Researcher-specific label
LABEL com.worldmind.agent="researcher"
LABEL com.worldmind.description="Read-only research agent for analysis and information gathering"

# Goose config is provided via environment variables:
#   GOOSE_PROVIDER - openai (LM Studio) or anthropic
#   GOOSE_MODEL - model name
#   OPENAI_HOST - LM Studio URL (when provider=openai)
#   OPENAI_API_KEY - API key (not-needed-for-local when using LM Studio)
#   ANTHROPIC_API_KEY - API key (when provider=anthropic)
#
# Researcher agents operate in read-only mode:
# - No file creation or modification
# - No git operations
# - Analysis, research, and reporting only
#
# entrypoint.sh (from base) generates profiles.yaml from these env vars,
# then runs: goose run <message_file>
