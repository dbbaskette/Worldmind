#!/bin/bash
# Centurion container entrypoint
# Generates Goose profiles.yaml from environment variables, then runs goose.
#
# In Cloud Foundry: parses VCAP_SERVICES for GenAI tile credentials.
# Locally: uses GOOSE_PROVIDER, GOOSE_MODEL, and provider-specific env vars.

GOOSE_CONFIG_DIR="/home/centurion/.config/goose"
PROFILES_FILE="$GOOSE_CONFIG_DIR/profiles.yaml"

mkdir -p "$GOOSE_CONFIG_DIR"

# --- CF environment detection ---
if [ -n "$VCAP_SERVICES" ]; then
    echo "[entrypoint] Cloud Foundry environment detected, parsing VCAP_SERVICES..."

    # Extract GenAI service credentials
    # VCAP_SERVICES structure for GenAI tile:
    # { "genai": [{ "credentials": { "model_provider": "anthropic", "model_name": "claude-sonnet-4-20250514", "api_key": "sk-..." } }] }
    # OR it might be under a different service label

    # Try all service labels, looking for GenAI credentials
    PROVIDER=$(echo "$VCAP_SERVICES" | python3 -c "
import sys, json
vcap = json.load(sys.stdin)
for label in vcap:
    for svc in vcap[label]:
        creds = svc.get('credentials', {})
        if 'model_provider' in creds:
            print(creds['model_provider'])
            sys.exit(0)
        if 'api_key' in creds and 'anthropic' in str(creds).lower():
            print('anthropic')
            sys.exit(0)
print('anthropic')
" 2>/dev/null || echo "anthropic")

    MODEL=$(echo "$VCAP_SERVICES" | python3 -c "
import sys, json
vcap = json.load(sys.stdin)
for label in vcap:
    for svc in vcap[label]:
        creds = svc.get('credentials', {})
        if 'model_name' in creds:
            print(creds['model_name'])
            sys.exit(0)
print('claude-sonnet-4-20250514')
" 2>/dev/null || echo "claude-sonnet-4-20250514")

    API_KEY=$(echo "$VCAP_SERVICES" | python3 -c "
import sys, json
vcap = json.load(sys.stdin)
for label in vcap:
    for svc in vcap[label]:
        creds = svc.get('credentials', {})
        if 'api_key' in creds:
            print(creds['api_key'])
            sys.exit(0)
print('')
" 2>/dev/null || echo "")

    # Set provider-specific API key env var
    if [ "$PROVIDER" = "anthropic" ] && [ -n "$API_KEY" ]; then
        export ANTHROPIC_API_KEY="$API_KEY"
    elif [ "$PROVIDER" = "openai" ] && [ -n "$API_KEY" ]; then
        export OPENAI_API_KEY="$API_KEY"
    fi

    echo "[entrypoint] CF config: provider=$PROVIDER, model=$MODEL"
else
    # --- Local/Docker environment (existing logic) ---
    PROVIDER="${GOOSE_PROVIDER:-openai}"
    MODEL="${GOOSE_MODEL:-qwen2.5-coder-32b}"
fi

# Allow explicit env vars to override CF-detected values
PROVIDER="${GOOSE_PROVIDER:-$PROVIDER}"
MODEL="${GOOSE_MODEL:-$MODEL}"

cat > "$PROFILES_FILE" <<EOF
default:
  provider: $PROVIDER
  processor: $MODEL
  accelerator: $MODEL
  moderator: synopsis
  toolkits:
    - name: synopsis
EOF

echo "[entrypoint] Goose config written: provider=$PROVIDER, model=$MODEL"
exec goose run "$@"
