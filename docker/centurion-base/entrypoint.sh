#!/bin/bash
# Centurion container entrypoint
# Generates Goose profiles.yaml from environment variables, then runs goose.
#
# In Cloud Foundry: parses VCAP_SERVICES for GenAI tile credentials.
# Locally: uses GOOSE_PROVIDER, GOOSE_MODEL, and provider-specific env vars.

GOOSE_CONFIG_DIR="${HOME}/.config/goose"
PROFILES_FILE="$GOOSE_CONFIG_DIR/profiles.yaml"

mkdir -p "$GOOSE_CONFIG_DIR"

# --- CF environment detection ---
if [ -n "$VCAP_SERVICES" ]; then
    echo "[entrypoint] Cloud Foundry environment detected, parsing VCAP_SERVICES..."

    # Parse credentials from the bound worldmind-model service.
    # Supports two formats:
    #   1. GenAI tile: { "model_provider": "anthropic", "model_name": "...", "api_key": "..." }
    #   2. OpenAI-compatible: { "uri": "https://...", "api_key": "...", "model": "..." }
    eval "$(echo "$VCAP_SERVICES" | python3 -c "
import sys, json, os
vcap = json.load(sys.stdin)
for label in vcap:
    for svc in vcap[label]:
        creds = svc.get('credentials', {})
        # Detect provider
        if 'model_provider' in creds:
            print('PROVIDER=' + creds['model_provider'])
        elif any(k in creds for k in ('uri', 'url', 'api_url', 'api_base')):
            print('PROVIDER=openai')
        else:
            continue
        # Model name
        model = creds.get('model_name') or creds.get('model') or os.environ.get('GOOSE_MODEL', '')
        if model:
            print('MODEL=' + model)
        # API key
        api_key = creds.get('api_key') or creds.get('apiKey') or creds.get('key', '')
        if api_key:
            print('API_KEY=' + api_key)
        # API URL (OpenAI-compatible endpoint)
        api_url = creds.get('uri') or creds.get('url') or creds.get('api_url') or creds.get('api_base', '')
        if api_url:
            print('API_URL=' + api_url)
        sys.exit(0)
" 2>/dev/null)"

    PROVIDER="${PROVIDER:-openai}"
    MODEL="${MODEL:-}"
    API_KEY="${API_KEY:-}"
    API_URL="${API_URL:-}"

    # Set provider-specific env vars for Goose
    if [ "$PROVIDER" = "openai" ]; then
        [ -n "$API_KEY" ] && export OPENAI_API_KEY="$API_KEY"
        if [ -n "$API_URL" ]; then
            # Goose uses OPENAI_HOST for custom endpoints.
            # Trailing slash is required so URL path joins correctly
            # (e.g. .../openai/ + v1/chat vs .../openaiv1/chat).
            export OPENAI_HOST="${API_URL%/}/"
        fi
    elif [ "$PROVIDER" = "anthropic" ]; then
        [ -n "$API_KEY" ] && export ANTHROPIC_API_KEY="$API_KEY"
    elif [ "$PROVIDER" = "google" ]; then
        [ -n "$API_KEY" ] && export GOOGLE_API_KEY="$API_KEY"
    fi

    echo "[entrypoint] CF config: provider=$PROVIDER, model=$MODEL, api_url=${API_URL:-N/A}"
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
  moderator: none
  toolkits:
    - name: developer
      requires: {}
EOF

echo "[entrypoint] Goose config written: provider=$PROVIDER, model=$MODEL"

# Add MCP server extensions to Goose config (injected by Worldmind orchestrator)
# NOTE: Must be POSIX-compatible — CF tasks eval this under /bin/sh, not bash.
if [ -n "$MCP_SERVERS" ]; then
  echo "  extensions:" >> "$PROFILES_FILE"
  OLDIFS="$IFS"
  IFS=','
  for SERVER in $MCP_SERVERS; do
    IFS="$OLDIFS"
    URL_VAR="MCP_SERVER_${SERVER}_URL"
    TOKEN_VAR="MCP_SERVER_${SERVER}_TOKEN"
    eval "URL=\${$URL_VAR}"
    eval "TOKEN=\${$TOKEN_VAR}"
    if [ -n "$URL" ]; then
      NAME=$(echo "$SERVER" | tr '[:upper:]' '[:lower:]')
      cat >> "$PROFILES_FILE" <<MCP_EOF
    ${NAME}:
      name: ${NAME}
      type: streamablehttp
      uri: ${URL}
MCP_EOF
      if [ -n "$TOKEN" ]; then
        export "${TOKEN_VAR}"
        cat >> "$PROFILES_FILE" <<MCP_TOKEN_EOF
      env_keys:
        - ${TOKEN_VAR}
MCP_TOKEN_EOF
      fi
      echo "[entrypoint] MCP extension '${NAME}' configured: ${URL}"
    fi
  done
  IFS="$OLDIFS"
fi

# Append CF platform CA certs to Python's trust store (internal CAs use self-signed certs)
if [ -n "$CF_SYSTEM_CERT_PATH" ]; then
    python3 -c "
import certifi, glob, os
p = certifi.where()
certs = glob.glob(os.environ['CF_SYSTEM_CERT_PATH'] + '/*.crt')
if certs:
    with open(p, 'a') as f:
        for c in certs:
            f.write(open(c).read())
    print(f'[entrypoint] Appended {len(certs)} CF CA certs to {p}')
" 2>/dev/null || true
fi

exec goose run "$@"
