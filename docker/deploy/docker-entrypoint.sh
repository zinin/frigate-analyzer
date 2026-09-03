#!/bin/sh
set -- --enable-native-access=ALL-UNNAMED -XX:MaxRAMPercentage=75.0

if [ -f /application/config/log4j2.yaml ]; then
  echo "Using external log4j2 config: /application/config/log4j2.yaml"
  set -- "$@" -Dlogging.config=/application/config/log4j2.yaml
else
  echo "Using built-in log4j2 config (console only)"
fi

if [ "${APP_AI_DESCRIPTION_ENABLED:-false}" = "true" ]; then
  case "${APP_AI_DESCRIPTION_PROVIDER:-claude}" in
    claude)
      if [ -z "${CLAUDE_CODE_OAUTH_TOKEN:-}" ] && [ -z "${ANTHROPIC_AUTH_TOKEN:-}" ]; then
          # ClaudeBackend.init fails fast with IllegalStateException when neither token is set
          # (avoid a silently broken feature). Advisory WARN here so the hint reaches stderr
          # before the JVM stack trace drowns it out.
          echo "WARN: APP_AI_DESCRIPTION_ENABLED=true but neither CLAUDE_CODE_OAUTH_TOKEN nor ANTHROPIC_AUTH_TOKEN is set; application will FAIL at startup." >&2
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
      ;;
    grok)
      if [ -n "${GROK_CLI_PATH:-}" ]; then
          if [ -x "${GROK_CLI_PATH}" ]; then
              echo "INFO: grok CLI detected at ${GROK_CLI_PATH}: $(${GROK_CLI_PATH} --version 2>/dev/null || echo 'unknown')"
          else
              echo "WARN: explicit GROK_CLI_PATH=${GROK_CLI_PATH} not found or not executable; AI descriptions will return fallback." >&2
          fi
      elif ! command -v grok >/dev/null 2>&1; then
          echo "WARN: grok CLI not found in PATH (GROK_CLI_PATH is empty); AI descriptions will return fallback." >&2
      else
          echo "INFO: grok CLI detected: $(grok --version 2>/dev/null || echo 'unknown')"
      fi
      if [ -n "${GROK_HOME:-}" ]; then
          if [ ! -d "${GROK_HOME}" ] || [ ! -w "${GROK_HOME}" ]; then
              echo "WARN: GROK_HOME=${GROK_HOME} is missing or not writable; 'grok login' and token refresh will fail. On the host: mkdir -p grok-home && chown 1000:1000 grok-home" >&2
          elif [ ! -f "${GROK_HOME}/auth.json" ]; then
              echo "WARN: ${GROK_HOME}/auth.json not found; run 'docker compose exec frigate-analyzer grok login --device-code' (not needed for BYOK models with their own api_key in config.toml)." >&2
          else
              echo "INFO: grok credentials found in ${GROK_HOME}"
          fi
      else
          echo "WARN: GROK_HOME is not set; the application default under the temp folder is ephemeral, point GROK_HOME at a mounted volume." >&2
      fi
      ;;
    *)
      echo "WARN: unknown APP_AI_DESCRIPTION_PROVIDER='${APP_AI_DESCRIPTION_PROVIDER}' (known: claude, grok); AI descriptions will return fallback." >&2
      ;;
  esac
fi

exec java "$@" -jar application.jar
