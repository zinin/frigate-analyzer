# Containerization & CI/CD Design

## Overview

Setup container build, CI/CD pipeline with GitHub Actions, DockerHub publishing, and production docker-compose for Frigate Analyzer.

## Docker Hub Images

| Image | Purpose |
|-------|---------|
| `avzinin/frigate-analyzer` | Application |
| `avzinin/frigate-analyzer-liquibase` | Database migrations |

Tags per release `v1.2.3`: `1.2.3` + `latest`

## File Structure

### New files

```
.github/workflows/
  ci.yml                               # Build + test on push to master & PRs
  docker-publish.yml                   # Build & publish Docker images on tag v*
docker/deploy/
  Dockerfile                           # App Dockerfile (moved from docker/app/)
  docker-compose.yml                   # Production: app + liquibase (external DB)
  .env.example                         # Environment variables template
  application-local.yaml.example       # Detect-servers config template
```

### Deleted

- `docker/app/` directory (Dockerfile moves to `docker/deploy/`)

### Unchanged

- `docker/liquibase/Dockerfile` — migrations image
- `docker/docker-compose.yml` — local development only
- `docker/test-compose.yml` — test infrastructure
- `docker/postgres/` — local development DB

## CI/CD Pipeline (GitHub Actions)

### Workflow 1: `ci.yml` — Build & Test

- **Triggers:** push to `master`, pull requests to `master`
- **Steps:** checkout -> setup Java 25 (Zulu) -> Gradle build -> tests
- **Purpose:** Ensure code compiles and tests pass

### Workflow 2: `docker-publish.yml` — Docker Build & Publish

- **Triggers:** tag `v*` only
- **Steps:**
  1. Checkout
  2. Setup Java 25 (Zulu) -> Gradle build (produces JAR)
  3. Login to DockerHub
  4. Build & push `avzinin/frigate-analyzer` (from `docker/deploy/Dockerfile`)
  5. Build & push `avzinin/frigate-analyzer-liquibase` (from `docker/liquibase/Dockerfile`)
- **Secrets:** `DOCKERHUB_USERNAME`, `DOCKERHUB_TOKEN`
- **Image tags:** extract version from git tag `v1.2.3` -> `1.2.3` + `latest`

## Production Docker Compose

`docker/deploy/docker-compose.yml`:

```yaml
services:
  frigate-analyzer-liquibase:
    image: avzinin/frigate-analyzer-liquibase:latest
    environment:
      - DB_HOST=${DB_HOST}
      - DB_PORT=${DB_PORT:-5432}
      - DB_NAME=${DB_NAME:-frigate_analyzer}
      - DB_USER=${DB_USER:-frigate_analyzer_rw}
      - DB_PASS=${DB_PASS}
    restart: "no"

  frigate-analyzer:
    image: avzinin/frigate-analyzer:latest
    depends_on:
      frigate-analyzer-liquibase:
        condition: service_completed_successfully
    env_file: .env
    volumes:
      - ${FRIGATE_RECORDS_FOLDER:-/mnt/data/frigate/recordings}:/mnt/data/frigate/recordings:ro
      - ./application-local.yaml:/application/config/application-local.yaml:ro
    environment:
      - SPRING_PROFILES_ACTIVE=local
    ports:
      - "${APP_PORT:-8080}:8080"
    restart: unless-stopped
```

## Configuration Strategy (Combined)

### Simple settings via `.env` file

`.env.example`:

```env
# Database (external)
DB_HOST=192.168.1.100
DB_PORT=5432
DB_NAME=frigate_analyzer
DB_USER=frigate_analyzer_rw
DB_PASS=secret

# Telegram
TELEGRAM_BOT_TOKEN=your-bot-token
TELEGRAM_OWNER=your-username

# Paths
FRIGATE_RECORDS_FOLDER=/mnt/data/frigate/recordings

# App
APP_PORT=8080
```

### Complex structures via YAML bind mount

`application-local.yaml.example` — for detect-servers and other nested config:

```yaml
application:
  detect-servers:
    mypc:
      host: 192.168.1.50
      port: 3001
      frame-requests:
        simultaneous-count: 4
        priority: 1
      frames-extract-requests:
        simultaneous-count: 1
        priority: 3
      visualize-requests:
        simultaneous-count: 1
        priority: 1
```

File is mounted to `/application/config/application-local.yaml`. Spring Boot automatically discovers config files in `./config/` directory relative to working directory (`/application`).

## Deployment Flow

1. User creates `.env` from `.env.example`
2. User creates `application-local.yaml` from example
3. `docker compose up -d` starts liquibase (migrations) then app
4. Frigate recordings are mounted read-only from host

## Key Decisions

- **No DB in production compose** — user manages their own PostgreSQL
- **Two separate DockerHub images** — app and migrations are independent
- **Combined config approach** — `.env` for simple values, YAML mount for nested structures
- **Dockerfile stays as Dockerfile** — no rename to Containerfile needed
- **Deploy files in `docker/deploy/`** — separated from local dev infrastructure
