# Logging Configuration Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Enable host-readable file logging with rotation, env-based log level control, and optional external log4j2 config override via Docker volumes.

**Architecture:** Built-in log4j2.yaml stays console-only with env-variable log levels. External log4j2.yaml.example provides Console+RollingFile template. Docker entrypoint script conditionally loads external config if mounted.

**Tech Stack:** Log4j2 YAML, Docker, docker-compose

---

### Task 1: Update built-in log4j2.yaml

**Files:**
- Modify: `modules/core/src/main/resources/log4j2.yaml`

**Step 1: Replace log4j2.yaml content**

Replace the entire file with:

```yaml
Configuration:
  status: warn
  shutdownHook: disable

  Appenders:
    Console:
      name: Console
      target: SYSTEM_OUT
      PatternLayout:
        pattern: "%d{ISO8601} %5p [%t] %c{1.} :%L - %m%n"

  Loggers:
    Root:
      level: ${env:LOG_LEVEL:-info}
      AppenderRef:
        - ref: Console

    Logger:
      - name: ru.zinin
        level: ${env:APP_LOG_LEVEL:-info}
```

Changes from current:
- Root level: `info` -> `${env:LOG_LEVEL:-info}`
- ru.zinin level: `debug` -> `${env:APP_LOG_LEVEL:-info}`
- Removed commented-out File appender block (lines 12-17, 24)

**Step 2: Commit**

```bash
git add modules/core/src/main/resources/log4j2.yaml
git commit -m "feat: add env-variable log levels, remove commented file appender (FA-24)"
```

---

### Task 2: Create log4j2.yaml.example

**Files:**
- Create: `docker/deploy/log4j2.yaml.example`

**Step 1: Create the example file**

```yaml
# Log4j2 configuration for Docker deployment
# Copy this file to log4j2.yaml to enable file logging with rotation.
# Mount via docker-compose volume: ./log4j2.yaml:/application/config/log4j2.yaml:ro
#
# Environment variables:
#   LOG_LEVEL     - root logger level (default: info)
#   APP_LOG_LEVEL - ru.zinin package level (default: info)
Configuration:
  status: warn
  shutdownHook: disable

  Appenders:
    Console:
      name: Console
      target: SYSTEM_OUT
      PatternLayout:
        pattern: "%d{ISO8601} %5p [%t] %c{1.} :%L - %m%n"

    RollingFile:
      name: FileAppender
      fileName: /application/logs/frigate-analyzer.log
      filePattern: /application/logs/frigate-analyzer-%i.log.gz
      append: true
      PatternLayout:
        pattern: "%d{ISO8601} %5p [%t] %c{1.} :%L - %m%n"
      Policies:
        SizeBasedTriggeringPolicy:
          size: 50MB
      DefaultRolloverStrategy:
        max: 10

  Loggers:
    Root:
      level: ${env:LOG_LEVEL:-info}
      AppenderRef:
        - ref: Console
        - ref: FileAppender

    Logger:
      - name: ru.zinin
        level: ${env:APP_LOG_LEVEL:-info}
```

**Step 2: Commit**

```bash
git add docker/deploy/log4j2.yaml.example
git commit -m "feat: add log4j2.yaml.example with file logging and rotation (FA-24)"
```

---

### Task 3: Create Docker entrypoint script

**Files:**
- Create: `docker/deploy/docker-entrypoint.sh`
- Modify: `docker/deploy/Dockerfile`

**Step 1: Create entrypoint script**

```sh
#!/bin/sh
JAVA_OPTS="--enable-native-access=ALL-UNNAMED -XX:MaxRAMPercentage=75.0 -XX:AOTCache=application.aot"

if [ -f /application/config/log4j2.yaml ]; then
  JAVA_OPTS="$JAVA_OPTS -Dlogging.config=/application/config/log4j2.yaml"
fi

exec java $JAVA_OPTS -jar application.jar "$@"
```

**Step 2: Update Dockerfile**

In `docker/deploy/Dockerfile`, apply these changes:

1. After the `RUN mkdir -p` block (line 16-19), add `/application/logs` and `/application/config` to the created directories:

Replace:
```dockerfile
RUN mkdir -p /tmp/frigate-analyzer && \
    addgroup -g 1000 appgroup && \
    adduser -u 1000 -G appgroup -D appuser && \
    chown -R appuser:appgroup /application /tmp/frigate-analyzer
```

With:
```dockerfile
RUN mkdir -p /tmp/frigate-analyzer /application/logs /application/config && \
    addgroup -g 1000 appgroup && \
    adduser -u 1000 -G appgroup -D appuser && \
    chown -R appuser:appgroup /application /tmp/frigate-analyzer
```

2. Before `USER appuser` (line 35), add the entrypoint script copy:

Add after `RUN chown -R appuser:appgroup /application` (line 33):
```dockerfile
COPY docker-entrypoint.sh /application/docker-entrypoint.sh
RUN chmod +x /application/docker-entrypoint.sh
```

3. Replace the ENTRYPOINT (line 39):

Replace:
```dockerfile
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-XX:MaxRAMPercentage=75.0", "-XX:AOTCache=application.aot", "-jar", "application.jar"]
```

With:
```dockerfile
ENTRYPOINT ["/application/docker-entrypoint.sh"]
```

**Step 3: Commit**

```bash
git add docker/deploy/docker-entrypoint.sh docker/deploy/Dockerfile
git commit -m "feat: add entrypoint script with conditional log4j2 config loading (FA-24)"
```

---

### Task 4: Update docker-compose.yml and .env.example

**Files:**
- Modify: `docker/deploy/docker-compose.yml`
- Modify: `docker/deploy/.env.example`

**Step 1: Update docker-compose.yml**

In the `frigate-analyzer` service, add volumes and environment variables.

Add to `volumes:` section (after line 23):
```yaml
      - ./logs:/application/logs
      # Uncomment to override log4j2 config (copy log4j2.yaml.example to log4j2.yaml first):
      # - ./log4j2.yaml:/application/config/log4j2.yaml:ro
```

Add to `environment:` section (after line 26):
```yaml
      - LOG_LEVEL=${LOG_LEVEL:-info}
      - APP_LOG_LEVEL=${APP_LOG_LEVEL:-info}
```

**Step 2: Update .env.example**

Add at the end of `.env.example`:
```
# Logging
LOG_LEVEL=info
APP_LOG_LEVEL=info
```

**Step 3: Commit**

```bash
git add docker/deploy/docker-compose.yml docker/deploy/.env.example
git commit -m "feat: add log volumes and env variables to docker-compose (FA-24)"
```

---

### Task 5: Verify and final commit

**Step 1: Review all changes**

```bash
git log --oneline master..HEAD
git diff master..HEAD --stat
```

Verify the following files were changed:
- `modules/core/src/main/resources/log4j2.yaml`
- `docker/deploy/log4j2.yaml.example` (new)
- `docker/deploy/docker-entrypoint.sh` (new)
- `docker/deploy/Dockerfile`
- `docker/deploy/docker-compose.yml`
- `docker/deploy/.env.example`

**Step 2: Run build to verify log4j2.yaml syntax**

Use build-runner agent:
```bash
./gradlew build -x test
```

The build should succeed — if log4j2.yaml has syntax errors, Spring Boot will fail to start during AOT processing.

**Step 3: Fix any issues and commit if needed**
