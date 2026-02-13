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

# --- Credential resolution ---
# Parse VCAP_SERVICES for any GenAI model binding (CredHub-managed credentials).
# If no model is bound, the orchestrator's env vars provide credentials instead.
# Other VCAP bindings (Postgres, etc.) are unaffected — only GenAI labels are parsed.

if [ -n "$VCAP_SERVICES" ]; then
    echo "[entrypoint] Cloud Foundry environment detected, parsing VCAP_SERVICES..."

    # Parse credentials from bound GenAI service (if any).
    # Sets VCAP_PROVIDER, VCAP_MODEL, VCAP_API_KEY, VCAP_API_URL only if a GenAI binding exists.
    eval "$(echo "$VCAP_SERVICES" | python3 -c "
import sys, json, os
vcap = json.load(sys.stdin)
for label in vcap:
    for svc in vcap[label]:
        creds = svc.get('credentials', {})
        if 'model_provider' in creds:
            print('VCAP_PROVIDER=' + creds['model_provider'])
        elif any(k in creds for k in ('uri', 'url', 'api_url', 'api_base')):
            print('VCAP_PROVIDER=openai')
        else:
            continue
        model = creds.get('model_name') or creds.get('model') or ''
        if model:
            print('VCAP_MODEL=' + model)
        api_key = creds.get('api_key') or creds.get('apiKey') or creds.get('key', '')
        if api_key:
            print('VCAP_API_KEY=' + api_key)
        api_url = creds.get('uri') or creds.get('url') or creds.get('api_url') or creds.get('api_base', '')
        if api_url:
            print('VCAP_API_URL=' + api_url)
        sys.exit(0)
" 2>/dev/null)"

    if [ -n "$VCAP_PROVIDER" ]; then
        echo "[entrypoint] Bound model: provider=$VCAP_PROVIDER, api_url=${VCAP_API_URL:-N/A}"
    else
        echo "[entrypoint] No GenAI model binding found in VCAP_SERVICES"
    fi
fi

# Resolve provider: GOOSE_PROVIDER > auto-detect from API key > VCAP binding > default
if [ -n "${GOOSE_PROVIDER:-}" ]; then
    PROVIDER="$GOOSE_PROVIDER"
elif [ -n "${ANTHROPIC_API_KEY:-}" ]; then
    PROVIDER=anthropic
elif [ -n "${GOOGLE_API_KEY:-}" ]; then
    PROVIDER=google
elif [ -n "${VCAP_PROVIDER:-}" ]; then
    PROVIDER="$VCAP_PROVIDER"
else
    PROVIDER=openai
fi
MODEL="${GOOSE_MODEL:-${VCAP_MODEL:-}}"

# Export Goose v1.x environment variables
export GOOSE_PROVIDER="$PROVIDER"
export GOOSE_MODEL="$MODEL"
export GOOSE_MODE=auto
export GOOSE_CONTEXT_STRATEGY=summarize
export GOOSE_MAX_TURNS=50

# Resolve API key: direct key (GOOSE_PROVIDER__API_KEY) > VCAP binding key
# When the orchestrator provides a direct key, the VCAP binding is skipped for auth.
# This lets you use any provider by setting the key in .env without unbinding services.
if [ -z "$GOOSE_PROVIDER__API_KEY" ] && [ -n "$VCAP_API_KEY" ]; then
    export GOOSE_PROVIDER__API_KEY="$VCAP_API_KEY"
    [ -n "$VCAP_API_URL" ] && export GOOSE_PROVIDER__HOST="$VCAP_API_URL"
    # Set provider-native vars for compatibility
    if [ "$PROVIDER" = "openai" ]; then
        export OPENAI_API_KEY="$VCAP_API_KEY"
        [ -n "$VCAP_API_URL" ] && export OPENAI_HOST="${VCAP_API_URL%/}/"
    elif [ "$PROVIDER" = "anthropic" ]; then
        export ANTHROPIC_API_KEY="$VCAP_API_KEY"
    elif [ "$PROVIDER" = "google" ]; then
        export GOOGLE_API_KEY="$VCAP_API_KEY"
    fi
    echo "[entrypoint] Using VCAP binding credentials"
else
    echo "[entrypoint] Using orchestrator-provided credentials"
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
    description: "${NAME} MCP server"
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
    description: "${NAME} MCP server"
    uri: ${URL}
    timeout: 300
MCP_EOF
      fi
      if [ -n "$TOKEN" ]; then
        echo "[entrypoint] MCP extension '${NAME}' configured: ${URL} (token: set)"
      else
        echo "[entrypoint] MCP extension '${NAME}' configured: ${URL} (token: none)"
      fi
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
# The instruction file is passed as $1 — use -i/--instructions flag (not positional arg).
exec goose run --no-session --with-builtin developer -i "$@"
