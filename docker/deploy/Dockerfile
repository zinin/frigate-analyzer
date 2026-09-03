# Этап 1: Извлечение слоёв из JAR
FROM azul/zulu-openjdk-alpine:25 AS builder
WORKDIR /builder
ARG JAR_FILE=modules/core/build/libs/frigate-analyzer-core.jar
COPY ${JAR_FILE} application.jar
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted

# Этап 2: Runtime контейнер
FROM azul/zulu-openjdk-alpine:25
WORKDIR /application

# Grok Build (xAI) pinned: the app depends on specific headless flags and the JSON output shape.
ARG GROK_VERSION=1.0.13

# Runtime deps: ffmpeg/fontconfig (existing) + bash/libgcc/libstdc++/ripgrep (Claude CLI on musl per Anthropic docs).
RUN apk add --no-cache ffmpeg curl fontconfig ttf-dejavu bash libgcc libstdc++ ripgrep

# Создание временных директорий и non-root user
RUN mkdir -p /tmp/frigate-analyzer /application/logs /application/config /application/grok-home && \
    addgroup -g 1000 appgroup && \
    adduser -u 1000 -G appgroup -D appuser && \
    chown -R appuser:appgroup /application /tmp/frigate-analyzer

# Копирование слоёв (от редко меняющихся к часто меняющимся)
COPY --from=builder /builder/extracted/dependencies/ ./
COPY --from=builder /builder/extracted/spring-boot-loader/ ./
COPY --from=builder /builder/extracted/snapshot-dependencies/ ./
COPY --from=builder /builder/extracted/application/ ./

COPY docker/deploy/docker-entrypoint.sh /application/docker-entrypoint.sh
RUN chmod +x /application/docker-entrypoint.sh && \
    chown -R appuser:appgroup /application

# Install Claude Code CLI as appuser (native installer puts the binary in ~/.local/bin).
# Also drop a settings.json disabling the built-in ripgrep (use the apk one above) and the
# auto-updater (deterministic image — new CLI arrives only through a rebuilt image).
# NB: WORKDIR /tmp during install — Anthropic docs note the installer's filesystem scan
# can hang or OOM if WORKDIR has a large tree (e.g. our /application with JAR + AOT cache).
WORKDIR /tmp
USER appuser
RUN curl -fsSL https://claude.ai/install.sh | bash && \
    mkdir -p /home/appuser/.claude && \
    printf '%s\n' '{' \
                  '  "env": {' \
                  '    "USE_BUILTIN_RIPGREP": "0",' \
                  '    "DISABLE_AUTOUPDATER": "1"' \
                  '  }' \
                  '}' > /home/appuser/.claude/settings.json

# Install Grok Build as appuser: static binary in ~/.grok/bin/grok, symlink in ~/.local/bin (already
# on PATH below). GROK_HOME at runtime points at the mounted volume, not at this ~/.grok.
RUN curl -fsSL https://x.ai/cli/install.sh | bash -s "$GROK_VERSION" && \
    /home/appuser/.local/bin/grok --version

# Put the CLI on PATH globally so both the entrypoint probe (`command -v claude`)
# and the JVM subprocess (ProcessBuilder) can find it. Needs root to mutate ENV;
# drop back to appuser before the app runs.
USER root
ENV PATH="/home/appuser/.local/bin:${PATH}"
USER appuser
WORKDIR /application

EXPOSE 8080

ENTRYPOINT ["/application/docker-entrypoint.sh"]
