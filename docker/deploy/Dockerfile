# Этап 1: Извлечение слоёв из JAR
FROM azul/zulu-openjdk-alpine:25 AS builder
WORKDIR /builder
ARG JAR_FILE=modules/core/build/libs/frigate-analyzer-core.jar
COPY ${JAR_FILE} application.jar
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted

# Этап 2: Runtime контейнер
FROM azul/zulu-openjdk-alpine:25
WORKDIR /application

# Runtime deps: ffmpeg/fontconfig (existing) + bash/libgcc/libstdc++/ripgrep (Claude CLI on musl per Anthropic docs).
RUN apk add --no-cache ffmpeg curl fontconfig ttf-dejavu bash libgcc libstdc++ ripgrep

# Создание временных директорий и non-root user
RUN mkdir -p /tmp/frigate-analyzer /application/logs /application/config && \
    addgroup -g 1000 appgroup && \
    adduser -u 1000 -G appgroup -D appuser && \
    chown -R appuser:appgroup /application /tmp/frigate-analyzer

# Копирование слоёв (от редко меняющихся к часто меняющимся)
COPY --from=builder /builder/extracted/dependencies/ ./
COPY --from=builder /builder/extracted/spring-boot-loader/ ./
COPY --from=builder /builder/extracted/snapshot-dependencies/ ./
COPY --from=builder /builder/extracted/application/ ./

# Build AOT cache for faster startup (Java 25)
RUN java --enable-native-access=ALL-UNNAMED \
         -XX:AOTCacheOutput=application.aot \
         -Dspring.context.exit=onRefresh \
         -jar application.jar || true

COPY docker/deploy/docker-entrypoint.sh /application/docker-entrypoint.sh
RUN chmod +x /application/docker-entrypoint.sh && \
    chown -R appuser:appgroup /application

# Install Claude Code CLI as appuser (native installer puts the binary in ~/.local/bin).
# Also drop a settings.json disabling the built-in ripgrep (use the apk one above) and the
# auto-updater (deterministic image — new CLI arrives only through a rebuilt image).
USER appuser
RUN curl -fsSL https://claude.ai/install.sh | bash && \
    mkdir -p /home/appuser/.claude && \
    printf '%s\n' '{' \
                  '  "env": {' \
                  '    "USE_BUILTIN_RIPGREP": "0",' \
                  '    "DISABLE_AUTOUPDATER": "1"' \
                  '  }' \
                  '}' > /home/appuser/.claude/settings.json

# Put the CLI on PATH globally so both the entrypoint probe (`command -v claude`)
# and the JVM subprocess (ProcessBuilder) can find it. Needs root to mutate ENV;
# drop back to appuser before the app runs.
USER root
ENV PATH="/home/appuser/.local/bin:${PATH}"
USER appuser

EXPOSE 8080

ENTRYPOINT ["/application/docker-entrypoint.sh"]
