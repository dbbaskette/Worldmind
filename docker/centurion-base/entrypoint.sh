#!/bin/sh
# Centurion container entrypoint — Goose v1.x (Rust)
# Generates config.yaml from environment variables, then runs goose.
#
# In Cloud Foundry: parses VCAP_SERVICES for GenAI tile credentials.
# Locally: uses GOOSE_PROVIDER, GOOSE_MODEL, and provider-specific env vars.
#
# NOTE: Must be fully POSIX-compatible — CF tasks eval this under /bin/sh.

GOOSE_CONFIG_DIR="${HOME}/.config/goose"
CONFIG_FILE="$GOOSE_CONFIG_DIR/config.yaml"

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

    echo "[entrypoint] CF config: provider=$PROVIDER, model=$MODEL, api_url=${API_URL:-N/A}"
else
    # --- Local/Docker environment ---
    PROVIDER="${GOOSE_PROVIDER:-openai}"
    MODEL="${GOOSE_MODEL:-qwen2.5-coder-32b}"
    API_KEY="${OPENAI_API_KEY:-${ANTHROPIC_API_KEY:-${GOOGLE_API_KEY:-}}}"
    API_URL="${OPENAI_HOST:-}"
fi

# Allow explicit env vars to override CF-detected values
PROVIDER="${GOOSE_PROVIDER:-$PROVIDER}"
MODEL="${GOOSE_MODEL:-$MODEL}"

# Export Goose v1.x environment variables
export GOOSE_PROVIDER="$PROVIDER"
export GOOSE_MODEL="$MODEL"
export GOOSE_MODE=auto
export GOOSE_CONTEXT_STRATEGY=summarize
export GOOSE_MAX_TURNS=50

# Set Goose provider auth via GOOSE_PROVIDER__* env vars (documented interface).
# Also set provider-native vars (OPENAI_API_KEY etc.) as fallback.
[ -n "$API_KEY" ] && export GOOSE_PROVIDER__API_KEY="$API_KEY"
[ -n "$API_URL" ] && export GOOSE_PROVIDER__HOST="$API_URL"

if [ "$PROVIDER" = "openai" ]; then
    [ -n "$API_KEY" ] && export OPENAI_API_KEY="$API_KEY"
    [ -n "$API_URL" ] && export OPENAI_HOST="${API_URL%/}/"
elif [ "$PROVIDER" = "anthropic" ]; then
    [ -n "$API_KEY" ] && export ANTHROPIC_API_KEY="$API_KEY"
elif [ "$PROVIDER" = "google" ]; then
    [ -n "$API_KEY" ] && export GOOGLE_API_KEY="$API_KEY"
fi

# Write config.yaml with developer extension using map format.
# Goose v1.23.x expects extensions as a map (key: config) not a list (- type: ...).
cat > "$CONFIG_FILE" <<CFGEOF
extensions:
  developer:
    bundled: true
    enabled: true
    name: developer
    type: builtin
    timeout: 300
CFGEOF

# Add MCP server extensions (injected by Worldmind orchestrator)
if [ -n "$MCP_SERVERS" ]; then
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
      if [ -n "$TOKEN" ]; then
        export "${TOKEN_VAR}"
        cat >> "$CONFIG_FILE" <<MCP_EOF
  ${NAME}:
    enabled: true
    type: streamable_http
    name: ${NAME}
    uri: ${URL}
    headers:
      Authorization: "Bearer \${${TOKEN_VAR}}"
    env_keys:
      - ${TOKEN_VAR}
    timeout: 300
MCP_EOF
      else
        cat >> "$CONFIG_FILE" <<MCP_EOF
  ${NAME}:
    enabled: true
    type: streamable_http
    name: ${NAME}
    uri: ${URL}
    timeout: 300
MCP_EOF
      fi
      echo "[entrypoint] MCP extension '${NAME}' configured: ${URL}"
    fi
  done
  IFS="$OLDIFS"
fi

echo "[entrypoint] Goose config written: provider=$PROVIDER, model=$MODEL"

# Append CF platform CA certs to system trust store (for Rust TLS)
if [ -n "$CF_SYSTEM_CERT_PATH" ]; then
    COMBINED_CERTS="/tmp/ca-certificates.crt"
    if [ -f /etc/ssl/certs/ca-certificates.crt ]; then
        cp /etc/ssl/certs/ca-certificates.crt "$COMBINED_CERTS"
    else
        : > "$COMBINED_CERTS"
    fi
    for cert in "$CF_SYSTEM_CERT_PATH"/*.crt; do
        [ -f "$cert" ] && cat "$cert" >> "$COMBINED_CERTS"
    done
    export SSL_CERT_FILE="$COMBINED_CERTS"
    echo "[entrypoint] Appended CF CA certs to $COMBINED_CERTS"
fi

# Pass --with-builtin developer as belt-and-suspenders alongside config.yaml.
# Config.yaml (map format) should load extensions, but CLI flag ensures developer loads.
exec goose run --with-builtin developer "$@"
