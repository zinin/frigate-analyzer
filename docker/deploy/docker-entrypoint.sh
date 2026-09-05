#!/bin/sh
set -- --enable-native-access=ALL-UNNAMED -XX:MaxRAMPercentage=75.0

if [ -f /application/config/log4j2.yaml ]; then
  echo "Using external log4j2 config: /application/config/log4j2.yaml"
  set -- "$@" -Dlogging.config=/application/config/log4j2.yaml
else
  echo "Using built-in log4j2 config (console only)"
fi

if [ "${APP_AI_DESCRIPTION_ENABLED:-false}" = "true" ]; then
  # Пресеты живут в application-docker.yaml и шеллу не видны: проверяем не выбранный провайдер, а
  # тех, чьи входные данные присутствуют.
  if [ -n "${CLAUDE_CODE_OAUTH_TOKEN:-}" ] || [ -n "${ANTHROPIC_AUTH_TOKEN:-}" ]; then
      if [ -n "${CLAUDE_CLI_PATH:-}" ]; then
          # Explicit path override — check it directly; falling back to PATH would give a false negative.
          if [ -x "${CLAUDE_CLI_PATH}" ]; then
              echo "INFO: claude CLI detected at ${CLAUDE_CLI_PATH}: $(${CLAUDE_CLI_PATH} --version 2>/dev/null || echo 'unknown')"
          else
              echo "WARN: explicit CLAUDE_CLI_PATH=${CLAUDE_CLI_PATH} not found or not executable; claude presets will return fallback." >&2
          fi
      elif ! command -v claude >/dev/null 2>&1; then
          echo "WARN: claude CLI not found in PATH (CLAUDE_CLI_PATH is empty); claude presets will return fallback." >&2
      else
          echo "INFO: claude CLI detected: $(claude --version 2>/dev/null || echo 'unknown')"
      fi
  else
      echo "INFO: neither CLAUDE_CODE_OAUTH_TOKEN nor ANTHROPIC_AUTH_TOKEN is set; claude presets will be marked unavailable."
  fi

  # ВАЖНО: сам по себе непустой GROK_HOME признаком не является — в docker-compose.yml он задан
  # ВСЕГДА (:35) и том монтируется всегда (:27). Гейт по нему выдал бы WARN про отсутствующий
  # auth.json каждому claude-only деплою, а дизайн обещает "WARN только на сломанное".
  grok_intended=false
  if [ -f "${GROK_HOME:-}/auth.json" ] || [ -f "${GROK_HOME:-}/config.toml" ] || \
     [ "$(printf '%s' "${APP_AI_DESCRIPTION_PROVIDER:-}" | tr '[:upper:]' '[:lower:]')" = "grok" ]; then
      grok_intended=true
  fi

  if [ "$grok_intended" = true ] && [ -n "${GROK_HOME:-}" ]; then
      if [ -n "${GROK_CLI_PATH:-}" ]; then
          if [ -x "${GROK_CLI_PATH}" ]; then
              echo "INFO: grok CLI detected at ${GROK_CLI_PATH}: $(${GROK_CLI_PATH} --version 2>/dev/null || echo 'unknown')"
          else
              echo "WARN: explicit GROK_CLI_PATH=${GROK_CLI_PATH} not found or not executable; grok presets will return fallback." >&2
          fi
      elif ! command -v grok >/dev/null 2>&1; then
          echo "WARN: grok CLI not found in PATH (GROK_CLI_PATH is empty); grok presets will return fallback." >&2
      else
          echo "INFO: grok CLI detected: $(grok --version 2>/dev/null || echo 'unknown')"
      fi
      if [ ! -d "${GROK_HOME}" ] || [ ! -w "${GROK_HOME}" ]; then
          echo "WARN: GROK_HOME=${GROK_HOME} is missing or not writable; 'grok login' and token refresh will fail. On the host: mkdir -p grok-home && chown 1000:1000 grok-home" >&2
      elif [ ! -f "${GROK_HOME}/auth.json" ]; then
          echo "WARN: ${GROK_HOME}/auth.json not found; run 'docker compose exec frigate-analyzer grok login --device-code' (not needed for BYOK models with their own api_key in config.toml)." >&2
      else
          echo "INFO: grok credentials found in ${GROK_HOME}"
      fi
  elif [ "$grok_intended" = true ]; then
      echo "WARN: GROK_HOME is not set; grok presets would fall back to the ephemeral default under the temp folder, point GROK_HOME at a mounted volume." >&2
  else
      echo "INFO: no grok credentials found under GROK_HOME and APP_AI_DESCRIPTION_PROVIDER is not 'grok'; skipping grok checks."
  fi

  # Две диагностики, которые нельзя терять вместе со старым `case`:
  # 1) самый частый misconfig — включённая фича без единого признака провайдера. Legacy-синтез
  #    одного claude-пресета без токена по-прежнему валит старт по правилу "ноль годных", и это
  #    не должно выглядеть мягким INFO плюс падение JVM.
  if [ -z "${CLAUDE_CODE_OAUTH_TOKEN:-}" ] && [ -z "${ANTHROPIC_AUTH_TOKEN:-}" ] && [ "$grok_intended" != true ]; then
      echo "WARN: APP_AI_DESCRIPTION_ENABLED=true but neither a Claude token nor grok credentials were found; startup will fail if the only declared preset is unusable." >&2
  fi
  # 2) опечатка в legacy-переменной остаётся мягкой на уровне приложения, поэтому шелл — последнее
  #    место, где её ещё видно рядом с причиной.
  case "$(printf '%s' "${APP_AI_DESCRIPTION_PROVIDER:-}" | tr '[:upper:]' '[:lower:]')" in
      ''|claude|grok) ;;
      *) echo "WARN: unknown APP_AI_DESCRIPTION_PROVIDER='${APP_AI_DESCRIPTION_PROVIDER}'; it is ignored when presets are declared, and yields no preset otherwise." >&2 ;;
  esac

  echo "INFO: the active preset is chosen in application-docker.yaml and in the /ai dialog; this check only reports which providers look usable."
fi

exec java "$@" -jar application.jar
