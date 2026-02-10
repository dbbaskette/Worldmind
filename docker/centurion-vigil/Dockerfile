FROM ghcr.io/dbbaskette/centurion-base:latest

# Vigil-specific label
LABEL com.worldmind.centurion="vigil"
LABEL com.worldmind.description="Code review centurion"

# Goose config is provided via environment variables:
#   GOOSE_PROVIDER - openai (LM Studio) or anthropic
#   GOOSE_MODEL - model name
#   OPENAI_HOST - LM Studio URL (when provider=openai)
#   OPENAI_API_KEY - API key (not-needed-for-local when using LM Studio)
#   ANTHROPIC_API_KEY - API key (when provider=anthropic)
#
# entrypoint.sh (from base) generates profiles.yaml from these env vars,
# then runs: goose run <message_file>
