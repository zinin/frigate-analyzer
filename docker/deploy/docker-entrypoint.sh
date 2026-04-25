#!/bin/sh
set -- --enable-native-access=ALL-UNNAMED -XX:MaxRAMPercentage=75.0 -XX:AOTCache=application.aot

if [ -f /application/config/log4j2.yaml ]; then
  echo "Using external log4j2 config: /application/config/log4j2.yaml"
  set -- "$@" -Dlogging.config=/application/config/log4j2.yaml
else
  echo "Using built-in log4j2 config (console only)"
fi

if [ "${APP_AI_DESCRIPTION_ENABLED:-false}" = "true" ]; then
    if [ -z "${CLAUDE_CODE_OAUTH_TOKEN:-}" ]; then
        # ClaudeDescriptionAgent.init fails fast with IllegalStateException when the token is
        # missing (design §4 — avoid a silently broken feature). Advisory WARN here so the hint
        # reaches stderr before the JVM stack trace drowns it out.
        echo "WARN: APP_AI_DESCRIPTION_ENABLED=true but CLAUDE_CODE_OAUTH_TOKEN is empty; application will FAIL at startup." >&2
    elif [ -n "${CLAUDE_CLI_PATH:-}" ]; then
        # Explicit path override — check it directly; falling back to PATH would give a false negative.
        if [ -x "${CLAUDE_CLI_PATH}" ]; then
            echo "INFO: claude CLI detected at ${CLAUDE_CLI_PATH}: $(${CLAUDE_CLI_PATH} --version 2>/dev/null || echo 'unknown')"
        else
            echo "WARN: explicit CLAUDE_CLI_PATH=${CLAUDE_CLI_PATH} not found or not executable; AI descriptions will return fallback." >&2
        fi
    elif ! command -v claude >/dev/null 2>&1; then
        echo "WARN: claude CLI not found in PATH (CLAUDE_CLI_PATH is empty); AI descriptions will return fallback." >&2
    else
        echo "INFO: claude CLI detected: $(claude --version 2>/dev/null || echo 'unknown')"
    fi
fi

exec java "$@" -jar application.jar
