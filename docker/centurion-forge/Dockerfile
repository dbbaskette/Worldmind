FROM worldmind/centurion-base:latest

# Forge-specific label
LABEL com.worldmind.centurion="forge"
LABEL com.worldmind.description="Code generation centurion"

# Goose config is provided via environment variables:
#   GOOSE_PROVIDER - openai (LM Studio) or anthropic
#   GOOSE_MODEL - model name
#   OPENAI_HOST - LM Studio URL (when provider=openai)
#   OPENAI_API_KEY - API key (not-needed-for-local when using LM Studio)
#   ANTHROPIC_API_KEY - API key (when provider=anthropic)

# Entry point is overridden by Docker Java client
# Default: goose run -t "<instruction>"
ENTRYPOINT ["goose", "run"]
