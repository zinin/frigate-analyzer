# Этап 1: Извлечение слоёв из JAR
FROM azul/zulu-openjdk-alpine:25 AS builder
WORKDIR /builder
ARG JAR_FILE=modules/core/build/libs/frigate-analyzer-core.jar
COPY ${JAR_FILE} application.jar
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted

# Этап 2: Runtime контейнер
FROM azul/zulu-openjdk-alpine:25
WORKDIR /application

# Установка ffmpeg
RUN apk add --no-cache ffmpeg curl

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

COPY docker-entrypoint.sh /application/docker-entrypoint.sh
RUN chmod +x /application/docker-entrypoint.sh && \
    chown -R appuser:appgroup /application

USER appuser

EXPOSE 8080

ENTRYPOINT ["/application/docker-entrypoint.sh"]
