# Logging Configuration Design

## Goal

Enable host-readable file logging with rotation, configurable log levels via environment variables, and optional external log4j2 config override through Docker volumes.

## Current State

- Log4j2 with Console-only appender, root=info, ru.zinin=debug
- No file logging, no Docker log volumes, no external config override
- Dependencies already in place: spring-boot-starter-log4j2, kotlin-logging-jvm

## Design

### Built-in log4j2.yaml (classpath)

Console appender only. Log levels controlled via env variables:

- `LOG_LEVEL` (default: `info`) — root logger level
- `APP_LOG_LEVEL` (default: `info`) — `ru.zinin` package level

No file appender. Removed commented-out File appender block.

### External log4j2.yaml.example (docker/deploy/)

Full config with Console + RollingFile appenders:

- **RollingFile path:** `/application/logs/frigate-analyzer.log`
- **Archive pattern:** `/application/logs/frigate-analyzer-%i.log.gz`
- **Rotation:** SizeBasedTriggeringPolicy at 50MB
- **Retention:** DefaultRolloverStrategy max=10 (total ~500MB max)
- Same env variable substitution for log levels

User copies to `log4j2.yaml` and mounts via Docker volume.

### Dockerfile Changes

- Create `/application/logs` directory owned by `appuser`
- Create `/application/config` directory owned by `appuser` (if not exists)

### docker-compose.yml Changes (deploy)

Volumes:
- `./logs:/application/logs` — log file output directory
- `./log4j2.yaml:/application/config/log4j2.yaml:ro` — commented, with explanation

Environment variables:
- `LOG_LEVEL=${LOG_LEVEL:-info}`
- `APP_LOG_LEVEL=${APP_LOG_LEVEL:-info}`

Command override with conditional config loading:
```sh
sh -c 'if [ -f /application/config/log4j2.yaml ]; then exec java -Dlogging.config=/application/config/log4j2.yaml -jar app.jar; else exec java -jar app.jar; fi'
```

### .env.example Changes

Add:
- `LOG_LEVEL=info`
- `APP_LOG_LEVEL=info`

## Files Changed

| File | Action |
|------|--------|
| modules/core/src/main/resources/log4j2.yaml | Update: env vars, remove commented file appender |
| docker/deploy/log4j2.yaml.example | Create: full config with RollingFile |
| docker/deploy/Dockerfile | Update: add /application/logs directory |
| docker/deploy/docker-compose.yml | Update: volumes, env, command |
| docker/deploy/.env.example | Update: add LOG_LEVEL, APP_LOG_LEVEL |

## Not Changed

- Build dependencies (already correct)
- Log pattern format (kept as-is)
- Test configurations
- Development docker-compose.yml
