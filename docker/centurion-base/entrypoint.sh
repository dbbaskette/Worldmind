#!/bin/bash
# Centurion container entrypoint
# Generates Goose profiles.yaml from environment variables, then runs goose.

GOOSE_CONFIG_DIR="/home/centurion/.config/goose"
PROFILES_FILE="$GOOSE_CONFIG_DIR/profiles.yaml"

mkdir -p "$GOOSE_CONFIG_DIR"

PROVIDER="${GOOSE_PROVIDER:-openai}"
MODEL="${GOOSE_MODEL:-qwen2.5-coder-32b}"

cat > "$PROFILES_FILE" <<EOF
default:
  provider: $PROVIDER
  processor: $MODEL
  accelerator: $MODEL
  moderator: synopsis
  toolkits:
    - name: synopsis
EOF

exec goose run "$@"
